#!/usr/bin/env python3
"""Try downloading Instagram stories / highlights with Instaloader, without the phone.

Purpose: find out whether Instaloader's story endpoints still work with your account
before story support is built into the app. Uses the same session code as the app
(app/src/main/python/igbridge.py) and prints every step, so a failure shows where it
happened.

Usage:
    pip install instaloader==<version from gradle/libs.versions.toml>

    # one story (link from "Share > Copy link" on a story)
    python3 tools/check_story.py --sessionid "..." https://www.instagram.com/stories/USERNAME/3456789012345678901/

    # all current stories of a user
    python3 tools/check_story.py --sessionid "..." USERNAME

    # list a user's highlights, then download one of them
    python3 tools/check_story.py --sessionid "..." USERNAME --highlights
    python3 tools/check_story.py --sessionid "..." https://www.instagram.com/stories/highlights/17912345678901234/ --user USERNAME

Cookies: --sessionid VALUE, --header "csrftoken=...; sessionid=...; ds_user_id=..."
or --cookies cookies.json (see check_cookies.py). They are a live login; don't share
them or the output folder's names with anyone.
"""

import argparse
import os
import re
import sys
import time
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "app", "src", "main", "python"))
sys.path.insert(0, HERE)

import instaloader  # noqa: E402

import igbridge  # noqa: E402
from check_cookies import add_cookie_args, read_cookies  # noqa: E402

STORY_RE = re.compile(
    r"instagram\.com/stories/(?P<user>[A-Za-z0-9_.]+)/(?P<media>\d+)", re.IGNORECASE)
HIGHLIGHT_RE = re.compile(
    r"instagram\.com/stories/highlights/(?P<id>\d+)", re.IGNORECASE)
USERNAME_RE = re.compile(r"^@?(?P<user>[A-Za-z0-9_.]{1,30})$")


def parse_target(text):
    """-> ("story", username, mediaid) | ("highlight", None, highlight_id) | ("user", username, None)."""
    text = text.strip()
    m = HIGHLIGHT_RE.search(text)
    if m:
        return "highlight", None, int(m.group("id"))
    m = STORY_RE.search(text)
    if m and m.group("user").lower() != "highlights":
        return "story", m.group("user"), int(m.group("media"))
    m = USERNAME_RE.match(text)
    if m:
        return "user", m.group("user"), None
    raise ValueError("not a story link, highlight link or username: %r" % text)


class StepFailed(Exception):
    pass


def step(title, fn, debug):
    print("-> %s ..." % title, flush=True)
    started = time.monotonic()
    try:
        result = fn()
    except Exception as exc:  # noqa: BLE001
        print("   FAILED after %.1fs: [%s] %s: %s" % (
            time.monotonic() - started, igbridge.classify(exc), type(exc).__name__, exc))
        if debug:
            traceback.print_exc()
        raise StepFailed() from exc
    print("   ok (%.1fs)" % (time.monotonic() - started))
    return result


def describe(item):
    kind = "video" if item.is_video else "image"
    return "%s  %s  %s UTC" % (item.mediaid, kind, item.date_utc.strftime("%Y-%m-%d %H:%M"))


def download_items(loader, items, out_dir, debug):
    os.makedirs(out_dir, exist_ok=True)
    loader.dirname_pattern = igbridge._escape_pattern(os.path.abspath(out_dir))
    loader.filename_pattern = "{owner_username}_{mediaid}"
    for item in items:
        step("download %s" % describe(item), lambda: loader.download_storyitem(item, target="story"), debug)
    files = igbridge.collect_media(out_dir)
    print("\nFiles in %s:" % out_dir)
    for f in files:
        print("   %s  (%s, %d KB)" % (f["name"], f["mime"], os.path.getsize(f["path"]) // 1024))
    return files


def run(args):
    kind, username, ident = parse_target(args.target)
    username = username or args.user
    if kind == "highlight" and not username:
        raise SystemExit("A highlight link has no username in it; add --user USERNAME.")

    print("instaloader", igbridge.version())
    loader = step("load cookies", lambda: igbridge.load_session_from_cookies(read_cookies(args)), args.debug)
    me = step("verify session (test_login)", lambda: igbridge.check_login(loader), args.debug)
    print("   logged in as @%s" % me)

    profile = step("look up @%s" % username,
                   lambda: instaloader.Profile.from_username(loader.context, username), args.debug)
    print("   user id %s, private=%s, followed by you=%s" % (
        profile.userid, profile.is_private, profile.followed_by_viewer))

    if kind == "highlight" or args.highlights:
        highlights = step("list highlights", lambda: list(loader.get_highlights(profile)), args.debug)
        for h in highlights:
            print("   %s  %r  (%s items)" % (h.unique_id, h.title, h.itemcount))
        if kind != "highlight":
            print("\nPass a highlight link (or https://www.instagram.com/stories/highlights/<id>/) "
                  "with --user %s to download one." % username)
            return 0
        match = [h for h in highlights if int(h.unique_id) == ident]
        if not match:
            print("\nHighlight %s not found among @%s's highlights." % (ident, username))
            return 1
        items = step("list items of %r" % match[0].title, lambda: igbridge.story_items(loader, match[0]), args.debug)
    else:
        stories = step("fetch current stories", lambda: list(loader.get_stories(userids=[profile.userid])),
                       args.debug)
        items = step("list story items", lambda: [item for story in stories
                                                   for item in igbridge.story_items(loader, story)], args.debug)
        print("   %d active story item(s)" % len(items))
        for item in items:
            print("   " + describe(item))
        if kind == "story":
            items = [item for item in items if item.mediaid == ident]
            if not items:
                print("\nStory %s is not among the active stories (expired after 24h, or no access)." % ident)
                print("Trying StoryItem.from_mediaid as a fallback ...")
                items = [step("StoryItem.from_mediaid(%s)" % ident,
                              lambda: instaloader.StoryItem.from_mediaid(loader.context, ident), args.debug)]

    if not items:
        print("\nNothing to download.")
        return 1
    if args.limit:
        items = items[:args.limit]
    files = download_items(loader, items, args.out, args.debug)
    print("\nRESULT: %s" % ("stories downloaded fine" if files else "no media files were written"))
    return 0 if files else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    add_cookie_args(parser)
    parser.add_argument("target", help="story link, highlight link or username")
    parser.add_argument("--user", help="owner username (needed for highlight links)")
    parser.add_argument("--highlights", action="store_true", help="list the user's highlights")
    parser.add_argument("--limit", type=int, default=0, help="download at most N items (0 = all)")
    parser.add_argument("--out", default="story-test-output", help="output folder (default: %(default)s)")
    parser.add_argument("--debug", action="store_true", help="print full tracebacks")
    args = parser.parse_args()
    try:
        return run(args)
    except StepFailed:
        print("\nRESULT: failed (see the step above). Re-run with --debug and send me the output "
              "after removing your username/cookies from it.")
        return 2
    finally:
        igbridge.clear_session()


if __name__ == "__main__":
    sys.exit(main())
