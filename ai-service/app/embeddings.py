"""
Vectors for knowledge chunks, from an embedding model when one is configured.

Without one, every chunk carries the local hashed-token vector built in main.build_embedding, which only measures
shared words. With an OpenAI-compatible embedding model named in the settings (embedding_model), chunks are given
the model's vectors, which measure meaning, so a question can find a passage that shares no words with it.

Indexing never waits for the model: a chunk is stored at once with its local vector, and a background worker
fetches the model's vectors in small batches afterwards. Each chunk records which model its vector came from
(embedding_model), so the worker knows what is left to do, retrieval only compares vectors from the same model,
and changing the model re-embeds everything by itself. When the model cannot be reached, the worker backs off and
retrieval falls back to word matching; nothing fails for the member asking.

The key and address are the OpenAI-compatible provider's, shared with chat and review ({base_url}/embeddings),
and the address is checked like every other upstream before the key goes to it.
"""
from __future__ import annotations

import json
import logging
import math
import operator
import os
import threading
import time
import urllib.error
import urllib.request
from array import array
from typing import Any, Callable

LOCAL_TAG = "local-hash"
# A chunk the model refused (for example, one that fails the provider's content inspection) is recorded under this
# prefix, so the worker moves past it; it stays matched by words, and a different model tries it again.
REFUSED_PREFIX = "refused:"
# HTTP answers that refuse the input itself. Anything else — a bad key, a rate limit, an outage, a malformed
# answer — says nothing about one chunk, so no chunk is ever marked refused for it.
INPUT_REFUSED_STATUSES = frozenset({400, 413, 422})
BATCH_SIZE = 10
# One input's length, in characters; embedding models accept a few thousand tokens.
MAX_INPUT_CHARS = 2000
# How close a passage must be in meaning to be offered on meaning alone (it shares no words with the question).
# Cosine similarity from current embedding models: unrelated text sits well below this, paraphrases above it.
SEMANTIC_MIN_SIMILARITY = 0.5
IDLE_SECONDS = 60.0
MAX_BACKOFF_SECONDS = 600.0

log = logging.getLogger("ai.embeddings")


class EmbeddingError(Exception):
    """The model gave no usable vectors. The message is safe to show an administrator."""

    def __init__(self, message: str, input_refused: bool = False) -> None:
        super().__init__(message)
        # True only when the provider refused the input it was given, not when it could not answer at all.
        self.input_refused = input_refused


def configured_model(config: dict[str, Any]) -> str:
    return str(config.get("embedding_model") or "").strip()


def model_tag(config: dict[str, Any]) -> str:
    """What a chunk's vector is recorded as coming from under these settings."""
    model = configured_model(config)
    return f"openai-compatible:{model}" if model else LOCAL_TAG


def refused_tag(tag: str) -> str:
    return REFUSED_PREFIX + tag


def endpoint(config: dict[str, Any]) -> str:
    base = str(config.get("base_url") or "").strip().rstrip("/")
    return base + "/embeddings" if base else ""


def input_text(title: str, content: str) -> str:
    """What is embedded for a chunk: its document's title gives the passage context."""
    return f"{title}\n{content}"[:MAX_INPUT_CHARS]


def fetch_embeddings(texts: list[str], config: dict[str, Any], api_key: str | None,
                     validate: Callable[[str], None],
                     post: Callable[[str, dict[str, Any], str, float], Any] | None = None) -> list[list[float]]:
    """The model's vectors for these texts, in order, or EmbeddingError."""
    model = configured_model(config)
    url = endpoint(config)
    if not model:
        raise EmbeddingError("没有配置向量模型")
    if not url:
        raise EmbeddingError("没有填写兼容接口基础地址（base_url）")
    if not (api_key or "").strip():
        raise EmbeddingError("没有可用的 OpenAI 兼容接口密钥")
    try:
        validate(url)
    except ValueError as error:
        raise EmbeddingError(f"接口地址不可用：{error}") from error
    body = {"model": model, "input": [text[:MAX_INPUT_CHARS] for text in texts]}
    timeout = float(os.getenv("AI_API_TIMEOUT", "30"))
    try:
        answer = (post or _post)(url, body, api_key.strip(), timeout)
    except urllib.error.HTTPError as error:
        raise EmbeddingError(f"向量接口返回错误（HTTP {error.code}）",
                             input_refused=error.code in INPUT_REFUSED_STATUSES) from error
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise EmbeddingError(f"无法连接向量接口（{type(error).__name__}）") from error
    except ValueError as error:
        raise EmbeddingError("向量接口返回的内容无法解析") from error
    return _vectors(answer, len(texts))


