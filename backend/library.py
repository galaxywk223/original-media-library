from __future__ import annotations

import json
import mimetypes
import os
import re
import shutil
import subprocess
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

from PIL import Image
from send2trash import send2trash
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session, selectinload

from .config import AppPaths
from .models import MediaAsset, MediaCollection, new_id, utcnow

SUPPORTED_IMAGES = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".avif"}
SUPPORTED_VIDEOS = {".mp4", ".webm", ".mov", ".mkv", ".m4v"}
SEQUENCE_RE = re.compile(r"^(?P<base>.+)_(?P<sequence>\d{2,3})$")
INVALID_FILENAME_RE = re.compile(r'[<>:"/\\|?*\r\n]+')


class LibraryService:
    def __init__(self, paths: AppPaths) -> None:
        self.paths = paths
        self.paths.thumbnail_dir.mkdir(parents=True, exist_ok=True)

    def scan(self, session: Session, root: Path) -> int:
        root = root.resolve()
        root.mkdir(parents=True, exist_ok=True)
        candidates = [
            path
            for path in root.rglob("*")
            if path.is_file()
            and path.suffix.lower() in SUPPORTED_IMAGES | SUPPORTED_VIDEOS
            and ".app-data" not in path.parts
            and not path.name.endswith(".part")
        ]
        existing_assets = {
            Path(asset.path).resolve(): asset
            for asset in session.scalars(select(MediaAsset)).all()
        }
        current_paths = {path.resolve() for path in candidates}

        for asset_path, asset in list(existing_assets.items()):
            if self._inside(asset_path, root) and asset_path not in current_paths:
                session.delete(asset)

        groups: dict[tuple[Path, str], list[tuple[Path, int]]] = defaultdict(list)
        for path in candidates:
            resolved = path.resolve()
            if resolved in existing_assets:
                self.refresh_asset(existing_assets[resolved])
                continue
            match = SEQUENCE_RE.match(path.stem)
            base = match.group("base") if match else path.stem
            sequence = int(match.group("sequence")) if match else 1
            groups[(path.parent.resolve(), base)].append((path, sequence))

        added = 0
        for (_, base), items in groups.items():
            items.sort(key=lambda item: item[1])
            collection = MediaCollection(
                title=base,
                media_type=self._collection_type([path for path, _ in items]),
                item_count=len(items),
                imported=True,
            )
            session.add(collection)
            session.flush()
            for path, sequence in items:
                session.add(self.build_asset(collection.id, path, sequence))
                added += 1

        session.flush()
        self._remove_empty_collections(session)
        self._refresh_collection_counts(session)
        session.commit()
        return added

    def add_download(
        self,
        session: Session,
        *,
        title: str,
        author: str | None,
        source_url: str,
        aweme_id: str | None,
        source_created_at: datetime | None,
        paths: list[Path],
    ) -> MediaCollection:
        collection = MediaCollection(
            title=title.strip() or "未命名作品",
            author=author,
            source_url=source_url,
            aweme_id=aweme_id,
            media_type=self._collection_type(paths),
            item_count=len(paths),
            imported=False,
            source_created_at=source_created_at,
        )
        session.add(collection)
        session.flush()
        for index, path in enumerate(paths, start=1):
            session.add(self.build_asset(collection.id, path, index))
        session.commit()
        return collection

    def build_asset(self, collection_id: str, path: Path, sequence: int) -> MediaAsset:
        path = path.resolve()
        stat = path.stat()
        kind = "image" if path.suffix.lower() in SUPPORTED_IMAGES else "video"
        width: int | None = None
        height: int | None = None
        duration: float | None = None
        if kind == "image":
            try:
                with Image.open(path) as image:
                    width, height = image.size
            except OSError:
                pass
        else:
            width, height, duration = self._probe_video(path)

        mime_type = mimetypes.guess_type(path.name)[0] or (
            "image/*" if kind == "image" else "video/*"
        )
        asset_id = new_id()
        thumbnail = self._make_thumbnail(asset_id, path, kind)
        return MediaAsset(
            id=asset_id,
            collection_id=collection_id,
            path=str(path),
            filename=path.name,
            kind=kind,
            mime_type=mime_type,
            extension=path.suffix.lower().lstrip("."),
            size=stat.st_size,
            width=width,
            height=height,
            duration=duration,
            sequence=sequence,
            thumbnail_path=str(thumbnail) if thumbnail else None,
            modified_at=datetime.fromtimestamp(stat.st_mtime, tz=timezone.utc),
        )

    def refresh_asset(self, asset: MediaAsset) -> None:
        path = Path(asset.path)
        if not path.exists():
            return
        stat = path.stat()
        asset.filename = path.name
        asset.size = stat.st_size
        asset.modified_at = datetime.fromtimestamp(stat.st_mtime, tz=timezone.utc)

    def query(
        self,
        session: Session,
        *,
        search: str = "",
        media_type: str = "all",
        sort: str = "newest",
        page: int = 1,
        page_size: int = 60,
    ) -> tuple[list[MediaCollection], int]:
        statement = select(MediaCollection).options(selectinload(MediaCollection.assets))
        count_statement = select(func.count(MediaCollection.id))
        if search.strip():
            pattern = f"%{search.strip()}%"
            condition = or_(
                MediaCollection.title.ilike(pattern),
                MediaCollection.author.ilike(pattern),
            )
            statement = statement.where(condition)
            count_statement = count_statement.where(condition)
        if media_type in {"image", "video"}:
            statement = statement.where(MediaCollection.media_type == media_type)
            count_statement = count_statement.where(MediaCollection.media_type == media_type)
        order = {
            "oldest": MediaCollection.created_at.asc(),
            "name": MediaCollection.title.asc(),
            "size": MediaCollection.item_count.desc(),
        }.get(sort, MediaCollection.created_at.desc())
        total = session.scalar(count_statement) or 0
        items = session.scalars(
            statement.order_by(order).offset((page - 1) * page_size).limit(page_size)
        ).all()
        return list(items), total

    def get_collection(self, session: Session, collection_id: str) -> MediaCollection | None:
        return session.scalar(
            select(MediaCollection)
            .where(MediaCollection.id == collection_id)
            .options(selectinload(MediaCollection.assets))
        )

    def rename(self, session: Session, collection: MediaCollection, title: str, root: Path) -> None:
        safe_title = self.safe_title(title)
        changes: list[tuple[MediaAsset, Path, Path]] = []
        for asset in collection.assets:
            old_path = Path(asset.path).resolve()
            self.require_inside(old_path, root)
            suffix = f"_{asset.sequence:02d}" if len(collection.assets) > 1 else ""
            new_path = old_path.with_name(f"{safe_title}{suffix}{old_path.suffix}")
            if new_path.exists() and new_path != old_path:
                raise FileExistsError(f"文件已存在：{new_path.name}")
            changes.append((asset, old_path, new_path))

        completed: list[tuple[Path, Path]] = []
        try:
            for asset, old_path, new_path in changes:
                if old_path != new_path:
                    old_path.rename(new_path)
                    completed.append((new_path, old_path))
                asset.path = str(new_path)
                asset.filename = new_path.name
            collection.title = safe_title
            collection.updated_at = utcnow()
            session.commit()
        except Exception:
            for new_path, old_path in reversed(completed):
                if new_path.exists():
                    new_path.rename(old_path)
            session.rollback()
            raise

    def act(self, session: Session, action: str, ids: list[str], root: Path) -> int:
        collections = [
            collection
            for collection_id in ids
            if (collection := self.get_collection(session, collection_id)) is not None
        ]
        if not collections:
            raise FileNotFoundError("未找到媒体")
        if action in {"open", "reveal"} and len(collections) != 1:
            raise ValueError("打开和定位操作仅支持单个作品")

        if action == "open":
            path = Path(collections[0].assets[0].path).resolve()
            self.require_inside(path, root)
            os.startfile(path)  # type: ignore[attr-defined]
            return 1
        if action == "reveal":
            path = Path(collections[0].assets[0].path).resolve()
            self.require_inside(path, root)
            subprocess.Popen(f'explorer.exe /select,"{path}"')
            return 1
        if action == "trash":
            assets = [asset for collection in collections for asset in collection.assets]
            for asset in assets:
                path = Path(asset.path).resolve()
                self.require_inside(path, root)
                if path.exists():
                    send2trash(str(path))
            for collection in collections:
                for asset in collection.assets:
                    if asset.thumbnail_path:
                        Path(asset.thumbnail_path).unlink(missing_ok=True)
                session.delete(collection)
            session.commit()
            return len(collections)
        raise ValueError("不支持的文件操作")

    def asset_path(self, session: Session, asset_id: str, root: Path) -> Path | None:
        asset = session.get(MediaAsset, asset_id)
        if asset is None:
            return None
        path = Path(asset.path).resolve()
        self.require_inside(path, root)
        return path if path.exists() else None

    def thumbnail_path(self, session: Session, asset_id: str, root: Path) -> Path | None:
        asset = session.get(MediaAsset, asset_id)
        if asset is None:
            return None
        if asset.thumbnail_path and Path(asset.thumbnail_path).exists():
            return Path(asset.thumbnail_path)
        source = Path(asset.path).resolve()
        self.require_inside(source, root)
        thumbnail = self._make_thumbnail(asset.id, source, asset.kind)
        if thumbnail:
            asset.thumbnail_path = str(thumbnail)
            session.commit()
        return thumbnail

    @staticmethod
    def safe_title(title: str) -> str:
        safe = INVALID_FILENAME_RE.sub("_", title.strip())[:120].strip(" .")
        if not safe:
            raise ValueError("名称不能为空")
        return safe

    @staticmethod
    def _inside(path: Path, root: Path) -> bool:
        try:
            path.resolve().relative_to(root.resolve())
            return True
        except ValueError:
            return False

    def require_inside(self, path: Path, root: Path) -> None:
        if not self._inside(path, root):
            raise PermissionError("文件不在当前下载目录内")

    @staticmethod
    def _collection_type(paths: list[Path]) -> str:
        kinds = {"image" if path.suffix.lower() in SUPPORTED_IMAGES else "video" for path in paths}
        return next(iter(kinds)) if len(kinds) == 1 else "mixed"

    @staticmethod
    def _probe_video(path: Path) -> tuple[int | None, int | None, float | None]:
        ffprobe = shutil.which("ffprobe")
        if not ffprobe:
            return None, None, None
        try:
            result = subprocess.run(
                [
                    ffprobe,
                    "-v",
                    "quiet",
                    "-print_format",
                    "json",
                    "-show_streams",
                    "-show_format",
                    str(path),
                ],
                capture_output=True,
                timeout=20,
                check=True,
            )
            data = json.loads(result.stdout)
            stream = next(
                (item for item in data.get("streams", []) if item.get("codec_type") == "video"),
                {},
            )
            duration_raw = stream.get("duration") or data.get("format", {}).get("duration")
            duration = float(duration_raw) if duration_raw else None
            return stream.get("width"), stream.get("height"), duration
        except (
            OSError,
            subprocess.SubprocessError,
            TypeError,
            UnicodeError,
            ValueError,
            json.JSONDecodeError,
        ):
            return None, None, None

    def _make_thumbnail(self, asset_id: str, path: Path, kind: str) -> Path | None:
        output = self.paths.thumbnail_dir / f"{asset_id}.jpg"
        if output.exists():
            return output
        try:
            if kind == "image":
                with Image.open(path) as image:
                    image.thumbnail((720, 720))
                    if image.mode not in {"RGB", "L"}:
                        background = Image.new("RGB", image.size, "white")
                        if "A" in image.getbands():
                            background.paste(image, mask=image.getchannel("A"))
                        else:
                            background.paste(image)
                        image = background
                    image.convert("RGB").save(output, "JPEG", quality=84, optimize=True)
                return output
            ffmpeg = shutil.which("ffmpeg")
            if ffmpeg:
                subprocess.run(
                    [
                        ffmpeg,
                        "-y",
                        "-ss",
                        "00:00:01",
                        "-i",
                        str(path),
                        "-frames:v",
                        "1",
                        "-vf",
                        "scale=720:-2:force_original_aspect_ratio=decrease",
                        str(output),
                    ],
                    capture_output=True,
                    timeout=30,
                    check=True,
                )
                return output
        except (OSError, subprocess.SubprocessError):
            output.unlink(missing_ok=True)
        return None

    @staticmethod
    def _remove_empty_collections(session: Session) -> None:
        for collection in session.scalars(
            select(MediaCollection).options(selectinload(MediaCollection.assets))
        ).all():
            if not collection.assets:
                session.delete(collection)

    @staticmethod
    def _refresh_collection_counts(session: Session) -> None:
        for collection in session.scalars(
            select(MediaCollection).options(selectinload(MediaCollection.assets))
        ).all():
            collection.item_count = len(collection.assets)
            if collection.assets:
                kinds = {asset.kind for asset in collection.assets}
                collection.media_type = next(iter(kinds)) if len(kinds) == 1 else "mixed"
