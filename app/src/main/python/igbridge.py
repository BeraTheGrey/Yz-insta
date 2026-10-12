"""Bridge between the Android app and Instaloader.

Every public function returns a JSON string envelope so the Kotlin side never has
to deal with Python exceptions or Python objects:

    {"ok": true,  "data": ...}
    {"ok": false, "error": {"code": "<ERROR_CODE>", "detail": "<text for logs>"}}

Error codes are a closed set (see ``ErrorCode``) and mirrored by ``ErrorCode`` in
Kotlin. Keep both in sync.
"""

import json
import os
import re
import shutil
import sys
import time
from contextlib import contextmanager
from datetime import timezone
from zoneinfo import ZoneInfo

import requests
import instaloader
from instaloader import exceptions as ie


class ErrorCode:
    INVALID_COOKIES = "INVALID_COOKIES"
    NOT_LOGGED_IN = "NOT_LOGGED_IN"
    SESSION_EXPIRED = "SESSION_EXPIRED"
    CHALLENGE_REQUIRED = "CHALLENGE_REQUIRED"
    PRIVATE = "PRIVATE"
    NOT_FOUND = "NOT_FOUND"
    RATE_LIMITED = "RATE_LIMITED"
    NETWORK = "NETWORK"
    UNKNOWN = "UNKNOWN"


# Cookies Instagram sets after a successful web login. Instaloader itself only
# needs csrftoken (it reads it into X-CSRFToken) but without sessionid and
# ds_user_id the session is anonymous.
REQUIRED_COOKIES = ("sessionid", "csrftoken", "ds_user_id")

# On a phone we would rather tell the user to come back later than block a
# thread for many minutes like the desktop CLI does.
DEFAULT_MAX_WAIT_SECONDS = 30.0


class BridgeError(Exception):
    def __init__(self, code, detail=""):
        super().__init__(detail or code)
        self.code = code
        self.detail = detail or code


class RateLimitedError(Exception):
    """Raised instead of sleeping for a long time when Instagram throttles us."""


class FailFastRateController(instaloader.RateController):
    max_wait_seconds = DEFAULT_MAX_WAIT_SECONDS

    def sleep(self, secs):
        if secs > self.max_wait_seconds:
            raise RateLimitedError("Instagram asks to wait %.0f s" % secs)
        time.sleep(secs)

    def handle_429(self, query_type):
        raise RateLimitedError("429 Too Many Requests (%s)" % query_type)


# --------------------------------------------------------------------------- #
# Error classification
# --------------------------------------------------------------------------- #

def _exception_chain(exc):
    seen = []
    while exc is not None and exc not in seen:
        seen.append(exc)
        exc = exc.__cause__ or exc.__context__
    return seen


def _any(chain, predicate):
    return any(predicate(e) for e in chain)


def _msg(exc):
    return str(exc)


def classify(exc):
    """Map any exception raised while talking to Instagram to an ErrorCode."""
    if isinstance(exc, BridgeError):
        return exc.code
    chain = _exception_chain(exc)

    if _any(chain, lambda e: isinstance(e, (RateLimitedError, ie.TooManyRequestsException))
            or "429 Too Many Requests" in _msg(e)
            or "Please wait a few minutes" in _msg(e)):
        return ErrorCode.RATE_LIMITED
    if _any(chain, lambda e: any(k in _msg(e) for k in
                                 ("checkpoint_required", "challenge_required", "feedback_required"))):
        return ErrorCode.CHALLENGE_REQUIRED
    if _any(chain, lambda e: isinstance(e, ie.LoginRequiredException)
            or "Redirected to login page" in _msg(e)):
        return ErrorCode.SESSION_EXPIRED
    if _any(chain, lambda e: isinstance(e, (ie.PrivateProfileNotFollowedException,
                                            ie.QueryReturnedForbiddenException))):
        return ErrorCode.PRIVATE
    if _any(chain, lambda e: isinstance(e, (ie.QueryReturnedNotFoundException,
                                            ie.ProfileNotExistsException))
            or (isinstance(e, ie.BadResponseException) and "Fetching Post metadata failed" in _msg(e))):
        return ErrorCode.NOT_FOUND
    if _any(chain, lambda e: isinstance(e, (requests.exceptions.ConnectionError,
                                            requests.exceptions.Timeout,
                                            ConnectionError, TimeoutError))):
        return ErrorCode.NETWORK
    return ErrorCode.UNKNOWN


