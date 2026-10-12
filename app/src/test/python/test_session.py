"""Cookie hand-over tests. They run on desktop Python without network access."""

import json

import pytest
import requests
from requests.adapters import BaseAdapter
from instaloader import exceptions as ie

import igbridge

COOKIES = {
    "sessionid": "123%3Aabc%3A27%3AXYZ",
    "csrftoken": "csrf-token-value",
    "ds_user_id": "123",
    "mid": "mid-value",
}


class FakeInstagram(BaseAdapter):
    """Transport adapter that records requests and replays a canned response."""

    def __init__(self, status=200, body=None, exc=None):
        super().__init__()
        self.status, self.body, self.exc = status, body, exc
        self.requests = []

    def send(self, request, **kwargs):
        self.requests.append(request)
        if self.exc is not None:
            raise self.exc
        resp = requests.Response()
        resp.status_code = self.status
        resp.reason = {200: "OK", 429: "Too Many Requests", 404: "Not Found"}.get(self.status, "")
        resp._content = json.dumps(self.body or {}).encode()
        resp.headers["Content-Type"] = "application/json"
        resp.url = request.url
        resp.request = request
        return resp

    def close(self):
        pass


@pytest.fixture
def instagram(monkeypatch):
    """Route every HTTPS request (Instaloader copies its session per query) to a fake."""
    def install(**kwargs):
        fake = FakeInstagram(**kwargs)
        monkeypatch.setattr(requests.adapters.HTTPAdapter, "send",
                            lambda self, request, **kw: fake.send(request, **kw))
        return fake
    return install


@pytest.fixture(autouse=True)
def reset():
    igbridge._loader = None
    yield
    igbridge._loader = None


def env(raw):
    return json.loads(raw)


# --- parsing ---------------------------------------------------------------

def test_parse_cookies_accepts_json_string_and_trims():
    clean = igbridge.parse_cookies(json.dumps({" sessionid ": " a ", "csrftoken": "b", "ds_user_id": "1"}))
    assert clean == {"sessionid": "a", "csrftoken": "b", "ds_user_id": "1"}


@pytest.mark.parametrize("missing", igbridge.REQUIRED_COOKIES)
def test_missing_required_cookie_is_rejected(missing):
    cookies = dict(COOKIES)
    del cookies[missing]
    result = env(igbridge.load_session(json.dumps(cookies)))
    assert result["ok"] is False
    assert result["error"]["code"] == "INVALID_COOKIES"
    assert missing in result["error"]["detail"]


def test_garbage_payload_is_rejected():
    assert env(igbridge.load_session("not json"))["error"]["code"] == "INVALID_COOKIES"
    assert env(igbridge.load_session("[1,2]"))["error"]["code"] == "INVALID_COOKIES"


# --- loading ---------------------------------------------------------------

def test_load_session_puts_cookies_and_csrf_header_on_session():
    assert env(igbridge.load_session(json.dumps(COOKIES))) == {"ok": True, "data": None}
    session = igbridge._loader.context._session
    assert session.cookies.get_dict() == COOKIES
    assert session.headers["X-CSRFToken"] == "csrf-token-value"
    assert igbridge._loader.context.is_logged_in


def test_cookies_are_sent_verbatim_to_instagram(instagram):
    loader = igbridge.load_session_from_cookies(COOKIES)
    fake = instagram(body={"data": {"user": {"username": "alice"}}, "status": "ok"})

    assert igbridge.check_login(loader) == "alice"

    sent = fake.requests[0]
    assert sent.url.startswith("https://www.instagram.com/graphql/query")
    cookie_header = sent.headers["Cookie"]
    for name, value in COOKIES.items():
        # URL-encoded values (sessionid has %3A) must not be decoded or re-encoded.
        assert "%s=%s" % (name, value) in cookie_header
    assert sent.headers["X-CSRFToken"] == "csrf-token-value"


# --- verification ----------------------------------------------------------

def test_verify_without_session():
    assert env(igbridge.verify_session())["error"]["code"] == "NOT_LOGGED_IN"


def test_verify_success_sets_real_username(instagram):
    loader = igbridge.load_session_from_cookies(COOKIES)
    instagram(body={"data": {"user": {"username": "alice"}}, "status": "ok"})
    assert env(igbridge.verify_session()) == {"ok": True, "data": {"username": "alice"}}
    assert loader.context.username == "alice"


def test_verify_logged_out_session(instagram):
    loader = igbridge.load_session_from_cookies(COOKIES)
    instagram(body={"data": {"user": None}, "status": "ok"})
    assert env(igbridge.verify_session())["error"]["code"] == "SESSION_EXPIRED"


def test_verify_network_error_is_not_reported_as_logged_out(instagram):
    loader = igbridge.load_session_from_cookies(COOKIES)
    instagram(exc=requests.exceptions.ConnectionError("no route to host"))
    assert env(igbridge.verify_session())["error"]["code"] == "NETWORK"


def test_verify_rate_limited(instagram):
    loader = igbridge.load_session_from_cookies(COOKIES)
    instagram(status=429, body={"status": "fail", "message": "rate limited"})
    assert env(igbridge.verify_session())["error"]["code"] == "RATE_LIMITED"


def test_check_login_restores_context_error(instagram):
    loader = igbridge.load_session_from_cookies(COOKIES)
    instagram(body={"data": {"user": {"username": "alice"}}, "status": "ok"})
    igbridge.check_login(loader)
    assert "error" not in vars(loader.context)


def test_clear_session():
    igbridge.load_session_from_cookies(COOKIES)
    assert env(igbridge.clear_session())["ok"] is True
    assert igbridge._loader is None


# --- classification --------------------------------------------------------

def chained(outer, inner):
    try:
        try:
            raise inner
        except Exception as e:
            raise outer from e
    except Exception as e:
        return e


@pytest.mark.parametrize("exc, code", [
    (ie.LoginRequiredException("x"), "SESSION_EXPIRED"),
    (ie.AbortDownloadException("Redirected to login page. You've been logged out"), "SESSION_EXPIRED"),
    (ie.AbortDownloadException('400 Bad Request - "fail" status, message "checkpoint_required"'),
     "CHALLENGE_REQUIRED"),
    (ie.PrivateProfileNotFollowedException("x"), "PRIVATE"),
    (ie.QueryReturnedForbiddenException("x"), "PRIVATE"),
    (ie.QueryReturnedNotFoundException("x"), "NOT_FOUND"),
    (ie.BadResponseException("Fetching Post metadata failed."), "NOT_FOUND"),
    (ie.TooManyRequestsException("x"), "RATE_LIMITED"),
    (igbridge.RateLimitedError("x"), "RATE_LIMITED"),
    (ie.ConnectionException('401 Unauthorized - "fail" status, message "Please wait a few minutes '
                            'before you try again."'), "RATE_LIMITED"),
    (chained(ie.ConnectionException("JSON Query"), ie.TooManyRequestsException("429")), "RATE_LIMITED"),
    (chained(ie.ConnectionException("JSON Query"), requests.exceptions.ReadTimeout("t")), "NETWORK"),
    (ie.ConnectionException("500 Internal Server Error"), "UNKNOWN"),
    (ValueError("boom"), "UNKNOWN"),
])
def test_classify(exc, code):
    assert igbridge.classify(exc) == code


def test_fast_fail_rate_controller():
    loader = igbridge.load_session_from_cookies(COOKIES)
    rc = loader.context._rate_controller
    rc.sleep(0)  # short waits are fine
    with pytest.raises(igbridge.RateLimitedError):
        rc.sleep(igbridge.DEFAULT_MAX_WAIT_SECONDS + 1)
