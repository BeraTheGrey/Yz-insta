#!/usr/bin/env python3
"""Check the cookie hand-over against the real Instagram, without the phone.

Runs exactly the code the app runs (app/src/main/python/igbridge.py).

Usage:
    pip install instaloader==<version from gradle/libs.versions.toml>
    python3 tools/check_cookies.py --cookies cookies.json
    python3 tools/check_cookies.py --sessionid "1234567%3Aabc..."

cookies.json is a JSON object such as
    {"sessionid": "...", "csrftoken": "...", "ds_user_id": "..."}
copied from your browser's developer tools (Application > Cookies >
https://www.instagram.com). Alternatively pass the raw Cookie header with
--header "csrftoken=...; sessionid=...; ds_user_id=...".

The file contains a live login; delete it afterwards.
"""

import argparse
import json
import os
import re
import secrets
import string
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "python"))

import igbridge  # noqa: E402


def from_header(header):
    cookies = {}
    for part in header.split(";"):
        name, sep, value = part.partition("=")
        if sep and name.strip() and value.strip():
            cookies[name.strip()] = value.strip().strip('"')
    return cookies


def from_sessionid(sessionid):
    """Same derivation as the app's "log in with a cookie" fallback (InstagramCookies.fromUserInput)."""
    sessionid = sessionid.strip().strip('"')
    cookies = {"sessionid": sessionid}
    match = re.match(r"^(\d+)(?:%3A|:)", sessionid, re.IGNORECASE)
    if match:
        cookies["ds_user_id"] = match.group(1)
    alphabet = string.ascii_letters + string.digits
    cookies["csrftoken"] = "".join(secrets.choice(alphabet) for _ in range(32))
    return cookies


def add_cookie_args(parser):
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--cookies", metavar="FILE", help="JSON file with cookies")
    group.add_argument("--header", help='raw Cookie header, "a=1; b=2"')
    group.add_argument("--sessionid", help="only the sessionid value (ds_user_id/csrftoken are derived)")


def read_cookies(args):
    if args.header:
        return from_header(args.header)
    if args.sessionid:
        return from_sessionid(args.sessionid)
    with open(args.cookies, encoding="utf-8") as fh:
        return json.load(fh)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    add_cookie_args(parser)
    args = parser.parse_args()
    cookies = read_cookies(args)

    print("instaloader", igbridge.version())
    print("cookies:", ", ".join(sorted(cookies)))
    for step, result in (("load_session", igbridge.load_session(json.dumps(cookies))),
                         ("verify_session", igbridge.verify_session())):
        print("%-15s %s" % (step, result))
        if not json.loads(result)["ok"]:
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
