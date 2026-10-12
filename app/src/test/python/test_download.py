"""Download wrapper tests: no network, Instaloader's Post and download are faked."""

import datetime
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
    def __init__(self, shortcode, owner="someone"):
        self.shortcode = shortcode
        self.date_utc = datetime.datetime(2026, 10, 12, 18, 30, 5)  # naive UTC, like Instaloader
        self._node = {"owner": {"username": owner}}


def unescape(pattern):
    return pattern.replace("{{", "{").replace("}}", "}")


def fake_download(written):
    """Replace Instaloader.download_post with one that writes the given file names."""
    def download_post(self, post, target):
        assert target == post.shortcode
        assert self.filename_pattern == "{shortcode}"
        for name in written:
            with open(os.path.join(unescape(self.dirname_pattern), name), "wb") as fh:
                fh.write(b"x")
        return True
    return download_post


class Progress:
    def __init__(self):
        self.calls = []

    def onProgress(self, done, total):  # noqa: N802 - same name as the Kotlin interface
        self.calls.append((done, total))


def test_loader_writes_no_side_files(session):
    assert session.save_metadata is False
    assert session.download_video_thumbnails is False
    assert session.download_geotags is False
    assert session.download_comments is False
    assert session.post_metadata_txt_pattern == ""


def test_safe_owner():
    assert igbridge.safe_owner("zeynep.k_1") == "zeynep.k_1"
    assert igbridge.safe_owner(None) == "instagram"
    assert igbridge.safe_owner("..") == "instagram"
    assert igbridge.safe_owner("a/b") == "a_b"


def test_media_name_uses_owner_and_local_time():
    date = datetime.datetime(2026, 10, 12, 18, 30, 5)
    assert igbridge.media_name("zeynep", date, "_2", ".JPG", "Europe/Istanbul") == \
        "zeynep_2026-10-12_21-30-05_2.jpg"
    assert igbridge.media_name(None, date, "", ".mp4", "No/Such_Zone") == \
        "instagram_2026-10-12_18-30-05.mp4"


def test_download_post_names_and_filters(monkeypatch, tmp_path):
    monkeypatch.setattr(instaloader.Post, "from_shortcode", staticmethod(lambda ctx, sc: FakePost(sc)))
    monkeypatch.setattr(instaloader.Instaloader, "download_post",
                        fake_download(["ABC_1.jpg", "ABC_2.mp4", "ABC.json", "ABC.txt", "ABC_3.webp"]))
    progress = Progress()
    result = json.loads(igbridge.download_post("ABC", str(tmp_path / "dir{with}braces"), "Europe/Istanbul", progress))
    assert result["ok"] is True, result
    data = result["data"]
    assert (data["id"], data["owner"]) == ("ABC", "someone")
    assert [(f["name"], f["kind"], f["mime"]) for f in data["files"]] == [
        ("someone_2026-10-12_21-30-05_1.jpg", "image", "image/jpeg"),
        ("someone_2026-10-12_21-30-05_2.mp4", "video", "video/mp4"),
        ("someone_2026-10-12_21-30-05_3.webp", "image", "image/webp"),
    ]
    # renamed on disk, in one folder per account, nothing else left behind
    target = tmp_path / "dir{with}braces"
    assert os.listdir(target) == ["someone"]
    assert sorted(os.listdir(target / "someone")) == sorted(f["name"] for f in data["files"])
    assert all(f["path"] == str(target / "someone" / f["name"]) and f["owner"] == "someone" for f in data["files"])
    assert progress.calls == [(0, 1), (1, 1)]


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


# --- stories & highlights ----------------------------------------------------

class FakeStoryItem:
    def __init__(self, mediaid, is_video, owner="zeynep"):
        self.mediaid, self.is_video = mediaid, is_video
        self.date_utc = datetime.datetime(2026, 10, 11, 19, 39, 0)
        self._node = {"owner": {"username": owner}} if owner else {}


