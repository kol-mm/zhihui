package com.aiknowledge.knowledge.analysis;

import com.aiknowledge.knowledge.store.KnowledgeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Gets a summary and a category for uploaded documents, one at a time, in the background: an upload never waits
 * for the model. The suggested category is applied only when the document has none; a category chosen by the
 * uploader or an administrator is never replaced (see KnowledgeStore.saveAnalysis).
 */
public class KnowledgeAnalysisRunner {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeAnalysisRunner.class);
    /** Beyond this many waiting documents, new ones are skipped rather than queued without limit. */
    static final int QUEUE_LIMIT = 200;

    private final KnowledgeStore store;
    private final AiDocumentAnalysis analysis;
    private final ExecutorService executor;

    public KnowledgeAnalysisRunner(KnowledgeStore store, AiDocumentAnalysis analysis) {
        this(store, analysis, new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_LIMIT),
                runnable -> {
                    Thread thread = new Thread(runnable, "knowledge-analysis");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    KnowledgeAnalysisRunner(KnowledgeStore store, AiDocumentAnalysis analysis, ExecutorService executor) {
        this.store = store;
        this.analysis = analysis;
        this.executor = executor;
    }

    /** Queues the document; false when the queue is full and it was skipped. */
    public boolean submit(Long fileId, String title, String text) {
        if (fileId == null || text == null || text.isBlank()) return false;
        try {
            executor.execute(() -> run(fileId, title, text));
            return true;
        } catch (RejectedExecutionException full) {
            log.warn("Document analysis queue is full; file {} was not analysed", fileId);
            return false;
        }
    }

    /** One document, now. Package-private so tests need no threads. */
    void run(Long fileId, String title, String text) {
        List<AiDocumentAnalysis.Category> categories = store.listCategories().stream()
                .filter(category -> category.getId() != null && category.getName() != null)
                .map(category -> new AiDocumentAnalysis.Category(category.getId(), category.getName()))
                .toList();
        AiDocumentAnalysis.Result result = analysis.analyze(title, text, categories);
        if (!result.available()) return;
        store.saveAnalysis(fileId, result.summary(), result.categoryId());
    }
}