def _ok(data=None):
    return json.dumps({"ok": True, "data": data})


def _fail(exc):
    detail = exc.detail if isinstance(exc, BridgeError) else "%s: %s" % (type(exc).__name__, exc)
    return json.dumps({"ok": False, "error": {"code": classify(exc), "detail": detail}})


def _envelope(fn):
    def wrapper(*args, **kwargs):
        try:
            return _ok(fn(*args, **kwargs))
        except Exception as exc:  # noqa: BLE001 - everything must become an envelope
            return _fail(exc)
    wrapper.__name__ = fn.__name__
    wrapper.__doc__ = fn.__doc__
    return wrapper


# --------------------------------------------------------------------------- #
# Session handling
# --------------------------------------------------------------------------- #

_loader = None


def _new_loader():
    return instaloader.Instaloader(
        quiet=True,
        # Only media files: no json/txt/thumbnails/geotags/comments.
        download_pictures=True,
        download_videos=True,
        download_video_thumbnails=False,
        download_geotags=False,
        download_comments=False,
        save_metadata=False,
        compress_json=False,
        post_metadata_txt_pattern="",
        storyitem_metadata_txt_pattern="",
        max_connection_attempts=2,
        request_timeout=60.0,
        rate_controller=lambda ctx: FailFastRateController(ctx),
    )


def parse_cookies(cookies):
    """Validate a cookie mapping (dict or JSON object string) coming from the app."""
    if isinstance(cookies, str):
        try:
            cookies = json.loads(cookies)
        except ValueError as exc:
            raise BridgeError(ErrorCode.INVALID_COOKIES, "cookie payload is not JSON") from exc
    if not isinstance(cookies, dict):
        raise BridgeError(ErrorCode.INVALID_COOKIES, "cookie payload is not an object")
    clean = {str(k).strip(): str(v).strip() for k, v in cookies.items() if str(k).strip()}
    missing = [name for name in REQUIRED_COOKIES if not clean.get(name)]
    if missing:
        raise BridgeError(ErrorCode.INVALID_COOKIES, "missing cookies: " + ",".join(missing))
    return clean


def load_session_from_cookies(cookies):
    """Create a fresh Instaloader whose requests.Session carries the given cookies.

    Pure local operation, no network. Returns the loader (also kept as the module
    session).
    """
    global _loader
    clean = parse_cookies(cookies)
    loader = _new_loader()
    # Instaloader needs *a* username to consider itself logged in. We do not know
    # the real one until verify_session(); ds_user_id is a stable placeholder.
    loader.load_session(clean["ds_user_id"], clean)
    _loader = loader
    return loader


def _require_loader():
    if _loader is None:
        raise BridgeError(ErrorCode.NOT_LOGGED_IN, "no session loaded")
    return _loader


def check_login(loader):
    """Run Instaloader.test_login() but keep the reason when it fails.

    test_login() swallows ConnectionException and just returns None, which would
    make "no internet" look like "logged out". InstaloaderContext.error() is
    called from inside that except block, so we can grab the active exception
    there and re-raise it afterwards.
    """
    ctx = loader.context
    captured = []
    original_error = ctx.error

    def capturing_error(msg, repeat_at_end=True):
        active = sys.exc_info()[1]
        if active is not None:
            captured.append(active)
        original_error(msg, repeat_at_end=repeat_at_end)

    ctx.error = capturing_error
    try:
        username = loader.test_login()
    finally:
        del ctx.error  # drop the instance attribute, back to the class method

    if username:
        ctx.username = username
        return username
    if captured:
        raise captured[-1]
    raise BridgeError(ErrorCode.SESSION_EXPIRED, "test_login() returned no user")


@_envelope
def load_session(cookies_json):
    """Load cookies (JSON object string) into a new Instaloader session. No network."""
    load_session_from_cookies(cookies_json)
    return None


@_envelope
def verify_session():
    """Check the loaded session against Instagram. Returns {"username": ...}."""
    return {"username": check_login(_require_loader())}


@_envelope
def clear_session():
    global _loader
    if _loader is not None:
        try:
            _loader.close()
        finally:
            _loader = None
    return None


# --------------------------------------------------------------------------- #
# Downloads
# --------------------------------------------------------------------------- #

IMAGE_TYPES = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png",
               ".webp": "image/webp", ".heic": "image/heic"}