def fake_storyitem_download(seen_iphone):
    def download_storyitem(self, item, target):
        seen_iphone.append(self.context.iphone_support)
        assert self.filename_pattern == "{mediaid}"
        name = "%d.%s" % (item.mediaid, "mp4" if item.is_video else "jpg")
        with open(os.path.join(unescape(self.dirname_pattern), name), "wb") as fh:
            fh.write(b"x")
        return True
    return download_storyitem


class FakeProfile:
    userid, username = 42, "zeynep"


class FakeStory:
    def __init__(self, items):
        self.items = items

    def get_items(self):
        return iter(self.items)


def test_download_one_story(monkeypatch, tmp_path, session):
    items = [FakeStoryItem(111, False), FakeStoryItem(222, True)]
    seen_iphone = []
    monkeypatch.setattr(instaloader.Profile, "from_username", staticmethod(lambda ctx, name: FakeProfile()))
    monkeypatch.setattr(instaloader.Instaloader, "get_stories", lambda self, userids: [FakeStory(items)])
    monkeypatch.setattr(instaloader.Instaloader, "download_storyitem", fake_storyitem_download(seen_iphone))
    result = json.loads(igbridge.download_story("zeynep", "222", str(tmp_path), "UTC"))
    assert result["ok"] is True, result
    assert [f["name"] for f in result["data"]["files"]] == ["zeynep_2026-10-11_19-39-00.mp4"]
    assert seen_iphone == [False]  # broken iPhone lookups are skipped for stories...
    assert session.context.iphone_support is True  # ...and re-enabled afterwards


def test_download_all_current_stories(monkeypatch, tmp_path):
    items = [FakeStoryItem(111, False), FakeStoryItem(222, True)]
    monkeypatch.setattr(instaloader.Profile, "from_username", staticmethod(lambda ctx, name: FakeProfile()))
    monkeypatch.setattr(instaloader.Instaloader, "get_stories", lambda self, userids: [FakeStory(items)])
    monkeypatch.setattr(instaloader.Instaloader, "download_storyitem", fake_storyitem_download([]))
    progress = Progress()
    result = json.loads(igbridge.download_story("zeynep", None, str(tmp_path), "UTC", progress))
    # same second -> second file gets " (2)"; still one flat folder
    assert [f["name"] for f in result["data"]["files"]] == [
        "zeynep_2026-10-11_19-39-00.jpg", "zeynep_2026-10-11_19-39-00.mp4"]
    assert sorted(os.listdir(tmp_path / "zeynep")) == ["zeynep_2026-10-11_19-39-00.jpg", "zeynep_2026-10-11_19-39-00.mp4"]
    assert progress.calls == [(0, 2), (1, 2), (2, 2)]


def test_expired_story(monkeypatch, tmp_path):
    monkeypatch.setattr(instaloader.Profile, "from_username", staticmethod(lambda ctx, name: FakeProfile()))
    monkeypatch.setattr(instaloader.Instaloader, "get_stories", lambda self, userids: [FakeStory([])])
    assert json.loads(igbridge.download_story("zeynep", "999", str(tmp_path)))["error"]["code"] == "NOT_FOUND"


def test_download_highlight_by_id_only(monkeypatch, tmp_path, session):
    nodes = [{"id": "1"}, {"id": "2"}]

    def fake_fetch(self):
        assert self.unique_id == 17973718328923841
        self._items = nodes
    monkeypatch.setattr(instaloader.Highlight, "_fetch_items", fake_fetch)
    monkeypatch.setattr(instaloader, "StoryItem",
                        lambda ctx, node: FakeStoryItem(int(node["id"]), node["id"] == "2", owner=None))
    monkeypatch.setattr(instaloader.Instaloader, "download_storyitem", fake_storyitem_download([]))
    result = json.loads(igbridge.download_highlight("17973718328923841", str(tmp_path), "UTC"))
    assert result["ok"] is True, result
    assert [f["name"] for f in result["data"]["files"]] == [
        "instagram_2026-10-11_19-39-00.jpg",  # owner unknown in the item node -> fallback name
        "instagram_2026-10-11_19-39-00.mp4",
    ]


