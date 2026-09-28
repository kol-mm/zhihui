"""
Whether the OpenAI-compatible provider offers the model it is configured with.

At start-up the service asks the configured endpoint which models it offers (GET {base_url}/models, the same
address the chat request is built from) and remembers the answer for the life of the process. The configured model
is always the one called: if the endpoint does not list it, a warning says so, but nothing is switched — the first
model a service lists may be an embedding model or an expensive one, and choosing is the administrator's call. When
the list cannot be had — timeout, an HTTP error, no such endpoint, an empty list — a warning says that instead. The list is
not fetched again: models added or withdrawn upstream take effect at the next restart. AI_MODEL_DISCOVERY_ENABLED=false
turns the whole thing off, and nothing is fetched.

Qwen3 models think before answering unless told not to, which costs time the review cannot spare, and some
endpoints refuse a non-streaming request that leaves thinking on. They alone are sent "enable_thinking": false —
a parameter the OpenAI API itself rejects, so no other model receives it.
"""
from __future__ import annotations

import json
import logging
import os
import threading
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Callable

DISCOVERY_TIMEOUT_SECONDS = 3.0
# A model list is small; a response larger than this is not one, and is not read in full.
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
MAX_MODELS = 2000
MAX_MODEL_ID_LENGTH = 200
LOGGED_MODELS = 50

log = logging.getLogger("ai.model_discovery")
if not log.handlers:
    # The service configures no logging of its own, so INFO would otherwise vanish; these lines are the record
    # of which model is in use.
    _handler = logging.StreamHandler()
    _handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s [%(name)s] %(message)s"))
    log.addHandler(_handler)
    log.setLevel(logging.INFO)
    log.propagate = False


@dataclass(frozen=True)
class Discovery:
    """What start-up learned. `models` is empty unless `outcome` is "ok"."""
    outcome: str  # "ok", "failed", "skipped" or "disabled"
    base_url: str = ""
    models: tuple[str, ...] = ()
    reason: str = ""


@dataclass
class _State:
    discovery: Discovery = field(default_factory=lambda: Discovery("skipped", reason="not run yet"))
    warned: set[tuple[str, str]] = field(default_factory=set)
    lock: threading.Lock = field(default_factory=threading.Lock)


_state = _State()


def enabled() -> bool:
    return os.getenv("AI_MODEL_DISCOVERY_ENABLED", "true").strip().lower() not in {"0", "false", "no", "off"}


def normalized_base(base_url: str | None) -> str:
    return str(base_url or "").strip().rstrip("/")


def wants_thinking_off(model: str | None) -> bool:
    """Qwen3, by its own name or after an organisation prefix such as "Qwen/Qwen3-32B"."""
    name = str(model or "").strip().lower().rsplit("/", 1)[-1]
    return name.startswith("qwen3")


def _fetch_models(url: str, api_key: str, timeout: float) -> Any:
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {api_key}"}, method="GET")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read(MAX_RESPONSE_BYTES + 1)
    if len(raw) > MAX_RESPONSE_BYTES:
        raise ValueError("model list response is too large")
    return json.loads(raw.decode("utf-8"))


def _model_ids(body: Any) -> tuple[str, ...]:
    """The ids in an OpenAI-style list ({"data": [{"id": ...}]}), in order, without junk or repeats."""
    items = body.get("data") if isinstance(body, dict) else None
    if not isinstance(items, list):
        raise ValueError("response is not a model list")
    ids: list[str] = []
    for item in items[:MAX_MODELS]:
        value = item.get("id") if isinstance(item, dict) else None
        if isinstance(value, str) and value.strip() and len(value) <= MAX_MODEL_ID_LENGTH and value.isprintable():
            if value.strip() not in ids:
                ids.append(value.strip())
    return tuple(ids)


