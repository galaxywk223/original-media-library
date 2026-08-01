from __future__ import annotations

import os
import re
import subprocess
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlparse

APP_BROWSER_DIR = Path(__file__).resolve().parent / ".browser-profile"
DOUYIN_HOME_URL = "https://www.douyin.com/"
DOUYIN_VIDEO_URL = "https://www.douyin.com/video/{aweme_id}"
AWEME_ID_RE = re.compile(r"/(?:note|video|share/video)/(?P<id>\d+)")

ProgressCallback = Callable[[int, int, int, int], None]
CancelCallback = Callable[[], bool]


class DownloadCancelled(RuntimeError):
    pass


@dataclass(frozen=True)
class DownloadResult:
    source_url: str
    filepaths: list[Path]
    title: str | None
    author: str | None
    aweme_id: str | None
    source_created_at: datetime | None
    ext: str | None

    @property
    def filepath(self) -> Path:
        return self.filepaths[0]


@dataclass(frozen=True)
class MediaCandidate:
    url: str
    kind: str
    index: int


def extract_aweme_id(url: str) -> str | None:
    if not is_supported_source(url):
        return None
    match = AWEME_ID_RE.search(urlparse(url).path)
    return match.group("id") if match else None


def is_supported_source(url: str) -> bool:
    host = urlparse(url).netloc.lower().split(":", 1)[0]
    return host == "douyin.com" or host.endswith(".douyin.com") or host.endswith(".iesdouyin.com")


def normalize_source_url(url: str) -> str:
    url = _resolve_redirect_url(url.strip())
    aweme_id = extract_aweme_id(url)
    if aweme_id:
        return DOUYIN_VIDEO_URL.format(aweme_id=aweme_id)
    return url


def download_original_media(
    url: str,
    output_dir: Path,
    *,
    progress: ProgressCallback | None = None,
    cancelled: CancelCallback | None = None,
) -> DownloadResult:
    url = normalize_source_url(url)
    output_dir.mkdir(parents=True, exist_ok=True)

    if not browser_profile_ready():
        raise RuntimeError("需要先打开登录浏览器并完成抖音登录")

    return _download_with_browser_capture(
        url,
        output_dir,
        progress=progress,
        cancelled=cancelled,
    )


