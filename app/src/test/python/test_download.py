"""Download wrapper tests: no network, Instaloader's Post and download are faked."""

import json
import os

import pytest
import instaloader
from instaloader import exceptions as ie

import igbridge

COOKIES = {"sessionid": "s", "csrftoken": "c", "ds_user_id": "1"}


@pytest.fixture(autouse=True)
def session():
    igbridge._loader = None
    igbridge.load_session_from_cookies(COOKIES)
    yield igbridge._loader
    igbridge._loader = None


class FakePost:
    owner_username = "someone"

    def __init__(self, shortcode):
        self.shortcode = shortcode


def fake_download(written):
    """Replace Instaloader.download_post with one that writes the given file names."""
    def download_post(self, post, target):
        directory = self.dirname_pattern.replace("{{", "{").replace("}}", "}")
        assert target == post.shortcode
        assert self.filename_pattern == "{shortcode}"
        for name in written:
            with open(os.path.join(directory, name), "wb") as fh:
                fh.write(b"x")
        return True
    return download_post


def test_loader_writes_no_side_files(session):
    assert session.save_metadata is False
    assert session.download_video_thumbnails is False
    assert session.download_geotags is False
    assert session.download_comments is False
    assert session.post_metadata_txt_pattern == ""


def test_download_returns_only_media(monkeypatch, tmp_path):
    monkeypatch.setattr(instaloader.Post, "from_shortcode", staticmethod(lambda ctx, sc: FakePost(sc)))
    monkeypatch.setattr(instaloader.Instaloader, "download_post",
                        fake_download(["ABC_1.jpg", "ABC_2.mp4", "ABC.json", "ABC.txt", "ABC_3.webp"]))
    target = tmp_path / "dir{with}braces"
    result = json.loads(igbridge.download_post("ABC", str(target)))
    assert result["ok"] is True
    data = result["data"]
    assert data["shortcode"] == "ABC"
    assert data["owner"] == "someone"
    assert [(f["name"], f["kind"], f["mime"]) for f in data["files"]] == [
        ("ABC_1.jpg", "image", "image/jpeg"),
        ("ABC_2.mp4", "video", "video/mp4"),
        ("ABC_3.webp", "image", "image/webp"),
    ]
    assert all(os.path.isfile(f["path"]) for f in data["files"])


def test_download_with_nothing_written(monkeypatch, tmp_path):
    monkeypatch.setattr(instaloader.Post, "from_shortcode", staticmethod(lambda ctx, sc: FakePost(sc)))
    monkeypatch.setattr(instaloader.Instaloader, "download_post", fake_download(["ABC.json"]))
    result = json.loads(igbridge.download_post("ABC", str(tmp_path)))
    assert result["error"]["code"] == "UNKNOWN"


@pytest.mark.parametrize("exc, code", [
    (ie.BadResponseException("Fetching Post metadata failed."), "NOT_FOUND"),
    (ie.LoginRequiredException("x"), "SESSION_EXPIRED"),
    (igbridge.RateLimitedError("x"), "RATE_LIMITED"),
])
def test_download_errors_are_classified(monkeypatch, tmp_path, exc, code):
    def boom(ctx, sc):
        raise exc
    monkeypatch.setattr(instaloader.Post, "from_shortcode", staticmethod(boom))
    assert json.loads(igbridge.download_post("ABC", str(tmp_path)))["error"]["code"] == code


def test_download_requires_session(tmp_path):
    igbridge._loader = None
    assert json.loads(igbridge.download_post("ABC", str(tmp_path)))["error"]["code"] == "NOT_LOGGED_IN"
