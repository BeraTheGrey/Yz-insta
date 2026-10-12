#!/usr/bin/env python3
"""Measure whole-profile downloads with Instaloader before building them into the app.

Answers: do a profile's post/reel lists load, do the downloads work, and after how
many requests / how much time does Instagram start throttling (and for how long)?
Uses the app's own session and naming code (app/src/main/python/igbridge.py).

Usage:
    pip install instaloader==<version from gradle/libs.versions.toml>

    # only list the newest 30 posts (cheap: ~1 request per 12 posts)
    python3 tools/check_profile.py --sessionid "..." USERNAME

    # list and download the newest 20 posts
    python3 tools/check_profile.py --sessionid "..." USERNAME --limit 20 --download

    # also reels and highlights
    python3 tools/check_profile.py --sessionid "..." USERNAME --limit 20 --reels --highlights --download

    # don't stop at the first rate limit: wait like the desktop Instaloader does and report the waits
    python3 tools/check_profile.py --sessionid "..." USERNAME --limit 100 --download --patient

A profile link (https://www.instagram.com/USERNAME/) works instead of USERNAME.
Start small. Many requests in a short time can make Instagram ask your account for a
verification; --patient runs can take a long time.
"""

import argparse
import itertools
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "app", "src", "main", "python"))
sys.path.insert(0, HERE)

import instaloader  # noqa: E402

import igbridge  # noqa: E402
from check_cookies import add_cookie_args, read_cookies  # noqa: E402
from check_story import StepFailed, step  # noqa: E402

PROFILE_RE = re.compile(r"instagram\.com/([A-Za-z0-9_.]{1,30})/?(?:[?#].*)?$", re.IGNORECASE)
USERNAME_RE = re.compile(r"^@?([A-Za-z0-9_.]{1,30})$")


def parse_username(text):
    text = text.strip()
    for regex in (PROFILE_RE, USERNAME_RE):
        m = regex.search(text)
        if m:
            return m.group(1)
    raise ValueError("not a profile link or username: %r" % text)


class Meter:
    """Counts Instagram requests and time spent waiting, via the loader's RateController."""

    def __init__(self, loader, patient):
        self.requests = 0
        self.waited = 0.0
        self.started = time.monotonic()
        self.first_limit = None
        rc = loader.context._rate_controller
        meter = self
        original_wait = rc.wait_before_query

        def counting_wait(query_type):
            meter.requests += 1
            return original_wait(query_type)
        rc.wait_before_query = counting_wait

        if not patient:
            # Fail fast like the app, but remember when it happened.
            original_sleep, original_429 = rc.sleep, rc.handle_429

            def noting_sleep(secs):
                if secs > igbridge.DEFAULT_MAX_WAIT_SECONDS:
                    meter.note_limit()
                return original_sleep(secs)

            def noting_429(query_type):
                meter.note_limit()
                return original_429(query_type)
            rc.sleep, rc.handle_429 = noting_sleep, noting_429
        else:
            def patient_sleep(secs):
                meter.note_limit()
                print("   ... Instagram asks to wait %.0f s (after %d requests, %.0f s in)" % (
                    secs, meter.requests, meter.elapsed()), flush=True)
                meter.waited += secs
                time.sleep(secs)

            def patient_429(query_type):
                meter.note_limit()
                # Instaloader's own handling: compute the wait and sleep (through patient_sleep).
                instaloader.RateController.handle_429(rc, query_type)
            rc.sleep = patient_sleep
            rc.handle_429 = patient_429

    def note_limit(self):
        if self.first_limit is None:
            self.first_limit = (self.requests, self.elapsed())

    def elapsed(self):
        return time.monotonic() - self.started

    def summary(self):
        lines = ["requests to Instagram: %d" % self.requests,
                 "elapsed: %.0f s (of which waiting for Instagram: %.0f s)" % (self.elapsed(), self.waited)]
        if self.first_limit:
            lines.append("first throttling after %d requests / %.0f s" % self.first_limit)
        else:
            lines.append("no throttling seen")
        return lines


