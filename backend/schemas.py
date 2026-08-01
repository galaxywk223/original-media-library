from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field


class ParseRequest(BaseModel):
    text: str = Field(min_length=1)


class ParsedSource(BaseModel):
    url: str
    aweme_id: str | None
    duplicate: bool
    existing_collection_id: str | None = None


class ParseResponse(BaseModel):
    sources: list[ParsedSource]


class CreateJobsRequest(BaseModel):
    urls: list[str] = Field(min_length=1)
    force: bool = False


class JobResponse(BaseModel):
    id: str
    source_url: str
    aweme_id: str | None
    title: str | None
    status: str
    progress: float
    downloaded_bytes: int
    total_bytes: int
    current_item: int
    total_items: int
    error: str | None
    collection_id: str | None
    created_at: datetime
    updated_at: datetime
    completed_at: datetime | None


class CreateJobResult(BaseModel):
    source_url: str
    duplicate: bool
    existing_collection_id: str | None = None
    job: JobResponse | None = None


class AssetResponse(BaseModel):
    id: str
    filename: str
    kind: str
    mime_type: str
    extension: str
    size: int
    width: int | None
    height: int | None
    duration: float | None
    sequence: int


class CollectionResponse(BaseModel):
    id: str
    aweme_id: str | None
    source_url: str | None
    title: str
    author: str | None
    media_type: str
    item_count: int
    imported: bool
    source_created_at: datetime | None
    created_at: datetime
    updated_at: datetime
    cover_asset_id: str | None
    total_size: int
    assets: list[AssetResponse] | None = None


class LibraryResponse(BaseModel):
    items: list[CollectionResponse]
    total: int


class RenameRequest(BaseModel):
    title: str = Field(min_length=1, max_length=120)


class LibraryActionRequest(BaseModel):
    action: Literal["open", "reveal", "trash"]
    ids: list[str] = Field(min_length=1)


class SettingsResponse(BaseModel):
    download_dir: str
    browser_profile_ready: bool
    ffmpeg_ready: bool


class SettingsUpdate(BaseModel):
    download_dir: str

