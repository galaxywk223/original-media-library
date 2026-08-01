from parser import extract_urls


def test_extract_urls_deduplicates_and_cleans_punctuation() -> None:
    result = extract_urls(
        "作品一 https://v.douyin.com/abc/，作品二 https://www.douyin.com/video/12345。"
        "重复 https://v.douyin.com/abc/"
    )

    assert result.urls == [
        "https://v.douyin.com/abc/",
        "https://www.douyin.com/video/12345",
    ]

