from __future__ import annotations

import asyncio
import json
import shutil
import subprocess
from collections.abc import AsyncIterator
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import FileResponse, StreamingResponse
from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from downloader import (
    browser_profile_ready,
    extract_aweme_id,
    is_supported_source,
    normalize_source_url,
    open_login_browser,
)
from parser import extract_urls

from .database import Database
from .library import LibraryService
from .models import AppSettings, DownloadJob, MediaCollection
from .schemas import (
    AssetResponse,
    CollectionResponse,
    CreateJobResult,
    CreateJobsRequest,
    JobResponse,
    LibraryActionRequest,
    LibraryResponse,
    ParseRequest,
    ParseResponse,
    ParsedSource,
    RenameRequest,
    SettingsResponse,
    SettingsUpdate,
)


def build_router(app_state: object) -> APIRouter:
    router = APIRouter(prefix="/api")

    def database() -> Database:
        return app_state.database  # type: ignore[attr-defined]

    def session(db: Database = Depends(database)):
        yield from db.session()

    def settings(db_session: Session) -> AppSettings:
        value = db_session.get(AppSettings, 1)
        if value is None:
            value = AppSettings(id=1, download_dir=str(app_state.paths.default_download_dir.resolve()))  # type: ignore[attr-defined]
            db_session.add(value)
            db_session.commit()
        return value

    @router.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @router.post("/parse", response_model=ParseResponse)
    def parse_sources(payload: ParseRequest, db_session: Session = Depends(session)) -> ParseResponse:
        parsed = extract_urls(payload.text)
        sources: list[ParsedSource] = []
        for raw_url in parsed.urls:
            normalized = normalize_source_url(raw_url)
            if not is_supported_source(normalized):
                continue
            aweme_id = extract_aweme_id(normalized)
            existing = None
            if aweme_id:
                existing = db_session.scalar(
                    select(MediaCollection)
                    .where(MediaCollection.aweme_id == aweme_id)
                    .order_by(MediaCollection.created_at.desc())
                )
            sources.append(
                ParsedSource(
                    url=normalized,
                    aweme_id=aweme_id,
                    duplicate=existing is not None,
                    existing_collection_id=existing.id if existing else None,
                )
            )
        return ParseResponse(sources=sources)

    @router.post("/jobs", response_model=list[CreateJobResult])
    def create_jobs(
        payload: CreateJobsRequest,
        db_session: Session = Depends(session),
    ) -> list[CreateJobResult]:
        current_settings = settings(db_session)
        output_dir = Path(current_settings.download_dir)
        results: list[CreateJobResult] = []
        for raw_url in payload.urls:
            source_url = normalize_source_url(raw_url)
            if not is_supported_source(source_url):
                raise HTTPException(422, "仅支持抖音作品链接")
            aweme_id = extract_aweme_id(source_url)
            existing = app_state.jobs.duplicate(aweme_id)  # type: ignore[attr-defined]
            if existing and not payload.force:
                results.append(
                    CreateJobResult(
                        source_url=source_url,
                        duplicate=True,
                        existing_collection_id=existing.id,
                    )
                )
                continue
            job = app_state.jobs.create(source_url, output_dir, payload.force)  # type: ignore[attr-defined]
            results.append(
                CreateJobResult(source_url=source_url, duplicate=False, job=job_response(job))
            )
        return results

    @router.get("/jobs", response_model=list[JobResponse])
    def list_jobs(
        limit: int = Query(default=100, ge=1, le=500),
        db_session: Session = Depends(session),
    ) -> list[JobResponse]:
        jobs = db_session.scalars(
            select(DownloadJob).order_by(DownloadJob.created_at.desc()).limit(limit)
        ).all()
        return [job_response(job) for job in jobs]

    @router.post("/jobs/{job_id}/cancel", response_model=JobResponse)
    def cancel_job(job_id: str) -> JobResponse:
        job = app_state.jobs.cancel(job_id)  # type: ignore[attr-defined]
        if job is None:
            raise HTTPException(404, "任务不存在")
        return job_response(job)

    @router.post("/jobs/{job_id}/retry", response_model=JobResponse)
    def retry_job(job_id: str) -> JobResponse:
        try:
            job = app_state.jobs.retry(job_id)  # type: ignore[attr-defined]
        except ValueError as exc:
            raise HTTPException(409, str(exc)) from exc
        if job is None:
            raise HTTPException(404, "任务不存在")
        return job_response(job)

    @router.get("/events")
    async def events(request: Request) -> StreamingResponse:
        async def stream() -> AsyncIterator[str]:
            last_payload = ""
            while not await request.is_disconnected():
                with app_state.database.sessions() as event_session:  # type: ignore[attr-defined]
                    recent = event_session.scalars(
                        select(DownloadJob).order_by(DownloadJob.updated_at.desc()).limit(20)
                    ).all()
                    payload = json.dumps(
                        {
                            "jobs": [
                                {
                                    "id": job.id,
                                    "status": job.status,
                                    "progress": job.progress,
                                    "updated_at": job.updated_at.isoformat(),
                                }
                                for job in recent
                            ]
                        },
                        ensure_ascii=False,
                    )
                if payload != last_payload:
                    yield f"event: snapshot\ndata: {payload}\n\n"
                    last_payload = payload
                else:
                    yield ": keep-alive\n\n"
                await asyncio.sleep(1)

        return StreamingResponse(stream(), media_type="text/event-stream")

    @router.get("/library", response_model=LibraryResponse)
    def list_library(
        search: str = "",
        media_type: str = "all",
        sort: str = "newest",
        page: int = Query(default=1, ge=1),
        page_size: int = Query(default=60, ge=1, le=200),
        db_session: Session = Depends(session),
    ) -> LibraryResponse:
        items, total = app_state.library.query(  # type: ignore[attr-defined]
            db_session,
            search=search,
            media_type=media_type,
            sort=sort,
            page=page,
            page_size=page_size,
        )
        return LibraryResponse(items=[collection_response(item) for item in items], total=total)

    @router.get("/library/{collection_id}", response_model=CollectionResponse)
    def get_collection(
        collection_id: str,
        db_session: Session = Depends(session),
    ) -> CollectionResponse:
        item = app_state.library.get_collection(db_session, collection_id)  # type: ignore[attr-defined]
        if item is None:
            raise HTTPException(404, "作品不存在")
        return collection_response(item, include_assets=True)

    @router.patch("/library/{collection_id}", response_model=CollectionResponse)
    def rename_collection(
        collection_id: str,
        payload: RenameRequest,
        db_session: Session = Depends(session),
    ) -> CollectionResponse:
        item = app_state.library.get_collection(db_session, collection_id)  # type: ignore[attr-defined]
        if item is None:
            raise HTTPException(404, "作品不存在")
        try:
            app_state.library.rename(  # type: ignore[attr-defined]
                db_session,
                item,
                payload.title,
                Path(settings(db_session).download_dir),
            )
        except (ValueError, FileExistsError, PermissionError) as exc:
            raise HTTPException(409, str(exc)) from exc
        return collection_response(item, include_assets=True)

    @router.post("/library/actions")
    def library_action(
        payload: LibraryActionRequest,
        db_session: Session = Depends(session),
    ) -> dict[str, int]:
        try:
            count = app_state.library.act(  # type: ignore[attr-defined]
                db_session,
                payload.action,
                payload.ids,
                Path(settings(db_session).download_dir),
            )
        except (ValueError, FileNotFoundError, PermissionError, OSError) as exc:
            raise HTTPException(409, str(exc)) from exc
        return {"affected": count}

    @router.get("/assets/{asset_id}/content")
    def asset_content(asset_id: str, db_session: Session = Depends(session)) -> FileResponse:
        path = app_state.library.asset_path(  # type: ignore[attr-defined]
            db_session,
            asset_id,
            Path(settings(db_session).download_dir),
        )
        if path is None:
            raise HTTPException(404, "文件不存在")
        return FileResponse(path, filename=path.name)

    @router.get("/assets/{asset_id}/thumbnail")
    def asset_thumbnail(asset_id: str, db_session: Session = Depends(session)) -> FileResponse:
        path = app_state.library.thumbnail_path(  # type: ignore[attr-defined]
            db_session,
            asset_id,
            Path(settings(db_session).download_dir),
        )
        if path is None:
            raise HTTPException(404, "缩略图不存在")
        return FileResponse(path, media_type="image/jpeg")

    @router.get("/settings", response_model=SettingsResponse)
    def get_settings(db_session: Session = Depends(session)) -> SettingsResponse:
        current = settings(db_session)
        return settings_response(current)

    @router.patch("/settings", response_model=SettingsResponse)
    def update_settings(
        payload: SettingsUpdate,
        db_session: Session = Depends(session),
    ) -> SettingsResponse:
        path = Path(payload.download_dir).expanduser().resolve()
        try:
            path.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            raise HTTPException(409, f"目录不可用：{exc}") from exc
        current = settings(db_session)
        current.download_dir = str(path)
        db_session.commit()
        app_state.restart_watcher(path)  # type: ignore[attr-defined]
        app_state.rescan()  # type: ignore[attr-defined]
        return settings_response(current)

    @router.post("/system/select-directory", response_model=SettingsResponse)
    def select_directory(db_session: Session = Depends(session)) -> SettingsResponse:
        current = settings(db_session)
        selected = _select_directory(Path(current.download_dir))
        if selected:
            current.download_dir = str(selected.resolve())
            db_session.commit()
            app_state.restart_watcher(selected)  # type: ignore[attr-defined]
            app_state.rescan()  # type: ignore[attr-defined]
        return settings_response(current)

    @router.post("/auth/douyin/open")
    def open_auth_browser() -> dict[str, bool]:
        try:
            open_login_browser()
        except RuntimeError as exc:
            raise HTTPException(409, str(exc)) from exc
        return {"opened": True}

    @router.post("/library/rescan")
    def rescan() -> dict[str, bool]:
        app_state.rescan()  # type: ignore[attr-defined]
        return {"started": True}

    def settings_response(current: AppSettings) -> SettingsResponse:
        return SettingsResponse(
            download_dir=current.download_dir,
            browser_profile_ready=browser_profile_ready(),
            ffmpeg_ready=shutil.which("ffmpeg") is not None,
        )

    return router