VIDEO_TYPES = {".mp4": "video/mp4", ".mov": "video/quicktime", ".webm": "video/webm"}


def _escape_pattern(path):
    # dirname_pattern goes through str.format()
    return path.replace("{", "{{").replace("}", "}}")


def collect_media(directory):
    """Media files Instaloader left in ``directory``; anything else (json, txt, ...) is ignored."""
    media = []
    for root, _dirs, files in os.walk(directory):
        for name in sorted(files):
            ext = os.path.splitext(name)[1].lower()
            if ext in IMAGE_TYPES:
                kind, mime = "image", IMAGE_TYPES[ext]
            elif ext in VIDEO_TYPES:
                kind, mime = "video", VIDEO_TYPES[ext]
            else:
                continue
            media.append({"path": os.path.join(root, name), "name": name, "kind": kind, "mime": mime})
    return media


# --- naming -------------------------------------------------------------------

def _zone(tz_name):
    if tz_name:
        try:
            return ZoneInfo(tz_name)
        except Exception:  # noqa: BLE001 - unknown zone or no tz database: fall back to UTC
            pass
    return timezone.utc


def media_name(owner, date_utc, suffix, ext, tz_name=None):
    """File name ``<owner>_<YYYY-MM-DD_HH-MM-SS><suffix><ext>`` with the post time in local time.

    ``date_utc`` is Instaloader's naive UTC datetime of the post/story; ``suffix`` is
    "_1", "_2", ... for carousel items and empty otherwise.
    """
    stamp = date_utc.replace(tzinfo=timezone.utc).astimezone(_zone(tz_name)).strftime("%Y-%m-%d_%H-%M-%S")
    owner = re.sub(r"[^A-Za-z0-9._-]", "_", owner or "instagram")
    return "%s_%s%s%s" % (owner, stamp, suffix, ext.lower())


def _unique_path(directory, name):
    """``directory/name``, or ``name (2)``, ``name (3)``... if taken (same second)."""
    base, ext = os.path.splitext(name)
    candidate, n = os.path.join(directory, name), 2
    while os.path.exists(candidate):
        candidate = os.path.join(directory, "%s (%d)%s" % (base, n, ext))
        n += 1
    return candidate


def _owner_of(item):
    """Owner username of a Post/StoryItem without extra requests or failures."""
    node = getattr(item, "_node", None) or {}
    owner = node.get("owner") if isinstance(node, dict) else None
    if isinstance(owner, dict) and owner.get("username"):
        return owner["username"]
    try:
        return item.owner_username
    except Exception:  # noqa: BLE001
        return None


@contextmanager
def _iphone_disabled(loader):
    """Story reels: the iPhone lookups are broken by Instagram's changed reply and, as
    tested on a real account, gave no better quality than GraphQL. Skip them."""
    ctx = loader.context
    saved = ctx.iphone_support
    ctx.iphone_support = False
    try:
        yield
    finally:
        ctx.iphone_support = saved


def _report(listener, done, total):
    if listener is not None:
        listener.onProgress(done, total)


def _download_items(loader, items, target_dir, kind, tz_name, listener):
    """Download Posts or StoryItems one by one and leave only renamed media files,
    flat in ``target_dir``. Returns media dicts. ``kind`` is "post", "story" or "highlight".

    Each item first goes into its own scratch directory so its files can be told
    apart; they are then moved up under their final names and the scratch is removed.
    """
    target_dir = os.path.abspath(target_dir)
    scratch_root = os.path.join(target_dir, ".instaloader-tmp")
    loader.filename_pattern = "{shortcode}" if kind == "post" else "{mediaid}"
    files = []
    total = len(items)
    _report(listener, 0, total)
    try:
        for index, item in enumerate(items):
            scratch = os.path.join(scratch_root, str(index))
            os.makedirs(scratch, exist_ok=True)
            loader.dirname_pattern = _escape_pattern(scratch)
            if kind == "post":
                loader.download_post(item, target=item.shortcode)
                stem = item.shortcode
            else:
                loader.download_storyitem(item, target=kind)
                stem = str(item.mediaid)
            owner = _owner_of(item)
            for media in collect_media(scratch):
                base, ext = os.path.splitext(media["name"])
                suffix = base[len(stem):] if base.startswith(stem) else ""  # "_1", "_2" for carousels
                dest = _unique_path(target_dir, media_name(owner, item.date_utc, suffix, ext, tz_name))
                os.replace(media["path"], dest)
                media["path"], media["name"] = dest, os.path.basename(dest)
                files.append(media)
            _report(listener, index + 1, total)
    finally:
        shutil.rmtree(scratch_root, ignore_errors=True)
    if not files:
        raise BridgeError(ErrorCode.UNKNOWN, "download finished but no media files were written")
    return files


