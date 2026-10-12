"""Bridge between the Android app and Instaloader.

Every public function returns a JSON string envelope so the Kotlin side never has
to deal with Python exceptions or Python objects:

    {"ok": true,  "data": ...}
    {"ok": false, "error": {"code": "<ERROR_CODE>", "detail": "<text for logs>"}}

Error codes are a closed set (see ``ErrorCode``) and mirrored by ``ErrorCode`` in
Kotlin. Keep both in sync.
"""

import json
import sys
import time

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


def version():
    return instaloader.__version__