def _post(url: str, body: dict[str, Any], api_key: str, timeout: float) -> Any:
    request = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), method="POST",
                                     headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def _vectors(answer: Any, expected: int) -> list[list[float]]:
    items = answer.get("data") if isinstance(answer, dict) else None
    if not isinstance(items, list) or len(items) != expected:
        raise EmbeddingError("向量接口返回的数量与请求不符")
    ordered = sorted(items, key=lambda item: item.get("index", 0) if isinstance(item, dict) else 0)
    vectors: list[list[float]] = []
    for item in ordered:
        vector = item.get("embedding") if isinstance(item, dict) else None
        if (not isinstance(vector, list) or not vector
                or not all(isinstance(value, (int, float)) and math.isfinite(value) for value in vector)):
            raise EmbeddingError("向量接口返回的向量无效")
        vectors.append([float(value) for value in vector])
    if len({len(vector) for vector in vectors}) != 1:
        raise EmbeddingError("向量接口返回的向量长度不一致")
    return vectors


# ---- comparing -------------------------------------------------------------------------------------------------

def as_array(vector: list[float]) -> array:
    return array("f", vector)


def cosine(left: array | list[float], right: array | list[float]) -> float:
    """Cosine similarity; models do not all return unit vectors, so it is not taken for granted."""
    if not left or not right or len(left) != len(right):
        return 0.0
    dot = sum(map(operator.mul, left, right))
    norms = math.sqrt(sum(map(operator.mul, left, left))) * math.sqrt(sum(map(operator.mul, right, right)))
    return dot / norms if norms else 0.0


class VectorCache:
    """Parsed chunk vectors by chunk id, so a question does not re-read every vector's JSON. Chunk ids are never
    reused (AUTOINCREMENT) and a changed vector changes its tag, so an entry is valid while its tag matches."""

    def __init__(self) -> None:
        self._entries: dict[int, tuple[str, array]] = {}
        self._lock = threading.Lock()

    def get(self, chunk_id: int, tag: str, raw: str | None) -> array | None:
        with self._lock:
            entry = self._entries.get(chunk_id)
        if entry and entry[0] == tag:
            return entry[1]
        if not raw:
            return None
        try:
            vector = as_array(json.loads(raw))
        except (ValueError, TypeError):
            return None
        with self._lock:
            self._entries[chunk_id] = (tag, vector)
        return vector

    def forget_missing(self, present: set[int]) -> None:
        with self._lock:
            for chunk_id in [key for key in self._entries if key not in present]:
                del self._entries[chunk_id]


# ---- the background worker ---------------------------------------------------------------------------------------

