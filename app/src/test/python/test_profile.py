"""Step-by-step profile downloads (profile_start / profile_next), offline."""

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
    igbridge._profile_job = None
    loader = igbridge.load_session_from_cookies(COOKIES)
    loader.context.username = "me"
    yield loader
    igbridge._loader = None
    igbridge._profile_job = None


class FakePost:
    def __init__(self, shortcode, day=1):
        self.shortcode = shortcode
        self.date_utc = datetime.datetime(2026, 10, day, 12, 0, 0)
        self._node = {"owner": {"username": "zeynep"}}


class FakeStoryItem:
    def __init__(self, mediaid):
        self.mediaid, self.is_video = mediaid, True
        self.date_utc = datetime.datetime(2026, 10, 11, 19, 39, 0)
        self._node = {"owner": {"username": "zeynep"}}


class FlakyIterator:
    """Like NodeIterator: a failing page fetch leaves the position unchanged."""

    def __init__(self, items, fail_at=None):
        self.items, self.pos, self.fail_at = items, 0, fail_at

    def __iter__(self):
        return self

    def __next__(self):
        if self.fail_at is not None and self.pos == self.fail_at:
            self.fail_at = None
            raise ie.ConnectionException("JSON Query") from ie.TooManyRequestsException("429")
        if self.pos >= len(self.items):
            raise StopIteration
        self.pos += 1
        return self.items[self.pos - 1]


class FakeProfile:
    username, full_name, userid = "zeynep", "Zeynep", 42
    is_private, followed_by_viewer = True, True

    def __init__(self, posts, reels=(), fail_posts_at=None):
        self._posts, self._reels, self._fail = posts, list(reels), fail_posts_at
        self.mediacount = len(posts)

    def get_posts(self):
        return FlakyIterator(self._posts, self._fail)

    def get_reels(self):
        return FlakyIterator(self._reels)


@pytest.fixture
def instagram(monkeypatch):
    downloads = {"posts": [], "stories": [], "fail_post": set()}

    def install(profile, story_items=(), highlights=None):
        monkeypatch.setattr(instaloader.Profile, "from_username", staticmethod(lambda ctx, name: profile))

        class Story:
            def get_items(self):
                return iter(story_items)
        monkeypatch.setattr(instaloader.Instaloader, "get_stories", lambda self, userids: [Story()])

        class HL:
            def __init__(self, uid):
                self.unique_id = uid
        monkeypatch.setattr(instaloader.Instaloader, "get_highlights",
                            lambda self, p: [HL(uid) for uid in (highlights or {})])
        monkeypatch.setattr(igbridge, "highlight_items", lambda loader, uid: list((highlights or {})[uid]))

        def download_post(self, post, target):
            if post.shortcode in downloads["fail_post"]:
                raise ie.BadResponseException("Fetching Post metadata failed.")
            downloads["posts"].append(post.shortcode)
            path = self.dirname_pattern.replace("{{", "{").replace("}}", "}")
            with open(os.path.join(path, post.shortcode + ".jpg"), "wb") as fh:
                fh.write(b"x")
            return True

        def download_storyitem(self, item, target):
            downloads["stories"].append((target, item.mediaid))
            path = self.dirname_pattern.replace("{{", "{").replace("}}", "}")
            with open(os.path.join(path, "%d.mp4" % item.mediaid), "wb") as fh:
                fh.write(b"x")
            return True
        monkeypatch.setattr(instaloader.Instaloader, "download_post", download_post)
        monkeypatch.setattr(instaloader.Instaloader, "download_storyitem", download_storyitem)
        return downloads
    return install


def start(options, known=()):
    return json.loads(igbridge.profile_start("zeynep", json.dumps(options), json.dumps(list(known))))


def step(tmp_path):
    return json.loads(igbridge.profile_next(str(tmp_path), "UTC"))


def run_all(tmp_path, max_steps=50):
    results = []
    for _ in range(max_steps):
        r = step(tmp_path)
        results.append(r)
        if r["ok"] and r["data"]["status"] == "finished":
            return results
    raise AssertionError("did not finish")


def test_profile_info(instagram, session):
    instagram(FakeProfile([FakePost("A")]))
    info = json.loads(igbridge.profile_info("zeynep"))["data"]
    assert info == {"username": "zeynep", "full_name": "Zeynep", "posts": 1, "is_private": True,
                    "followed": True, "userid": "42"}


def test_private_not_followed_is_refused(instagram):
    profile = FakeProfile([FakePost("A")])
    profile.followed_by_viewer = False
    instagram(profile)
    assert start({"posts": True})["error"]["code"] == "PRIVATE"