def open_login_browser() -> None:
    browser = find_browser_executable()
    if browser is None:
        raise RuntimeError("未找到 Chrome 或 Edge")

    APP_BROWSER_DIR.mkdir(parents=True, exist_ok=True)
    subprocess.Popen(
        [
            str(browser),
            f"--user-data-dir={APP_BROWSER_DIR}",
            "--new-window",
            DOUYIN_HOME_URL,
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def browser_profile_ready() -> bool:
    return (APP_BROWSER_DIR / "Default").exists()


def find_browser_executable() -> Path | None:
    candidates = [
        Path(os.environ.get("ProgramFiles", "")) / "Google" / "Chrome" / "Application" / "chrome.exe",
        Path(os.environ.get("ProgramFiles(x86)", "")) / "Microsoft" / "Edge" / "Application" / "msedge.exe",
        Path(os.environ.get("ProgramFiles", "")) / "Microsoft" / "Edge" / "Application" / "msedge.exe",
    ]
    return next((candidate for candidate in candidates if candidate.exists()), None)


def _resolve_redirect_url(url: str) -> str:
    parsed = urlparse(url)
    host = parsed.netloc.lower()
    if not (host.endswith("douyin.com") or host.endswith("iesdouyin.com")):
        return url
    if AWEME_ID_RE.search(parsed.path):
        return url

    request = urllib.request.Request(url, headers={"User-Agent": _user_agent()})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.geturl()
    except (urllib.error.URLError, TimeoutError, ValueError):
        return url


def _download_with_browser_capture(
    url: str,
    output_dir: Path,
    *,
    progress: ProgressCallback | None,
    cancelled: CancelCallback | None,
) -> DownloadResult:
    from playwright.sync_api import sync_playwright

    browser = find_browser_executable()
    if browser is None:
        raise RuntimeError("未找到 Chrome 或 Edge")
    if cancelled and cancelled():
        raise DownloadCancelled("下载已取消")

    aweme_detail: dict[str, Any] | None = None
    try:
        with sync_playwright() as playwright:
            context = playwright.chromium.launch_persistent_context(
                str(APP_BROWSER_DIR.resolve()),
                executable_path=str(browser),
                headless=True,
                viewport={"width": 1280, "height": 900},
            )
            page = context.new_page()

            def handle_response(response: Any) -> None:
                nonlocal aweme_detail
                if "/aweme/v1/web/aweme/detail/" not in response.url:
                    return
                try:
                    data = response.json()
                except Exception:
                    return
                detail = data.get("aweme_detail")
                if isinstance(detail, dict):
                    aweme_detail = detail

            page.on("response", handle_response)
            page.goto(url, wait_until="domcontentloaded", timeout=60000)
            for _ in range(20):
                if aweme_detail or (cancelled and cancelled()):
                    break
                page.wait_for_timeout(500)
            context.close()
    except Exception as exc:
        message = str(exc).lower()
        if "processsingleton" in message or "profile" in message or "target page" in message:
            raise RuntimeError("登录浏览器仍在运行，请关闭后重试") from exc
        raise

    if cancelled and cancelled():
        raise DownloadCancelled("下载已取消")
    if not aweme_detail:
        raise RuntimeError("页面未返回媒体详情，登录状态可能已失效")

    candidates = _extract_media_candidates(aweme_detail)
    if not candidates:
        raise RuntimeError("页面详情中没有找到原始媒体")

    title = _safe_name(aweme_detail.get("desc") or aweme_detail.get("aweme_id") or "douyin")
    saved: list[Path] = []
    for candidate in candidates:
        saved.append(
            _download_url(
                candidate.url,
                output_dir,
                title,
                candidate.index,
                candidate.kind,
                total_items=len(candidates),
                progress=progress,
                cancelled=cancelled,
            )
        )

    author_data = aweme_detail.get("author")
    author = author_data.get("nickname") if isinstance(author_data, dict) else None
    created = aweme_detail.get("create_time")
    source_created_at = (
        datetime.fromtimestamp(created, tz=timezone.utc) if isinstance(created, int) else None
    )
    return DownloadResult(
        source_url=url,
        filepaths=saved,
        title=aweme_detail.get("desc"),
        author=author,
        aweme_id=str(aweme_detail.get("aweme_id") or extract_aweme_id(url) or "") or None,
        source_created_at=source_created_at,
        ext=saved[0].suffix.lstrip("."),
    )


def _extract_media_candidates(aweme_detail: dict[str, Any]) -> list[MediaCandidate]:
    candidates: list[MediaCandidate] = []
    images = aweme_detail.get("images")
    if isinstance(images, list) and images:
        for index, image in enumerate(images, start=1):
            if not isinstance(image, dict):
                continue
            for key in ("download_url_list", "url_list"):
                urls = image.get(key)
                if isinstance(urls, list) and urls:
                    candidates.append(MediaCandidate(str(urls[0]), "image", index))
                    break
        return candidates

    video = aweme_detail.get("video")
    if isinstance(video, dict):
        for key in ("play_addr", "download_addr"):
            value = video.get(key)
            if not isinstance(value, dict):
                continue
            urls = value.get("url_list") or value.get("download_url_list")
            if isinstance(urls, list) and urls:
                return [MediaCandidate(str(urls[0]), "video", 1)]
    return candidates


def _download_url(
    url: str,
    output_dir: Path,
    title: str,
    index: int,
    kind: str,
    *,
    total_items: int,
    progress: ProgressCallback | None,
    cancelled: CancelCallback | None,
) -> Path:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": _user_agent(), "Referer": DOUYIN_HOME_URL},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        content_type = response.headers.get("content-type", "")
        total_bytes = int(response.headers.get("content-length") or 0)
        ext = _extension_from_url_or_type(url, content_type, kind)
        filepath = _unique_path(output_dir / f"{title}_{index:02d}{ext}")
        partial = filepath.with_suffix(filepath.suffix + ".part")
        downloaded = 0
        try:
            with partial.open("wb") as file:
                while True:
                    if cancelled and cancelled():
                        raise DownloadCancelled("下载已取消")
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    file.write(chunk)
                    downloaded += len(chunk)
                    if progress:
                        progress(index, total_items, downloaded, total_bytes)
            partial.replace(filepath)
        except Exception:
            partial.unlink(missing_ok=True)
            raise
        return filepath


def _unique_path(path: Path) -> Path:
    if not path.exists() and not path.with_suffix(path.suffix + ".part").exists():
        return path
    for number in range(2, 10_000):
        candidate = path.with_name(f"{path.stem} ({number}){path.suffix}")
        if not candidate.exists() and not candidate.with_suffix(candidate.suffix + ".part").exists():
            return candidate
    raise RuntimeError("无法生成不冲突的文件名")


def _extension_from_url_or_type(url: str, content_type: str, kind: str) -> str:
    path = urlparse(url).path.lower()
    for ext in (".mp4", ".webm", ".mov", ".jpg", ".jpeg", ".png", ".webp"):
        if path.endswith(ext):
            return ext
    if "png" in content_type:
        return ".png"
    if "jpeg" in content_type or "jpg" in content_type:
        return ".jpg"
    if "webp" in content_type:
        return ".webp"
    if "video" in content_type or kind == "video":
        return ".mp4"
    return ".bin"


def _safe_name(value: Any) -> str:
    text = str(value).strip()[:80] or "douyin"
    return re.sub(r'[<>:"/\\|?*\r\n]+', "_", text).strip(" .") or "douyin"


def _user_agent() -> str:
    return (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/126.0.0.0 Safari/537.36"
    )
