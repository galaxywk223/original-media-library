from pathlib import Path

from fastapi.testclient import TestClient

from backend.main import create_app


def test_health_and_default_settings(tmp_path: Path) -> None:
    with TestClient(create_app(tmp_path)) as client:
        assert client.get("/api/health").json() == {"status": "ok"}
        settings = client.get("/api/settings")
        assert settings.status_code == 200
        assert settings.json()["download_dir"] == str((tmp_path / "downloads").resolve())


def test_parse_direct_douyin_url(tmp_path: Path) -> None:
    with TestClient(create_app(tmp_path)) as client:
        response = client.post(
            "/api/parse",
            json={"text": "https://www.douyin.com/video/123456789"},
        )
        assert response.status_code == 200
        assert response.json()["sources"][0]["aweme_id"] == "123456789"


def test_parse_ignores_non_douyin_url(tmp_path: Path) -> None:
    with TestClient(create_app(tmp_path)) as client:
        response = client.post("/api/parse", json={"text": "https://example.com/video/123"})
        assert response.status_code == 200
        assert response.json() == {"sources": []}
