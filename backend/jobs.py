from __future__ import annotations

import queue
import threading
from datetime import datetime, timezone
from pathlib import Path

from sqlalchemy import select, update
from sqlalchemy.orm import selectinload

from downloader import DownloadCancelled, download_original_media, extract_aweme_id

from .database import Database
from .library import LibraryService
from .models import DownloadJob, MediaCollection, utcnow


class JobManager:
    def __init__(self, database: Database, library: LibraryService) -> None:
        self.database = database
        self.library = library
        self.pending: queue.Queue[str | None] = queue.Queue()
        self.cancel_events: dict[str, threading.Event] = {}
        self.thread: threading.Thread | None = None
        self.stopped = threading.Event()

    def start(self) -> None:
        if self.thread and self.thread.is_alive():
            return
        with self.database.sessions() as session:
            session.execute(
                update(DownloadJob)
                .where(DownloadJob.status.in_(["resolving", "downloading"]))
                .values(status="interrupted", error="应用上次运行时任务未完成", updated_at=utcnow())
            )
            queued = session.scalars(
                select(DownloadJob).where(DownloadJob.status == "queued").order_by(DownloadJob.created_at)
            ).all()
            session.commit()
        self.stopped.clear()
        self.thread = threading.Thread(target=self._worker, name="download-worker", daemon=True)
        self.thread.start()
        for job in queued:
            self.pending.put(job.id)

    def stop(self) -> None:
        self.stopped.set()
        self.pending.put(None)
        if self.thread:
            self.thread.join(timeout=3)

    def create(self, source_url: str, output_dir: Path, force: bool) -> DownloadJob:
        job = DownloadJob(
            source_url=source_url,
            aweme_id=extract_aweme_id(source_url),
            output_dir=str(output_dir.resolve()),
            force=force,
        )
        with self.database.sessions() as session:
            session.add(job)
            session.commit()
        self.cancel_events[job.id] = threading.Event()
        self.pending.put(job.id)
        return job

    def duplicate(self, aweme_id: str | None) -> MediaCollection | None:
        if not aweme_id:
            return None
        with self.database.sessions() as session:
            return session.scalar(
                select(MediaCollection)
                .where(MediaCollection.aweme_id == aweme_id)
                .order_by(MediaCollection.created_at.desc())
            )

    def cancel(self, job_id: str) -> DownloadJob | None:
        with self.database.sessions() as session:
            job = session.get(DownloadJob, job_id)
            if job is None:
                return None
            if job.status in {"completed", "failed", "cancelled"}:
                return job
            event = self.cancel_events.setdefault(job_id, threading.Event())
            event.set()
            if job.status == "queued":
                job.status = "cancelled"
                job.error = None
                job.completed_at = utcnow()
                session.commit()
            return job

    def retry(self, job_id: str) -> DownloadJob | None:
        with self.database.sessions() as session:
            job = session.get(DownloadJob, job_id)
            if job is None:
                return None
            if job.status not in {"failed", "cancelled", "interrupted"}:
                raise ValueError("当前任务状态不可重试")
            job.status = "queued"
            job.progress = 0
            job.downloaded_bytes = 0
            job.total_bytes = 0
            job.current_item = 0
            job.total_items = 0
            job.error = None
            job.completed_at = None
            session.commit()
        self.cancel_events[job_id] = threading.Event()
        self.pending.put(job_id)
        return job

    def _worker(self) -> None:
        while not self.stopped.is_set():
            job_id = self.pending.get()
            if job_id is None:
                break
            self._run(job_id)

    def _run(self, job_id: str) -> None:
        with self.database.sessions() as session:
            job = session.get(DownloadJob, job_id)
            if job is None or job.status != "queued":
                return
            job.status = "resolving"
            job.updated_at = utcnow()
            session.commit()
            source_url = job.source_url
            output_dir = Path(job.output_dir)

        cancel_event = self.cancel_events.setdefault(job_id, threading.Event())

        def progress(current: int, total: int, downloaded: int, item_total: int) -> None:
            fraction = downloaded / item_total if item_total else 0.0
            overall = min(0.999, ((current - 1) + fraction) / max(total, 1))
            with self.database.sessions() as progress_session:
                progress_job = progress_session.get(DownloadJob, job_id)
                if progress_job is None:
                    return
                progress_job.status = "downloading"
                progress_job.current_item = current
                progress_job.total_items = total
                progress_job.progress = overall
                progress_job.downloaded_bytes = downloaded
                progress_job.total_bytes = item_total
                progress_job.updated_at = utcnow()
                progress_session.commit()

        try:
            result = download_original_media(
                source_url,
                output_dir,
                progress=progress,
                cancelled=cancel_event.is_set,
            )
            if cancel_event.is_set():
                raise DownloadCancelled("下载已取消")
            with self.database.sessions() as session:
                collection = self.library.add_download(
                    session,
                    title=result.title or result.aweme_id or "未命名作品",
                    author=result.author,
                    source_url=result.source_url,
                    aweme_id=result.aweme_id,
                    source_created_at=result.source_created_at,
                    paths=result.filepaths,
                )
                job = session.get(DownloadJob, job_id)
                if job is not None:
                    job.title = result.title
                    job.aweme_id = result.aweme_id
                    job.status = "completed"
                    job.progress = 1.0
                    job.current_item = len(result.filepaths)
                    job.total_items = len(result.filepaths)
                    job.collection_id = collection.id
                    job.error = None
                    job.completed_at = utcnow()
                    session.commit()
        except DownloadCancelled:
            self._finish_error(job_id, "cancelled", None)
        except Exception as exc:  # noqa: BLE001
            self._finish_error(job_id, "failed", self._friendly_error(exc))

    def _finish_error(self, job_id: str, status: str, error: str | None) -> None:
        with self.database.sessions() as session:
            job = session.get(DownloadJob, job_id)
            if job is None:
                return
            job.status = status
            job.error = error
            job.completed_at = datetime.now(timezone.utc)
            job.updated_at = utcnow()
            session.commit()

    @staticmethod
    def _friendly_error(exc: Exception) -> str:
        text = str(exc)
        lowered = text.lower()
        if "cookie" in lowered or "登录" in text:
            return "登录状态不可用，请重新打开登录浏览器"
        if "profile" in lowered or "processsingleton" in lowered:
            return "登录浏览器仍在运行，请关闭后重试"
        if "timeout" in lowered:
            return "请求超时，请稍后重试"
        return text[:300] or "下载失败"
