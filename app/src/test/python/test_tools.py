"""Offline tests for the desktop helper scripts in tools/."""

import datetime
import os
import sys

import pytest
import instaloader

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", "..", "tools")))

import check_cookies  # noqa: E402
import check_story  # noqa: E402
import igbridge  # noqa: E402


@pytest.mark.parametrize("text, expected", [
    ("https://www.instagram.com/stories/some.user_1/3456789012345678901/?igsh=abc",
     ("story", "some.user_1", 3456789012345678901)),
    ("https://instagram.com/stories/highlights/17912345678901234/", ("highlight", None, 17912345678901234)),
    ("@some.user", ("user", "some.user", None)),
    ("some_user", ("user", "some_user", None)),
])
def test_parse_target(text, expected):
    assert check_story.parse_target(text) == expected


def test_parse_target_rejects_junk():
    with pytest.raises(ValueError):
        check_story.parse_target("https://www.instagram.com/p/ABC/")


def test_sessionid_only_cookies_match_app_rules():
    cookies = check_cookies.from_sessionid(' "4567890%3Atok%3A12" ')
    assert cookies["sessionid"] == "4567890%3Atok%3A12"
    assert cookies["ds_user_id"] == "4567890"
    assert len(cookies["csrftoken"]) == 32
    assert igbridge.parse_cookies(cookies) == cookies


class FakeItem:
    def __init__(self, mediaid, is_video):
        self.mediaid, self.is_video = mediaid, is_video
        self.date_utc = datetime.datetime(2026, 10, 12, 20, 0)
        self.owner_username = "someone"


class FakeStory:
    def __init__(self, items):
        self._items = items

    def get_items(self):
        return iter(self._items)


class FakeProfile:
    userid, is_private, followed_by_viewer = 42, False, True


def test_story_flow_offline(monkeypatch, tmp_path, capsys):
    items = [FakeItem(111, False), FakeItem(222, True)]
    monkeypatch.setattr(igbridge, "check_login", lambda loader: "me")
    monkeypatch.setattr(instaloader.Profile, "from_username", staticmethod(lambda ctx, name: FakeProfile()))
    monkeypatch.setattr(instaloader.Instaloader, "get_stories", lambda self, userids: [FakeStory(items)])

    def fake_download(self, item, target):
        directory = self.dirname_pattern.replace("{{", "{").replace("}}", "}")
        name = "%d.%s" % (item.mediaid, "mp4" if item.is_video else "jpg")
        with open(os.path.join(directory, name), "wb") as fh:
            fh.write(b"x")
        return True
    monkeypatch.setattr(instaloader.Instaloader, "download_storyitem", fake_download)
    monkeypatch.setattr(sys, "argv", [
        "check_story.py", "--sessionid", "4567890%3Atok", "--out", str(tmp_path),
        "https://www.instagram.com/stories/someone/222/"])

    assert check_story.main() == 0
    assert sorted(os.listdir(tmp_path / "someone")) == ["someone_2026-10-12_20-00-00.mp4"]
    assert "stories downloaded fine" in capsys.readouterr().out


def test_story_flow_reports_failing_step(monkeypatch, tmp_path, capsys):
    monkeypatch.setattr(igbridge, "check_login", lambda loader: "me")

    def boom(ctx, name):
        raise instaloader.exceptions.TooManyRequestsException("429")
    monkeypatch.setattr(instaloader.Profile, "from_username", staticmethod(boom))
    monkeypatch.setattr(sys, "argv", ["check_story.py", "--sessionid", "1%3At", "--out", str(tmp_path), "someone"])

    assert check_story.main() == 2
    out = capsys.readouterr().out
    assert "[RATE_LIMITED]" in out and "look up @someone" in out


# --- check_profile -------------------------------------------------------------

import check_profile  # noqa: E402


@pytest.mark.parametrize("text", ["zeynep.k_1", "@zeynep.k_1", "https://www.instagram.com/zeynep.k_1/",
                                  "https://instagram.com/zeynep.k_1?igsh=abc",
                                  "https://www.instagram.com/zeynep.k_1/reels/",
                                  "https://www.instagram.com/_u/zeynep.k_1",
                                  "https://İnstagram.com/zeynep.k_1?obrf=MWhxMDhnbTFlbDNiZw%3D%3D"])
def test_parse_username(text):
    assert check_profile.parse_username(text) == "zeynep.k_1"


class FakePost:
    def __init__(self, shortcode):
        self.shortcode = shortcode
        self.date_utc = datetime.datetime(2026, 10, 1, 12, 0, 0)
        self._node = {"owner": {"username": "zeynep"}}


class FakeFullProfile:
    mediacount, is_private, followed_by_viewer, userid, username = 3, False, True, 42, "zeynep"

    def get_posts(self):
        return iter([FakePost("AAA"), FakePost("BBB"), FakePost("CCC")])

    def get_reels(self):
        return iter([FakePost("BBB"), FakePost("RRR")])


def test_profile_flow_offline(monkeypatch, tmp_path, capsys):
    monkeypatch.setattr(igbridge, "check_login", lambda loader: "me")
    monkeypatch.setattr(instaloader.Profile, "from_username", staticmethod(lambda ctx, name: FakeFullProfile()))
    downloaded = []

    def fake_download_post(self, post, target):
        downloaded.append(post.shortcode)
        path = self.dirname_pattern.replace("{{", "{").replace("}}", "}")
        with open(os.path.join(path, post.shortcode + ".jpg"), "wb") as fh:
            fh.write(b"x")
        return True
    monkeypatch.setattr(instaloader.Instaloader, "download_post", fake_download_post)
    monkeypatch.setattr(sys, "argv", ["check_profile.py", "--sessionid", "1%3At", "--out", str(tmp_path),
                                      "--limit", "2", "--reels", "--download", "--tz", "Europe/Istanbul",
                                      "https://www.instagram.com/zeynep/"])
    assert check_profile.main() == 0
    assert downloaded == ["AAA", "BBB", "RRR"]  # newest 2 posts + reels not already downloaded
    out = capsys.readouterr().out
    # one folder per account, owner_date names; same timestamp -> (2), (3)
    assert sorted(os.listdir(tmp_path / "zeynep")) == [
        "zeynep_2026-10-01_15-00-00 (2).jpg", "zeynep_2026-10-01_15-00-00 (3).jpg", "zeynep_2026-10-01_15-00-00.jpg"]
    assert "zeynep_2026-10-01_15-00-00.jpg" in out
    assert "requests to Instagram:" in out and "no throttling seen" in out


def test_profile_rate_limit_is_reported(monkeypatch, tmp_path, capsys):
    monkeypatch.setattr(igbridge, "check_login", lambda loader: "me")

    class LimitedProfile(FakeFullProfile):
        def get_posts(self):
            raise igbridge.RateLimitedError("429 Too Many Requests")
    monkeypatch.setattr(instaloader.Profile, "from_username", staticmethod(lambda ctx, name: LimitedProfile()))
    monkeypatch.setattr(sys, "argv", ["check_profile.py", "--sessionid", "1%3At", "--out", str(tmp_path), "zeynep"])
    assert check_profile.main() == 2
    assert "[RATE_LIMITED]" in capsys.readouterr().out