# --- public download functions -------------------------------------------------
# All return {"id", "owner", "files": [{"path", "name", "kind", "mime"}]}; files are
# already renamed to <owner>_<date> and lie flat in target_dir.

@_envelope
def download_post(shortcode, target_dir, tz_name=None, listener=None):
    """One post, reel or IGTV video."""
    loader = _require_loader()
    post = instaloader.Post.from_shortcode(loader.context, shortcode)
    files = _download_items(loader, [post], target_dir, "post", tz_name, listener)
    return {"id": shortcode, "owner": _owner_of(post), "files": files}


@_envelope
def download_story(username, media_id, target_dir, tz_name=None, listener=None):
    """One story item (``media_id``) of ``username``, or all current ones if media_id is None."""
    loader = _require_loader()
    with _iphone_disabled(loader):
        profile = instaloader.Profile.from_username(loader.context, username)
        items = [item for story in loader.get_stories(userids=[profile.userid]) for item in story.get_items()]
        if media_id is not None:
            items = [item for item in items if item.mediaid == int(media_id)]
        if not items:
            raise BridgeError(ErrorCode.NOT_FOUND, "story expired or not visible to this account")
        files = _download_items(loader, items, target_dir, "story", tz_name, listener)
    return {"id": str(media_id or profile.userid), "owner": profile.username, "files": files}


def highlight_items(loader, highlight_id):
    """StoryItems of a highlight, from its id alone (share links carry no username)."""
    highlight = instaloader.Highlight(loader.context, {"id": str(highlight_id)})
    try:
        highlight._fetch_items()  # Instaloader's own GraphQL query for the reel's items
    except IndexError as exc:  # empty reels_media: no such highlight or no access
        raise BridgeError(ErrorCode.NOT_FOUND, "highlight not found or not visible to this account") from exc
    return [instaloader.StoryItem(loader.context, node) for node in highlight._items or []]


@_envelope
def download_highlight(highlight_id, target_dir, tz_name=None, listener=None):
    """All items of a highlight."""
    loader = _require_loader()
    with _iphone_disabled(loader):
        items = highlight_items(loader, highlight_id)
        if not items:
            raise BridgeError(ErrorCode.NOT_FOUND, "highlight is empty")
        files = _download_items(loader, items, target_dir, "highlight", tz_name, listener)
    return {"id": str(highlight_id), "owner": _owner_of(items[0]), "files": files}


def story_items(loader, reel):
    """StoryItems of a Story or Highlight, tolerating Instagram's changed iPhone API.

    get_items() also fetches ``api/v1/feed/reels_media/?reel_ids=...`` (only used for
    higher quality URLs) and indexes the reply with ``reels["highlight:<id>"]``. Instagram
    no longer always returns that key, so get_items() dies with a KeyError although the
    GraphQL item list is fine. In that case read the items again without the iPhone
    reply; StoryItem.url / video_url then fall back to the GraphQL resources.
    """
    try:
        return list(reel.get_items())
    except KeyError:
        ctx = loader.context
        saved = ctx.iphone_support
        ctx.iphone_support = False
        try:
            return list(reel.get_items())
        finally:
            ctx.iphone_support = saved


def prefetch_story_hq(loader, item):
    """Try to get high-quality URLs for a StoryItem from the per-media info endpoint.

    StoryItem looks its iPhone data up in ``reels_media?reel_ids=<owner id>``, which
    fails with Instagram's current reply (KeyError on the owner id) and could never
    find older highlight items anyway. ``api/v1/media/<pk>/info/`` is what Post uses.
    Returns True if HQ data was attached; otherwise StoryItem keeps using the GraphQL
    resources as before.
    """
    try:
        data = loader.context.get_iphone_json(path="api/v1/media/%d/info/" % item.mediaid, params={})
        struct = data["items"][0]
    except (ie.InstaloaderException, KeyError, IndexError, TypeError):
        return False
    if not ("video_versions" in struct or "image_versions2" in struct):
        return False
    item._iphone_struct_ = struct
    return True


def version():
    return instaloader.__version__
