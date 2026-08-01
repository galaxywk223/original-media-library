from __future__ import annotations

import socket
import threading
import webbrowser
from contextlib import asynccontextmanager
from pathlib import Path

import uvicorn
from fastapi import FastAPI
from fastapi.responses import FileResponse, HTMLResponse
from sqlalchemy import select

from .api import build_router
from .config import AppPaths, get_paths
from .database import Database
from .jobs import JobManager
from .library import LibraryService
from .models import AppSettings
from .watcher import DirectoryWatcher


class AppState:
    def __init__(self, paths: AppPaths) -> None:
        self.paths = paths
        self.database = Database(paths.database)
        self.library = LibraryService(paths)
        self.jobs = JobManager(self.database, self.library)
        self.watcher = DirectoryWatcher(self.rescan)
        self.scan_lock = threading.Lock()

    def initialize(self) -> None:
        self.paths.data_dir.mkdir(parents=True, exist_ok=True)
        self.paths.thumbnail_dir.mkdir(parents=True, exist_ok=True)
        self.database.create_all()
        with self.database.sessions() as session:
            settings = session.get(AppSettings, 1)
            if settings is None:
                settings = AppSettings(
                    id=1,
                    download_dir=str(self.paths.default_download_dir.resolve()),
                )
                session.add(settings)
                session.commit()
            download_dir = Path(settings.download_dir)
        download_dir.mkdir(parents=True, exist_ok=True)
        self.restart_watcher(download_dir)
        self.rescan()
        self.jobs.start()

    def shutdown(self) -> None:
        self.watcher.stop()
        self.jobs.stop()

    def restart_watcher(self, path: Path) -> None:
        self.watcher.start(path.resolve())

    def rescan(self) -> None:
        if not self.scan_lock.acquire(blocking=False):
            return

        def scan() -> None:
            try:
                with self.database.sessions() as session:
                    settings = session.get(AppSettings, 1)
                    if settings:
                        self.library.scan(session, Path(settings.download_dir))
            finally:
                self.scan_lock.release()

        thread = threading.Thread(target=scan, name="library-scan", daemon=True)
        thread.start()


def create_app(root: Path | None = None) -> FastAPI:
    state = AppState(get_paths(root))

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        state.initialize()
        yield
        state.shutdown()

    app = FastAPI(title="原片库", docs_url="/api/docs", lifespan=lifespan)
    app.state.services = state
    app.include_router(build_router(state))

    @app.get("/{full_path:path}", include_in_schema=False)
    def frontend(full_path: str):
        dist = state.paths.frontend_dist
        requested = (dist / full_path).resolve()
        if dist.exists() and requested.is_relative_to(dist.resolve()) and requested.is_file():
            return FileResponse(requested)
        index = dist / "index.html"
        if index.exists():
            return FileResponse(index)
        return HTMLResponse(
            "<h1>原片库前端尚未构建</h1><p>在 frontend 目录运行 npm install 和 npm run build。</p>",
            status_code=503,
        )

    return app


def _free_port(start: int = 8765) -> int:
    for port in range(start, start + 50):
        with socket.socket() as probe:
            try:
                probe.bind(("127.0.0.1", port))
            except OSError:
                continue
            return port
    raise RuntimeError("未找到可用端口")


def run() -> None:
    port = _free_port()
    url = f"http://127.0.0.1:{port}"
    threading.Timer(1.0, lambda: webbrowser.open(url)).start()
    uvicorn.run(create_app(), host="127.0.0.1", port=port, log_level="info")