def discover(base_url: str | None, api_key: str | None, validate: Callable[[str], None],
             fetch: Callable[[str, str, float], Any] | None = None) -> Discovery:
    """
    One attempt at the model list. Never raises: every failure is an outcome. `validate` is the service's check
    that an upstream address is public and https — the key goes with this request, as with every other.
    """
    if not enabled():
        return Discovery("disabled", reason="AI_MODEL_DISCOVERY_ENABLED is off")
    base = normalized_base(base_url)
    if not base:
        return Discovery("skipped", reason="no base_url is configured (a full request_url alone does not say where "
                                           "the model list is)")
    if not (api_key or "").strip():
        return Discovery("skipped", base, reason="no API key is configured")
    url = base + "/models"
    try:
        validate(url)
    except ValueError as error:
        return Discovery("failed", base, reason=f"the address was refused: {error}")
    try:
        body = (fetch or _fetch_models)(url, api_key.strip(), DISCOVERY_TIMEOUT_SECONDS)
        models = _model_ids(body)
    except urllib.error.HTTPError as error:
        return Discovery("failed", base, reason=f"HTTP {error.code}")
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        return Discovery("failed", base, reason=f"unreachable ({type(error).__name__})")
    except (ValueError, UnicodeDecodeError) as error:
        return Discovery("failed", base, reason=str(error) or "unreadable response")
    if not models:
        return Discovery("failed", base, reason="the endpoint returned an empty model list")
    return Discovery("ok", base, models)


def select_model(configured: str | None, base_url: str | None, discovery: Discovery | None = None) -> tuple[str, str | None]:
    """
    (model to call, a warning — or None). The model is always the configured one; the warning says when the
    endpoint's start-up list does not include it. The list only counts for the base URL it came from: after the
    address is changed in the settings, nothing is checked until a restart.
    """
    configured = str(configured or "").strip()
    found = discovery or current()
    if found.outcome != "ok" or found.base_url != normalized_base(base_url) or configured in found.models:
        return configured, None
    return configured, (f"configured model {configured!r} is not in the {len(found.models)} models "
                        f"{found.base_url} lists; calling it anyway, as configured")


def active_model(configured: str | None, base_url: str | None) -> str:
    """The configured model, warning once per configuration when the endpoint did not list it."""
    model, warning = select_model(configured, base_url)
    if warning:
        key = (str(configured or ""), normalized_base(base_url))
        with _state.lock:
            first_time = key not in _state.warned
            _state.warned.add(key)
        if first_time:
            log.warning("model not listed: %s", warning)
    return model


def current() -> Discovery:
    return _state.discovery


def remember(discovery: Discovery) -> None:
    with _state.lock:
        _state.discovery = discovery
        _state.warned.clear()


def run_at_startup(provider: str, base_url: str | None, api_key: str | None, configured_model: str | None,
                   validate: Callable[[str], None], fetch: Callable[[str, str, float], Any] | None = None) -> Discovery:
    """Discover (for the OpenAI-compatible provider only), remember the result, and log what it means."""
    if provider != "openai-compatible":
        found = Discovery("skipped", reason=f"the configured provider is {provider!r}, not openai-compatible")
    else:
        found = discover(base_url, api_key, validate, fetch)
    remember(found)
    configured = str(configured_model or "").strip()
    if found.outcome == "ok":
        shown = ", ".join(repr(model) for model in found.models[:LOGGED_MODELS])
        more = f" and {len(found.models) - LOGGED_MODELS} more" if len(found.models) > LOGGED_MODELS else ""
        log.info("model discovery: %d models at %s: %s%s", len(found.models), found.base_url, shown, more)
    elif found.outcome == "failed":
        log.warning("model discovery failed (%s); using the configured model %r as entered", found.reason, configured)
    else:
        log.info("model discovery %s: %s", found.outcome, found.reason)
    if provider == "openai-compatible":
        model, warning = select_model(configured, base_url, found)
        if warning:
            with _state.lock:
                _state.warned.add((configured, normalized_base(base_url)))
            log.warning("model not listed: %s", warning)
        log.info("active model: %r%s", model, " (thinking turned off: Qwen3)" if wants_thinking_off(model) else "")
    return found


def run_in_background(**arguments: Any) -> threading.Thread:
    """Start-up never waits on it: until it finishes, requests use the configured model."""
    thread = threading.Thread(target=lambda: _run_quietly(arguments), name="model-discovery", daemon=True)
    thread.start()
    return thread


def _run_quietly(arguments: dict[str, Any]) -> None:
    try:
        run_at_startup(**arguments)
    except Exception as error:  # noqa: BLE001 - discovery must never take the service down
        remember(Discovery("failed", reason=f"unexpected error ({type(error).__name__})"))
        log.warning("model discovery failed unexpectedly (%s); using the configured model", type(error).__name__)
