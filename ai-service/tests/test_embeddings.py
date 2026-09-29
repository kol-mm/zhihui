"""
Semantic retrieval: an embedding model's vectors for knowledge chunks, fetched in the background, compared only
with vectors from the same model, and retrieval that finds a passage by meaning as well as by shared words.
"""
import base64
import hashlib
import hmac
import json
import os
import tempfile
import time
import unittest
import urllib.error
from unittest import mock

BASE = "https://models.example.test/v1"
MODEL = "text-embedding-v3"


def token(role: str, super_admin: bool = False) -> str:
    def segment(value: dict) -> str:
        return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).decode().rstrip("=")

    claims = {"sub": "admin" if role == "ADMIN" else "demo", "uid": 2 if role == "ADMIN" else 1, "role": role,
              "iat": int(time.time()), "exp": int(time.time()) + 600}
    if super_admin:
        claims["sa"] = True
    signing_input = f"{segment({'alg': 'HS256', 'typ': 'JWT'})}.{segment(claims)}"
    signature = base64.urlsafe_b64encode(hmac.new(
        b"local-dev-secret-change-before-production", signing_input.encode(), hashlib.sha256).digest()).decode().rstrip("=")
    return f"Bearer {signing_input}.{signature}"


class EmbeddingsTestBase(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = tempfile.TemporaryDirectory()
        os.environ["AI_DB_PATH"] = os.path.join(self.tmpdir.name, "embeddings_test.db")
        os.environ["AI_API_KEY"] = "compatible-key"
        from app import embeddings, main

        self.main = main
        self.embeddings = embeddings
        self.main.init_db()
        self.admin = token("ADMIN")
        self.super_admin = token("ADMIN", super_admin=True)
        self.member = token("USER")
        audit = mock.patch.object(self.main, "record_admin_action")
        audit.start()
        self.addCleanup(audit.stop)

    def tearDown(self) -> None:
        self.tmpdir.cleanup()
        for name in ("AI_DB_PATH", "AI_API_KEY"):
            os.environ.pop(name, None)

    def config(self, **overrides) -> dict:
        values = self.main.read_ai_config()
        values.update({"provider": "openai-compatible", "model": "qwen-max", "base_url": BASE, "embedding_model": MODEL})
        values.update(overrides)
        return values

    def index(self, file_id: int, text: str, title: str = "文档") -> None:
        self.main.parse_document(self.main.TextRequest(file_id=file_id, title=title, text=text), authorization=self.admin)

    def set_vector(self, file_id: int, vector: list[float], tag: str | None = None) -> None:
        with self.main.connect() as conn:
            conn.execute("UPDATE knowledge_chunk SET embedding = ?, embedding_model = ? WHERE file_id = ?",
                         (json.dumps(vector), tag or f"openai-compatible:{MODEL}", file_id))


class FetchTest(EmbeddingsTestBase):
    def posting(self, answer=None, error: Exception | None = None):
        self.sent = []

        def post(url, body, api_key, timeout):
            self.sent.append((url, body, api_key))
            if error:
                raise error
            return answer
        return post

    def fetch(self, texts, post, config=None, validate=lambda url: None):
        return self.embeddings.fetch_embeddings(texts, config or self.config(), "compatible-key", validate, post)

    def test_the_request_goes_to_the_providers_embeddings_endpoint(self) -> None:
        answer = {"data": [{"index": 1, "embedding": [0.0, 1.0]}, {"index": 0, "embedding": [1.0, 0.0]}]}
        vectors = self.fetch(["甲", "乙"], self.posting(answer))

        self.assertEqual([[1.0, 0.0], [0.0, 1.0]], vectors, "put back in the order asked, by index")
        url, body, key = self.sent[0]
        self.assertEqual((BASE + "/embeddings", {"model": MODEL, "input": ["甲", "乙"]}, "compatible-key"), (url, body, key))

    def test_a_refused_address_is_never_contacted(self) -> None:
        def refuse(url):
            raise ValueError("private AI upstream addresses are disabled")

        post = self.posting({"data": []})
        with self.assertRaises(self.embeddings.EmbeddingError):
            self.fetch(["甲"], post, validate=refuse)
        self.assertEqual([], self.sent)

    def test_every_kind_of_bad_answer_is_an_embedding_error(self) -> None:
        bad = {
            "http error": self.posting(error=urllib.error.HTTPError(BASE, 401, "no", {}, None)),
            "unreachable": self.posting(error=urllib.error.URLError("refused")),
            "wrong count": self.posting({"data": [{"index": 0, "embedding": [1.0]}]}),
            "not a list": self.posting({"data": [{"index": 0, "embedding": "x"}, {"index": 1, "embedding": [1.0]}]}),
            "not finite": self.posting({"data": [{"index": 0, "embedding": [float("nan")]}, {"index": 1, "embedding": [1.0]}]}),
            "lengths differ": self.posting({"data": [{"index": 0, "embedding": [1.0]}, {"index": 1, "embedding": [1.0, 2.0]}]}),
        }
        for name, post in bad.items():
            with self.subTest(name), self.assertRaises(self.embeddings.EmbeddingError):
                self.fetch(["甲", "乙"], post)

    def test_only_an_answer_about_the_input_says_the_input_was_refused(self) -> None:
        for code, refused in ((400, True), (413, True), (422, True), (401, False), (403, False), (404, False),
                              (429, False), (500, False), (503, False)):
            with self.subTest(code), self.assertRaises(self.embeddings.EmbeddingError) as raised:
                self.fetch(["甲"], self.posting(error=urllib.error.HTTPError(BASE, code, "no", {}, None)))
            self.assertIs(refused, raised.exception.input_refused)
        for post in (self.posting(error=urllib.error.URLError("refused")), self.posting({"data": []})):
            with self.assertRaises(self.embeddings.EmbeddingError) as raised:
                self.fetch(["甲"], post)
            self.assertFalse(raised.exception.input_refused)

    def test_nothing_is_sent_without_a_model_an_address_or_a_key(self) -> None:
        post = self.posting({"data": []})
        for config in (self.config(embedding_model=""), self.config(base_url="")):
            with self.assertRaises(self.embeddings.EmbeddingError):
                self.fetch(["甲"], post, config=config)
        with self.assertRaises(self.embeddings.EmbeddingError):
            self.embeddings.fetch_embeddings(["甲"], self.config(), "", lambda url: None, post)
        self.assertEqual([], self.sent)

    def test_cosine_does_not_assume_unit_vectors(self) -> None:
        self.assertAlmostEqual(1.0, self.embeddings.cosine([3.0, 4.0], [6.0, 8.0]))
        self.assertAlmostEqual(0.0, self.embeddings.cosine([1.0, 0.0], [0.0, 5.0]))
        self.assertEqual(0.0, self.embeddings.cosine([1.0], [1.0, 2.0]))


class ChunkingTest(EmbeddingsTestBase):
    def test_short_lines_are_packed_into_chunks_of_a_few_sentences(self) -> None:
        lines = [f"第{i}句关于知识库检索的说明。" for i in range(80)]
        chunks = self.main.split_text("\n".join(lines))

        self.assertGreater(len(chunks), 1)
        self.assertLess(len(chunks), 20)
        self.assertTrue(all(len(chunk) <= self.main.CHUNK_CHARS for chunk in chunks))
        self.assertEqual(lines, "\n".join(chunks).split("\n"), "nothing lost, nothing reordered")

    def test_a_line_too_long_for_one_chunk_is_cut_with_overlap(self) -> None:
        line = "".join(chr(0x4e00 + i % 500) for i in range(1300))
        chunks = self.main.split_text(line)

        self.assertTrue(all(len(chunk) <= self.main.CHUNK_CHARS for chunk in chunks))
        self.assertEqual(chunks[0][-self.main.CHUNK_OVERLAP:], chunks[1][:self.main.CHUNK_OVERLAP])
        self.assertTrue(line.endswith(chunks[-1][-50:]))

    def test_a_sensitive_line_is_dropped_but_its_neighbours_kept(self) -> None:
        chunks = self.main.split_text("知识库支持安全检索\n$env:MYSQL_PASSWORD = \"secret\"\n检索结果标注来源")

        self.assertEqual(["知识库支持安全检索\n检索结果标注来源"], chunks)


class IndexingTest(EmbeddingsTestBase):
    def test_only_administrators_index(self) -> None:
        # Whatever is indexed is quoted to everyone by AI 问答, and chunks are replaced by file id.
        with self.assertRaises(self.main.HTTPException) as refused:
            self.main.parse_document(self.main.TextRequest(file_id=5, text="伪造的内容"), authorization=self.member)
        self.assertEqual(403, refused.exception.status_code)
        self.assertEqual(0, self.main.vector_status().data["indexed_chunks"])

    def test_a_chunk_is_stored_at_once_with_its_local_vector_and_the_worker_is_woken(self) -> None:
        with mock.patch.object(self.main.embedding_worker, "wake") as wake:
            self.index(1, "知识库的检索方法")
        with self.main.connect() as conn:
            row = conn.execute("SELECT embedding, embedding_model FROM knowledge_chunk").fetchone()
        self.assertEqual(self.embeddings.LOCAL_TAG, row["embedding_model"])
        self.assertEqual(128, len(json.loads(row["embedding"])))
        wake.assert_called()


class InternalIndexTest(EmbeddingsTestBase):
    """The knowledge service keeps the index in step itself, through two internal endpoints."""

    def chunks(self) -> list[tuple[int, str]]:
        with self.main.connect() as conn:
            return [(row[0], row[1]) for row in conn.execute("SELECT file_id, title FROM knowledge_chunk ORDER BY id")]

    def test_only_the_services_may_change_the_index(self) -> None:
        for token in (None, "wrong"):
            with self.assertRaises(self.main.HTTPException) as refused:
                self.main.internal_index(self.main.TextRequest(file_id=5, title="伪造", text="伪造的内容"), x_internal_token=token)
            self.assertEqual(403, refused.exception.status_code)
            with self.assertRaises(self.main.HTTPException):
                self.main.internal_index_remove(self.main.IndexRemoval(file_id=5), x_internal_token=token)
        self.assertEqual([], self.chunks())

    def test_an_approved_file_replaces_its_chunks_and_a_removal_takes_only_that_file_out(self) -> None:
        token = "ai-knowledge-local-internal"
        self.main.internal_index(self.main.TextRequest(file_id=5, title="旧标题", text="旧的正文"), x_internal_token=token)
        self.main.internal_index(self.main.TextRequest(file_id=5, title="新标题", text="新的正文"), x_internal_token=token)
        self.main.internal_index(self.main.TextRequest(file_id=6, title="另一份", text="另一份正文"), x_internal_token=token)
        self.assertEqual([(5, "新标题"), (6, "另一份")], sorted(self.chunks()))

        removed = self.main.internal_index_remove(self.main.IndexRemoval(file_id=5), x_internal_token=token)
        self.assertEqual(1, removed.data["removed"])
        self.assertEqual([(6, "另一份")], self.chunks())

    def test_a_file_id_is_required(self) -> None:
        with self.assertRaises(self.main.HTTPException) as refused:
            self.main.internal_index(self.main.TextRequest(title="无编号", text="正文"), x_internal_token="ai-knowledge-local-internal")
        self.assertEqual(400, refused.exception.status_code)


class WorkerTest(EmbeddingsTestBase):
    def worker(self, config: dict, fetch):
        return self.embeddings.EmbeddingWorker(connect=self.main.connect, read_config=lambda: config,
                                               resolve_key=lambda: "compatible-key", validate=lambda url: None,
                                               fetch=fetch)

    def tags(self) -> list[str]:
        with self.main.connect() as conn:
            return [row[0] for row in conn.execute("SELECT embedding_model FROM knowledge_chunk ORDER BY id")]

    def test_without_a_model_it_does_nothing(self) -> None:
        self.index(1, "内容")
        fetch = mock.Mock()
        self.assertEqual(self.embeddings.IDLE_SECONDS, self.worker(self.config(embedding_model=""), fetch).run_once())
        fetch.assert_not_called()

    def test_it_embeds_every_chunk_a_batch_at_a_time(self) -> None:
        for file_id in range(1, 13):
            self.index(file_id, f"第{file_id}份文档")
        batches = []

        def fetch(texts, config, key, validate):
            batches.append(len(texts))
            return [[1.0, float(len(text))] for text in texts]

        worker = self.worker(self.config(), fetch)
        self.assertEqual(0.0, worker.run_once())
        self.assertEqual(0.0, worker.run_once())
        self.assertEqual(self.embeddings.IDLE_SECONDS, worker.run_once())

        self.assertEqual([10, 2], batches)
        self.assertEqual({f"openai-compatible:{MODEL}"}, set(self.tags()))

    def test_a_new_model_re_embeds_everything(self) -> None:
        self.index(1, "内容")
        self.set_vector(1, [1.0, 0.0])
        worker = self.worker(self.config(embedding_model="text-embedding-v4"), lambda texts, *rest: [[0.5, 0.5]] * len(texts))
        worker.run_once()
        self.assertEqual(["openai-compatible:text-embedding-v4"], self.tags())

    def test_a_failure_backs_off_and_is_reported_until_a_batch_succeeds(self) -> None:
        self.index(1, "内容")
        failing = self.worker(self.config(), mock.Mock(side_effect=self.embeddings.EmbeddingError("向量接口返回错误（HTTP 401）")))

        first = failing.run_once()
        second = failing.run_once()
        self.assertGreaterEqual(first, 30.0)
        self.assertGreater(second, first)
        self.assertEqual("向量接口返回错误（HTTP 401）", failing.last_error)
        self.assertEqual([self.embeddings.LOCAL_TAG], self.tags())

        failing._fetch = lambda texts, *rest: [[1.0]] * len(texts)
        failing.run_once()
        self.assertIsNone(failing.last_error)

    def refusing(self, *bad: str, error=None):
        """A model that refuses any request containing one of these texts, and records what it was asked."""
        self.asked = []

        def fetch(texts, *rest):
            self.asked.append(len(texts))
            if any(word in text for text in texts for word in bad):
                raise error or self.embeddings.EmbeddingError("向量接口返回错误（HTTP 400）", input_refused=True)
            return [[1.0, float(len(text))] for text in texts]
        return fetch

    def test_a_refused_chunk_is_set_aside_and_the_rest_are_embedded(self) -> None:
        for file_id in range(1, 13):
            self.index(file_id, "敏感" if file_id == 3 else f"第{file_id}份文档")
        worker = self.worker(self.config(), self.refusing("敏感"))

        self.assertEqual(0.0, worker.run_once())
        self.assertEqual(0.0, worker.run_once())
        self.assertEqual(self.embeddings.IDLE_SECONDS, worker.run_once())

        tag = f"openai-compatible:{MODEL}"
        self.assertEqual([tag, tag, "refused:" + tag] + [tag] * 9, self.tags())
        self.assertEqual([10] + [1] * 10 + [2], self.asked, "the failed batch is asked again one chunk at a time")
        self.assertIsNone(worker.last_error)
        with self.main.connect() as conn:
            kept = conn.execute("SELECT embedding FROM knowledge_chunk WHERE file_id = 3").fetchone()["embedding"]
            status = self.embeddings.status(conn, self.config(), worker)
        self.assertEqual(128, len(json.loads(kept)), "the refused chunk keeps its local vector")
        self.assertEqual((12, 11, 1, 0), (status["total"], status["embedded"], status["refused"], status["pending"]))

    def test_a_new_model_tries_a_refused_chunk_again(self) -> None:
        self.index(1, "敏感")
        self.set_vector(1, [1.0], tag=f"refused:openai-compatible:{MODEL}")
        self.worker(self.config(), self.refusing()).run_once()
        self.assertEqual([f"refused:openai-compatible:{MODEL}"], self.tags(), "the same model is not asked again")

        self.worker(self.config(embedding_model="text-embedding-v4"), self.refusing()).run_once()
        self.assertEqual(["openai-compatible:text-embedding-v4"], self.tags())

    def test_an_outage_marks_nothing_refused(self) -> None:
        for file_id in (1, 2):
            self.index(file_id, f"第{file_id}份文档")
        for error in (self.embeddings.EmbeddingError("向量接口返回错误（HTTP 429）"),
                      self.embeddings.EmbeddingError("无法连接向量接口（URLError）")):
            with self.subTest(str(error)):
                worker = self.worker(self.config(), self.refusing("文档", error=error))
                self.assertGreaterEqual(worker.run_once(), 30.0)
                self.assertEqual([1], [len(self.asked)], "no chunk is asked for on its own")
                self.assertEqual([self.embeddings.LOCAL_TAG] * 2, self.tags())
                self.assertEqual(str(error), worker.last_error)

    def test_an_outage_while_asking_one_at_a_time_stops_and_backs_off(self) -> None:
        for file_id in (1, 2, 3):
            self.index(file_id, f"第{file_id}份文档")
        answers = iter([self.embeddings.EmbeddingError("向量接口返回错误（HTTP 400）", input_refused=True),
                        [[1.0]], self.embeddings.EmbeddingError("向量接口返回错误（HTTP 503）")])

        def fetch(texts, *rest):
            answer = next(answers)
            if isinstance(answer, Exception):
                raise answer
            return answer

        worker = self.worker(self.config(), fetch)
        self.assertGreaterEqual(worker.run_once(), 30.0)
        tag = f"openai-compatible:{MODEL}"
        self.assertEqual([tag, self.embeddings.LOCAL_TAG, self.embeddings.LOCAL_TAG], self.tags())
        self.assertEqual("向量接口返回错误（HTTP 503）", worker.last_error)

    def test_a_model_that_refuses_everything_is_reported_not_blamed_on_the_chunks(self) -> None:
        for file_id in (1, 2):
            self.index(file_id, f"第{file_id}份文档")
        worker = self.worker(self.config(), self.refusing("文档"))  # e.g. a model name the provider does not know

        self.assertGreaterEqual(worker.run_once(), 30.0)
        self.assertEqual([self.embeddings.LOCAL_TAG] * 2, self.tags())
        self.assertEqual("向量接口返回错误（HTTP 400）", worker.last_error)

    def test_once_the_model_has_worked_a_lone_refused_chunk_is_set_aside(self) -> None:
        self.index(1, "第一份文档")
        self.index(2, "敏感")
        self.set_vector(1, [1.0])
        worker = self.worker(self.config(), self.refusing("敏感"))

        self.assertEqual(0.0, worker.run_once())
        self.assertEqual(f"refused:openai-compatible:{MODEL}", self.tags()[1])
        self.assertIsNone(worker.last_error)

    def test_a_pass_that_only_sets_chunks_aside_clears_an_earlier_outage(self) -> None:
        self.index(1, "第一份文档")
        self.index(2, "敏感")
        self.set_vector(1, [1.0])
        worker = self.worker(self.config(), mock.Mock(side_effect=self.embeddings.EmbeddingError("无法连接向量接口（URLError）")))
        self.assertGreaterEqual(worker.run_once(), 30.0)

        worker._fetch = self.refusing("敏感")
        self.assertEqual(0.0, worker.run_once())
        self.assertIsNone(worker.last_error)
        self.assertIsNone(worker.last_error_at)

    def test_a_chunk_replaced_while_it_was_embedded_keeps_its_new_content(self) -> None:
        self.index(1, "旧内容")

        def fetch(texts, *rest):
            self.index(1, "新内容")  # re-indexed in between
            return [[1.0]] * len(texts)

        self.worker(self.config(), fetch).run_once()
        with self.main.connect() as conn:
            row = conn.execute("SELECT content, embedding_model FROM knowledge_chunk").fetchone()
        self.assertEqual(("新内容", self.embeddings.LOCAL_TAG), (row["content"], row["embedding_model"]))


class RetrievalTest(EmbeddingsTestBase):
    def retrieve(self, question: str, vector=None, config=None):
        with mock.patch.object(self.main, "question_vector", return_value=vector) as asked:
            found = self.main.retrieve_chunks(question, limit=5, config=config or self.config())
        self.asked = asked
        return [item["file_id"] for item in found]

    def test_a_passage_is_found_by_meaning_without_sharing_a_word(self) -> None:
        self.index(1, "机器学习模型需要大量标注数据进行训练", title="训练")
        self.index(2, "午餐菜单包括米饭和汤", title="菜单")
        self.set_vector(1, [0.9, 0.1, 0.0])
        self.set_vector(2, [0.0, 0.1, 0.9])

        self.assertEqual([1], self.retrieve("How do I teach an AI system?", vector=[1.0, 0.0, 0.0]))

    def test_words_still_find_a_passage_the_model_has_not_described_yet(self) -> None:
        self.index(1, "知识库检索的使用方法")
        self.assertEqual([1], self.retrieve("知识库检索", vector=[1.0, 0.0]))

    def test_when_the_model_cannot_be_reached_it_matches_by_words(self) -> None:
        self.index(1, "知识库检索的使用方法")
        self.set_vector(1, [1.0, 0.0])
        self.assertEqual([1], self.retrieve("知识库检索", vector=None))
        self.assertEqual([], self.retrieve("How do I teach an AI system?", vector=None))

    def test_vectors_from_another_model_are_never_compared(self) -> None:
        self.index(1, "机器学习模型的训练")
        self.set_vector(1, [1.0, 0.0], tag="openai-compatible:some-other-model")
        # One chunk from the current model, far from the question, so the question is embedded at all.
        self.index(2, "午餐菜单")
        self.set_vector(2, [0.0, 1.0])
        self.assertEqual([], self.retrieve("How do I teach an AI system?", vector=[1.0, 0.0]))
        self.asked.assert_called_once()

    def test_without_an_embedding_model_the_question_is_not_sent_anywhere(self) -> None:
        self.index(1, "知识库检索的使用方法")
        self.assertEqual([1], self.retrieve("知识库检索", config=self.config(embedding_model="")))
        self.asked.assert_not_called()

    def test_the_whole_index_is_searched_not_only_the_newest_chunks(self) -> None:
        self.index(1, "最早上传的独特资料：量子退火")
        for file_id in range(2, 260):
            self.index(file_id, f"后来上传的普通资料 {file_id}")
        self.assertEqual([1], self.retrieve("量子退火", config=self.config(embedding_model="")))


class SettingsTest(EmbeddingsTestBase):
    def save(self, authorization, **fields):
        return self.main.save_ai_config(self.main.AiConfigRequest(**fields), authorization=authorization)

    def test_the_super_administrator_sets_the_model_and_the_worker_starts_on_it(self) -> None:
        with mock.patch.object(self.main.embedding_worker, "wake") as wake:
            self.save(self.super_admin, provider="openai-compatible", model="qwen-max", base_url=BASE,
                      embedding_model=f" {MODEL} ")
        self.assertEqual(MODEL, self.main.read_ai_config()["embedding_model"])
        wake.assert_called_once()

    def test_an_ordinary_administrator_cannot_change_it(self) -> None:
        self.save(self.super_admin, provider="openai-compatible", model="qwen-max", base_url=BASE, embedding_model=MODEL)
        self.save(self.admin, provider="openai-compatible", model="qwen-max", base_url=BASE, embedding_model="elsewhere")
        self.assertEqual(MODEL, self.main.read_ai_config()["embedding_model"])

    def test_a_model_without_an_address_is_refused(self) -> None:
        with self.assertRaises(self.main.HTTPException) as refused:
            self.save(self.super_admin, provider="anthropic", embedding_model=MODEL)
        self.assertIn("base_url", refused.exception.detail)

    def test_status_counts_what_the_model_has_described(self) -> None:
        self.index(1, "一")
        self.index(2, "二")
        self.set_vector(1, [1.0])
        with self.main.connect() as conn:
            status = self.embeddings.status(conn, self.config(), None)
        self.assertEqual({"model": MODEL, "semantic": True, "total": 2, "embedded": 1, "refused": 0, "pending": 1},
                         {key: status[key] for key in ("model", "semantic", "total", "embedded", "refused", "pending")})

    def test_status_counts_refused_chunks_for_the_current_model_only(self) -> None:
        for file_id in (1, 2, 3):
            self.index(file_id, str(file_id))
        self.set_vector(1, [1.0])
        self.set_vector(2, [1.0], tag=f"refused:openai-compatible:{MODEL}")
        self.set_vector(3, [1.0], tag="refused:openai-compatible:older-model")
        with self.main.connect() as conn:
            status = self.embeddings.status(conn, self.config(), None)
            local = self.embeddings.status(conn, self.config(embedding_model=""), None)
        self.assertEqual((3, 1, 1, 1), (status["total"], status["embedded"], status["refused"], status["pending"]))
        self.assertEqual((0, 0, 0), (local["embedded"], local["refused"], local["pending"]))

    def test_the_public_vector_status_says_nothing_about_the_model(self) -> None:
        self.index(1, "一")
        with mock.patch.object(self.main, "read_ai_config", return_value=self.config()):
            data = self.main.vector_status().data
        self.assertEqual({"mode", "dimension", "indexed_chunks", "external_ready"}, set(data))
        self.assertNotIn(MODEL, json.dumps(data))

    def test_startup_checks_the_embedding_model_even_when_chat_uses_claude(self) -> None:
        with mock.patch.object(self.main, "read_ai_config", return_value=self.config(provider="anthropic")), \
                mock.patch.object(self.main.model_discovery, "run_in_background") as started:
            self.main.start_model_discovery()
        arguments = started.call_args.kwargs
        self.assertEqual(("openai-compatible", MODEL), (arguments["provider"], arguments["embedding_model"]))


if __name__ == "__main__":
    unittest.main()