class EmbeddingWorker:
    """Gives every chunk the configured model's vector, a batch at a time, in the background."""

    def __init__(self, connect: Callable[[], Any], read_config: Callable[[], dict[str, Any]],
                 resolve_key: Callable[[], str | None], validate: Callable[[str], None],
                 fetch: Callable[..., list[list[float]]] = fetch_embeddings) -> None:
        self._connect = connect
        self._read_config = read_config
        self._resolve_key = resolve_key
        self._validate = validate
        self._fetch = fetch
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._lock = threading.Lock()
        self.last_error: str | None = None
        self.last_error_at: str | None = None
        self._backoff = 0.0

    def wake(self) -> None:
        """New chunks or new settings: look again now rather than at the next idle check."""
        self._wake.set()

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._thread = threading.Thread(target=self._run, name="embedding-worker", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._wake.set()

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                pause = self.run_once()
            except Exception as error:  # noqa: BLE001 - the worker must outlive any one bad batch
                self._failed(f"{type(error).__name__}")
                pause = self._next_backoff()
            self._wake.wait(pause)
            self._wake.clear()

    def run_once(self) -> float:
        """Embeds one batch. Returns how long to wait before the next: 0 while there is more to do."""
        config = self._read_config()
        tag = model_tag(config)
        if tag == LOCAL_TAG:
            return IDLE_SECONDS
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT id, title, content FROM knowledge_chunk WHERE COALESCE(embedding_model, '') NOT IN (?, ?) "
                "ORDER BY id LIMIT ?", (tag, refused_tag(tag), BATCH_SIZE)).fetchall()
        if not rows:
            return IDLE_SECONDS
        try:
            vectors = self._embed(rows, config)
        except EmbeddingError as error:
            if not error.input_refused:
                self._failed(str(error))
                return self._next_backoff()
            # The provider refused something in the batch. Asked one at a time, the chunks it refuses are set
            # aside and the rest are embedded, so one chunk can never hold up the whole library.
            return self._one_at_a_time(rows, config, tag)
        self._store(rows, vectors, tag)
        self._succeeded()
        return 0.0

    def _embed(self, rows: list[Any], config: dict[str, Any]) -> list[list[float]]:
        return self._fetch([input_text(row["title"], row["content"]) for row in rows], config,
                           self._resolve_key(), self._validate)

    def _one_at_a_time(self, rows: list[Any], config: dict[str, Any], tag: str) -> float:
        refused: list[Any] = []
        last_error = ""
        for row in rows:
            try:
                vector = self._embed([row], config)
            except EmbeddingError as error:
                if not error.input_refused:
                    self._failed(str(error))
                    return self._next_backoff()
                refused.append(row)
                last_error = str(error)
                continue
            self._store([row], vector, tag)
        # A wrong model name is refused as bad input too, for every chunk. Chunks are only set aside once the model
        # has shown it works (including just now, for another chunk of this batch); until then nothing is marked,
        # and the failure is reported and retried like any other.
        if refused and not self._model_has_worked(tag):
            self._failed(last_error)
            return self._next_backoff()
        for row in refused:
            self._refuse(row, tag)
        self._succeeded()
        return 0.0

    def _model_has_worked(self, tag: str) -> bool:
        with self._connect() as conn:
            return conn.execute("SELECT 1 FROM knowledge_chunk WHERE embedding_model = ? LIMIT 1", (tag,)).fetchone() is not None

    def _store(self, rows: list[Any], vectors: list[list[float]], tag: str) -> None:
        with self._connect() as conn:
            for row, vector in zip(rows, vectors):
                # Only if the chunk is still the one that was embedded; a re-index in between replaced it.
                conn.execute("UPDATE knowledge_chunk SET embedding = ?, embedding_model = ? WHERE id = ? AND content = ?",
                             (json.dumps(vector), tag, row["id"], row["content"]))

    def _refuse(self, row: Any, tag: str) -> None:
        """Keeps the chunk's local vector and marks it refused, so it is matched by words and not asked again."""
        log.warning("embedding model refused knowledge chunk %s; it stays matched by words", row["id"])
        with self._connect() as conn:
            conn.execute("UPDATE knowledge_chunk SET embedding_model = ? WHERE id = ? AND content = ?",
                         (refused_tag(tag), row["id"], row["content"]))

    def _succeeded(self) -> None:
        with self._lock:
            self._backoff = 0.0
            self.last_error = None
            self.last_error_at = None

    def _failed(self, message: str) -> None:
        with self._lock:
            first = self.last_error != message
            self.last_error = message
            self.last_error_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        if first:
            log.warning("embedding failed: %s", message)

    def _next_backoff(self) -> float:
        with self._lock:
            self._backoff = min(MAX_BACKOFF_SECONDS, max(30.0, self._backoff * 2))
            return self._backoff


def status(conn: Any, config: dict[str, Any], worker: EmbeddingWorker | None) -> dict[str, Any]:
    """What the settings page shows: which vectors are in use and how far the model has got."""
    tag = model_tag(config)
    total = conn.execute("SELECT COUNT(*) FROM knowledge_chunk").fetchone()[0]
    done = conn.execute("SELECT COUNT(*) FROM knowledge_chunk WHERE embedding_model = ?", (tag,)).fetchone()[0]
    refused = conn.execute("SELECT COUNT(*) FROM knowledge_chunk WHERE embedding_model = ?", (refused_tag(tag),)).fetchone()[0]
    semantic = tag != LOCAL_TAG
    return {
        "model": configured_model(config),
        "semantic": semantic,
        "total": int(total),
        "embedded": int(done) if semantic else 0,
        "refused": int(refused) if semantic else 0,
        "pending": int(total - done - refused) if semantic else 0,
        "last_error": worker.last_error if worker and tag != LOCAL_TAG else None,
        "last_error_at": worker.last_error_at if worker and tag != LOCAL_TAG else None,
    }
