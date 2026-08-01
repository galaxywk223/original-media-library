from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class AppPaths:
    root: Path
    data_dir: Path
    database: Path
    thumbnail_dir: Path
    frontend_dist: Path
    default_download_dir: Path


def get_paths(root: Path | None = None) -> AppPaths:
    app_root = (root or Path(__file__).resolve().parents[1]).resolve()
    data_dir = app_root / ".app-data"
    return AppPaths(
        root=app_root,
        data_dir=data_dir,
        database=data_dir / "library.db",
        thumbnail_dir=data_dir / "thumbnails",
        frontend_dist=app_root / "frontend" / "dist",
        default_download_dir=app_root / "downloads",
    )

