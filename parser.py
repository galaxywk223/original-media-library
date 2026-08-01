from __future__ import annotations

import re
from dataclasses import dataclass

URL_RE = re.compile(r"https?://[^\s\]\[【】<>\"'，。！？；；、]+", re.IGNORECASE)


@dataclass(frozen=True)
class ParseResult:
    urls: list[str]
    normalized_text: str


def _clean_url(url: str) -> str:
    cleaned = url.strip().strip("【】<>\'\"（）()[]{}，。！？；；、,.;")
    while cleaned and cleaned[-1] in ")]}】>，。！？；；、,.;":
        cleaned = cleaned[:-1]
    return cleaned


def extract_urls(text: str) -> ParseResult:
    found: list[str] = []
    seen: set[str] = set()

    for match in URL_RE.finditer(text):
        url = _clean_url(match.group(0))
        if not url or url in seen:
            continue
        found.append(url)
        seen.add(url)

    normalized_text = "\n".join(found)
    return ParseResult(urls=found, normalized_text=normalized_text)
