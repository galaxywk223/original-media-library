import json
import subprocess
from pathlib import Path

from PIL import Image
from sqlalchemy import select
from sqlalchemy.orm import selectinload

from backend.config import get_paths
from backend.database import Database
from backend.library import LibraryService
from backend.models import MediaCollection


def make_image(path: Path, color: str) -> None:
    Image.new("RGB", (80, 60), color).save(path, "WEBP")


def test_scan_groups_sequential_images_and_is_idempotent(tmp_path: Path) -> None:
    downloads = tmp_path / "downloads"
    downloads.mkdir()
    make_image(downloads / "测试作品_01.webp", "red")
    make_image(downloads / "测试作品_02.webp", "blue")

    paths = get_paths(tmp_path)
    database = Database(paths.database)
    database.create_all()
    library = LibraryService(paths)

    with database.sessions() as session:
        assert library.scan(session, downloads) == 2
    with database.sessions() as session:
        assert library.scan(session, downloads) == 0
        collection = session.scalar(
            select(MediaCollection).options(selectinload(MediaCollection.assets))
        )
        assert collection is not None
        assert collection.title == "测试作品"
        assert collection.item_count == 2
        assert len(collection.assets) == 2


def test_rename_updates_group_files(tmp_path: Path) -> None:
    downloads = tmp_path / "downloads"
    downloads.mkdir()
    make_image(downloads / "旧名称_01.webp", "red")
    make_image(downloads / "旧名称_02.webp", "blue")

    paths = get_paths(tmp_path)
    database = Database(paths.database)
    database.create_all()
    library = LibraryService(paths)
    with database.sessions() as session:
        library.scan(session, downloads)
        collection = session.scalar(
            select(MediaCollection).options(selectinload(MediaCollection.assets))
        )
        assert collection is not None
        library.rename(session, collection, "新名称", downloads)

    assert (downloads / "新名称_01.webp").exists()
    assert (downloads / "新名称_02.webp").exists()


def test_reveal_quotes_complex_path_in_explorer_select_command(
    tmp_path: Path,
    monkeypatch,
) -> None:
    downloads = tmp_path / "downloads"
    downloads.mkdir()
    media = downloads / "仙阙 场景，第一幕 #AI.webp"
    make_image(media, "red")

    paths = get_paths(tmp_path)
    database = Database(paths.database)
    database.create_all()
    library = LibraryService(paths)
    launched: list[str] = []
    monkeypatch.setattr(subprocess, "Popen", lambda args: launched.append(args))

    with database.sessions() as session:
        library.scan(session, downloads)
        collection = session.scalar(select(MediaCollection))
        assert collection is not None
        assert library.act(session, "reveal", [collection.id], downloads) == 1

    assert launched == [f'explorer.exe /select,"{media.resolve()}"']


def test_probe_video_parses_utf8_bytes_without_locale_decoding(
    tmp_path: Path,
    monkeypatch,
) -> None:
    video = tmp_path / "中文标题.mp4"
    video.write_bytes(b"video")
    payload = json.dumps(
        {
            "streams": [
                {
                    "codec_type": "video",
                    "width": 1920,
                    "height": 1080,
                    "duration": "33.576",
                    "tags": {"title": "仙阙"},
                }
            ],
            "format": {"filename": str(video)},
        },
        ensure_ascii=False,
    ).encode("utf-8")

    def fake_run(*args, **kwargs):
        assert "text" not in kwargs
        assert "encoding" not in kwargs
        return subprocess.CompletedProcess(args[0], 0, stdout=payload, stderr=b"")

    monkeypatch.setattr(subprocess, "run", fake_run)

    assert LibraryService._probe_video(video) == (1920, 1080, 33.576)


def test_probe_video_degrades_on_invalid_output(tmp_path: Path, monkeypatch) -> None:
    video = tmp_path / "损坏输出.mp4"
    video.write_bytes(b"video")

    monkeypatch.setattr(
        subprocess,
        "run",
        lambda *args, **kwargs: subprocess.CompletedProcess(
            args[0], 0, stdout=b"not-json-\xff", stderr=b""
        ),
    )

    assert LibraryService._probe_video(video) == (None, None, None)