def job_response(job: DownloadJob) -> JobResponse:
    return JobResponse(
        id=job.id,
        source_url=job.source_url,
        aweme_id=job.aweme_id,
        title=job.title,
        status=job.status,
        progress=job.progress,
        downloaded_bytes=job.downloaded_bytes,
        total_bytes=job.total_bytes,
        current_item=job.current_item,
        total_items=job.total_items,
        error=job.error,
        collection_id=job.collection_id,
        created_at=job.created_at,
        updated_at=job.updated_at,
        completed_at=job.completed_at,
    )


def collection_response(
    item: MediaCollection,
    *,
    include_assets: bool = False,
) -> CollectionResponse:
    assets = sorted(item.assets, key=lambda asset: asset.sequence)
    return CollectionResponse(
        id=item.id,
        aweme_id=item.aweme_id,
        source_url=item.source_url,
        title=item.title,
        author=item.author,
        media_type=item.media_type,
        item_count=item.item_count,
        imported=item.imported,
        source_created_at=item.source_created_at,
        created_at=item.created_at,
        updated_at=item.updated_at,
        cover_asset_id=assets[0].id if assets else None,
        total_size=sum(asset.size for asset in assets),
        assets=(
            [
                AssetResponse(
                    id=asset.id,
                    filename=asset.filename,
                    kind=asset.kind,
                    mime_type=asset.mime_type,
                    extension=asset.extension,
                    size=asset.size,
                    width=asset.width,
                    height=asset.height,
                    duration=asset.duration,
                    sequence=asset.sequence,
                )
                for asset in assets
            ]
            if include_assets
            else None
        ),
    )


def _select_directory(initial: Path) -> Path | None:
    script = (
        "Add-Type -AssemblyName System.Windows.Forms; "
        "$dialog = New-Object System.Windows.Forms.FolderBrowserDialog; "
        f"$dialog.SelectedPath = '{str(initial).replace("'", "''")}'; "
        "if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) "
        "{ [Console]::OutputEncoding=[Text.Encoding]::UTF8; Write-Output $dialog.SelectedPath }"
    )
    result = subprocess.run(
        ["powershell.exe", "-NoProfile", "-STA", "-Command", script],
        capture_output=True,
        text=True,
        timeout=120,
        encoding="utf-8",
    )
    selected = result.stdout.strip()
    return Path(selected) if selected else None
