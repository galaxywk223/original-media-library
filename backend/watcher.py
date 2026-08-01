from __future__ import annotations

import threading
from pathlib import Path
from typing import Callable

from watchdog.events import FileSystemEvent, FileSystemEventHandler
from watchdog.observers import Observer


class _Handler(FileSystemEventHandler):
    def __init__(self, callback: Callable[[], None]) -> None:
        self.callback = callback
        self.timer: threading.Timer | None = None
        self.lock = threading.Lock()

    def on_any_event(self, event: FileSystemEvent) -> None:
        if event.is_directory:
            return
        with self.lock:
            if self.timer:
                self.timer.cancel()
            self.timer = threading.Timer(1.2, self.callback)
            self.timer.daemon = True
            self.timer.start()


class DirectoryWatcher:
    def __init__(self, callback: Callable[[], None]) -> None:
        self.callback = callback
        self.observer: Observer | None = None
        self.path: Path | None = None

    def start(self, path: Path) -> None:
        self.stop()
        path.mkdir(parents=True, exist_ok=True)
        self.path = path.resolve()
        observer = Observer()
        observer.schedule(_Handler(self.callback), str(self.path), recursive=True)
        observer.daemon = True
        observer.start()
        self.observer = observer

    def stop(self) -> None:
        if self.observer:
            self.observer.stop()
            self.observer.join(timeout=2)
            self.observer = None