def take(iterator, limit):
    return list(itertools.islice(iterator, limit)) if limit else list(iterator)


def download_posts(loader, posts, out_dir, tz, debug):
    done = 0
    for post in posts:
        def one(p=post):
            sub = os.path.join(out_dir, p.shortcode)
            return igbridge._download_items(loader, [p], sub, "post", tz, None)
        files = step("download %s (%s)" % (post.shortcode, post.date_utc.strftime("%Y-%m-%d")), one, debug)
        for f in files:
            print("   %s" % f["name"])
        done += 1
    return done


def run(args, meter_box):
    username = parse_username(args.target)
    print("instaloader", igbridge.version())
    loader = step("load cookies", lambda: igbridge.load_session_from_cookies(read_cookies(args)), args.debug)
    meter = Meter(loader, args.patient)
    meter_box.append(meter)
    me = step("verify session", lambda: igbridge.check_login(loader), args.debug)
    print("   logged in as @%s" % me)

    profile = step("look up @%s" % username,
                   lambda: instaloader.Profile.from_username(loader.context, username), args.debug)
    print("   %s posts, private=%s, followed by you=%s" % (
        profile.mediacount, profile.is_private, profile.followed_by_viewer))

    posts = step("list newest %s posts" % (args.limit or "all"), lambda: take(profile.get_posts(), args.limit),
                 args.debug)
    print("   got %d posts" % len(posts))
    reels = []
    if args.reels:
        reels = step("list newest %s reels" % (args.limit or "all"), lambda: take(profile.get_reels(), args.limit),
                     args.debug)
        print("   got %d reels" % len(reels))
    highlights = []
    if args.highlights:
        with igbridge._iphone_disabled(loader):
            highlights = step("list highlights", lambda: list(loader.get_highlights(profile)), args.debug)
        print("   %d highlights" % len(highlights))

    if not args.download:
        print("\nListing only. Add --download to download them.")
        return 0

    out = os.path.abspath(args.out)
    seen = {p.shortcode for p in posts}
    downloaded = download_posts(loader, posts + [r for r in reels if r.shortcode not in seen], out, args.tz, args.debug)
    for h in highlights:
        def one(hl=h):
            with igbridge._iphone_disabled(loader):
                items = igbridge.highlight_items(loader, hl.unique_id)
                return igbridge._download_items(loader, items, os.path.join(out, "highlight_%s" % hl.unique_id),
                                                "highlight", args.tz, None)
        files = step("download highlight %r" % h.title, one, args.debug)
        print("   %d files" % len(files))
        downloaded += 1
    print("\nRESULT: profile download works (%d posts/highlights). Files are in %s" % (downloaded, out))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    add_cookie_args(parser)
    parser.add_argument("target", help="username or profile link")
    parser.add_argument("--limit", type=int, default=30, help="newest N posts/reels (0 = all, default 30)")
    parser.add_argument("--reels", action="store_true", help="also list/download reels")
    parser.add_argument("--highlights", action="store_true", help="also list/download highlights")
    parser.add_argument("--download", action="store_true", help="download, not only list")
    parser.add_argument("--patient", action="store_true",
                        help="wait out rate limits like desktop Instaloader instead of stopping")
    parser.add_argument("--tz", default=None,
                        help="time zone for file names, e.g. Europe/Istanbul (default UTC; on Windows "
                             "needs: pip install tzdata)")
    parser.add_argument("--out", default="profile-test-output", help="output folder (default: %(default)s)")
    parser.add_argument("--debug", action="store_true", help="print full tracebacks")
    args = parser.parse_args()
    meter_box = []
    try:
        code = run(args, meter_box)
    except StepFailed:
        print("\nRESULT: failed (see the step above).")
        code = 2
    except KeyboardInterrupt:
        print("\nRESULT: stopped by you.")
        code = 130
    finally:
        igbridge.clear_session()
    if meter_box:
        print("\n" + "\n".join(meter_box[0].summary()))
    return code


if __name__ == "__main__":
    sys.exit(main())