def test_everything_in_order_with_limit_and_known_items(instagram, tmp_path):
    posts = [FakePost("P%d" % i, day=1 + i) for i in range(5)]
    reels = [FakePost("P1"), FakePost("R1", day=9)]  # P1 is also a grid post
    downloads = instagram(FakeProfile(posts, reels), story_items=[FakeStoryItem(7)],
                          highlights={11: [FakeStoryItem(8), FakeStoryItem(9)], 12: []})
    assert start({"stories": True, "highlights": True, "posts": True, "reels": True, "limit": 3},
                 known=["P0"])["ok"]

    results = [r["data"] for r in run_all(tmp_path)]
    statuses = [(r.get("section"), r.get("id"), r["status"]) for r in results]
    assert statuses == [
        ("stories", "7", "downloaded"),
        ("highlights", "8", "downloaded"),
        ("highlights", "9", "downloaded"),
        ("posts", "P0", "skipped"),       # in history already
        ("posts", "P1", "downloaded"),
        ("posts", "P2", "downloaded"),    # limit 3 -> P3, P4 not taken
        ("reels", "P1", "skipped"),       # same item as a grid post
        ("reels", "R1", "downloaded"),
        (None, None, "finished"),
    ]
    assert downloads["posts"] == ["P1", "P2", "R1"]
    assert results[-1]["downloaded"] == 6 and results[-1]["skipped"] == 2
    assert results[4]["position"] == 2 and results[4]["expected"] == 3
    # files land in <target>/<owner>/ with owner_date names
    names = sorted(os.listdir(tmp_path / "zeynep"))
    assert "zeynep_2026-10-02_12-00-00.jpg" in names and len(names) == 6


def test_rate_limit_while_listing_retries_the_same_spot(instagram, tmp_path):
    posts = [FakePost("P%d" % i, day=1 + i) for i in range(3)]
    downloads = instagram(FakeProfile(posts, fail_posts_at=1))
    start({"posts": True})
    assert step(tmp_path)["data"]["id"] == "P0"
    limited = step(tmp_path)
    assert limited["ok"] is False and limited["error"]["code"] == "RATE_LIMITED"
    rest = [r["data"] for r in run_all(tmp_path)]
    assert [r.get("id") for r in rest] == ["P1", "P2", None]
    assert downloads["posts"] == ["P0", "P1", "P2"]  # nothing lost, nothing twice


def test_rate_limit_while_downloading_keeps_item_pending(instagram, tmp_path, monkeypatch):
    downloads = instagram(FakeProfile([FakePost("A"), FakePost("B", day=2)]))
    start({"posts": True})
    real = instaloader.Instaloader.download_post
    calls = {"n": 0}

    def limited_once(self, post, target):
        calls["n"] += 1
        if calls["n"] == 1:
            raise igbridge.RateLimitedError("429")
        return real(self, post, target)
    monkeypatch.setattr(instaloader.Instaloader, "download_post", limited_once)
    assert step(tmp_path)["error"]["code"] == "RATE_LIMITED"
    assert step(tmp_path)["data"]["id"] == "A"  # retried, not skipped
    assert downloads["posts"] == ["A"]


def test_broken_item_is_skipped_not_fatal(instagram, tmp_path):
    downloads = instagram(FakeProfile([FakePost("A"), FakePost("B", day=2)]))
    downloads["fail_post"].add("A")
    start({"posts": True})
    results = [r["data"] for r in run_all(tmp_path)]
    assert [(r.get("id"), r["status"]) for r in results] == [("A", "failed"), ("B", "downloaded"), (None, "finished")]
    assert "BadResponseException" in results[0]["detail"]


def test_session_expiry_is_fatal(instagram, tmp_path, monkeypatch):
    instagram(FakeProfile([FakePost("A")]))
    start({"posts": True})

    def logged_out(self, post, target):
        raise ie.LoginRequiredException("Redirected to login page")
    monkeypatch.setattr(instaloader.Instaloader, "download_post", logged_out)
    assert step(tmp_path)["error"]["code"] == "SESSION_EXPIRED"


def test_next_without_job_and_cancel(tmp_path):
    assert step(tmp_path)["error"]["code"] == "UNKNOWN"
    assert json.loads(igbridge.profile_cancel())["ok"] is True


def test_iphone_lookups_disabled_during_profile_steps(instagram, tmp_path, monkeypatch, session):
    seen = []
    instagram(FakeProfile([FakePost("A")]))
    real = instaloader.Instaloader.download_post

    def spy(self, post, target):
        seen.append(self.context.iphone_support)
        return real(self, post, target)
    monkeypatch.setattr(instaloader.Instaloader, "download_post", spy)
    start({"posts": True})
    step(tmp_path)
    assert seen == [False] and session.context.iphone_support is True