def test_same_second_files_do_not_overwrite(monkeypatch, tmp_path):
    def fake_fetch(self):
        self._items = [{"id": "1"}, {"id": "2"}, {"id": "3"}]
    monkeypatch.setattr(instaloader.Highlight, "_fetch_items", fake_fetch)
    monkeypatch.setattr(instaloader, "StoryItem", lambda ctx, node: FakeStoryItem(int(node["id"]), False))
    monkeypatch.setattr(instaloader.Instaloader, "download_storyitem", fake_storyitem_download([]))
    result = json.loads(igbridge.download_highlight("9", str(tmp_path), "UTC"))
    assert [f["name"] for f in result["data"]["files"]] == [
        "zeynep_2026-10-11_19-39-00.jpg", "zeynep_2026-10-11_19-39-00 (2).jpg", "zeynep_2026-10-11_19-39-00 (3).jpg"]
    assert len(os.listdir(tmp_path / "zeynep")) == 3


def test_missing_highlight(monkeypatch, tmp_path):
    def empty(self):
        raise IndexError("list index out of range")
    monkeypatch.setattr(instaloader.Highlight, "_fetch_items", empty)
    assert json.loads(igbridge.download_highlight("1", str(tmp_path)))["error"]["code"] == "NOT_FOUND"


class FakeReel:
    """Mimics Highlight.get_items(): the iPhone reels_media lookup raises KeyError while enabled."""

    def __init__(self, context):
        self.context = context
        self.calls = 0

    def get_items(self):
        self.calls += 1
        if self.context.iphone_support:
            raise KeyError("highlight:17973718328923841")
        yield "item1"
        yield "item2"


def test_story_items_survives_changed_iphone_reply(session):
    reel = FakeReel(session.context)
    assert session.context.iphone_support is True
    assert igbridge.story_items(session, reel) == ["item1", "item2"]
    assert reel.calls == 2
    assert session.context.iphone_support is True  # restored for the downloads


def test_story_items_normal_path(session):
    session.context.iphone_support = False  # FakeReel then succeeds first time
    reel = FakeReel(session.context)
    assert igbridge.story_items(session, reel) == ["item1", "item2"]
    assert reel.calls == 1


class HqItem:
    mediaid = 4005791587892109102

    def __init__(self):
        self._iphone_struct_ = None


def test_prefetch_story_hq_attaches_media_info(session, monkeypatch):
    seen = []

    def fake_iphone_json(path, params):
        seen.append(path)
        return {"items": [{"pk": 1, "video_versions": [{"url": "https://hq"}]}]}
    monkeypatch.setattr(session.context, "get_iphone_json", fake_iphone_json)
    item = HqItem()
    assert igbridge.prefetch_story_hq(session, item) is True
    assert seen == ["api/v1/media/4005791587892109102/info/"]
    assert item._iphone_struct_["video_versions"][0]["url"] == "https://hq"


@pytest.mark.parametrize("reply", [{}, {"items": []}, {"items": [{"pk": 1}]}, None])
def test_prefetch_story_hq_falls_back(session, monkeypatch, reply):
    monkeypatch.setattr(session.context, "get_iphone_json", lambda path, params: reply)
    item = HqItem()
    assert igbridge.prefetch_story_hq(session, item) is False
    assert item._iphone_struct_ is None


def test_prefetch_story_hq_network_error(session, monkeypatch):
    def boom(path, params):
        raise ie.ConnectionException("nope")
    monkeypatch.setattr(session.context, "get_iphone_json", boom)
    assert igbridge.prefetch_story_hq(session, HqItem()) is False
