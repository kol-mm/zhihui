/** What ai-service reports about the knowledge chunks' vectors (embeddings.status). */
export type EmbeddingStatus = {
  model?: string;
  semantic?: boolean;
  total?: number;
  embedded?: number;
  pending?: number;
  last_error?: string | null;
};

/**
 * One line for the settings page: which vectors retrieval uses and, with an embedding model, how far the
 * background worker has got. Semantic retrieval covers only the chunks already described by the model, so the
 * page says how many are still waiting rather than implying all of them are searchable by meaning.
 */
export function embeddingStatusLine(status: EmbeddingStatus | null | undefined): string {
  const data = status ?? {};
  if (!data.semantic || !data.model) return '未配置向量模型：按关键词匹配知识片段';
  const total = Math.max(0, data.total ?? 0);
  const embedded = Math.min(total, Math.max(0, data.embedded ?? 0));
  const pending = Math.max(0, data.pending ?? total - embedded);
  if (total === 0) return `向量模型 ${data.model}：暂无知识片段`;
  if (pending === 0) return `向量模型 ${data.model}：全部 ${total} 个片段已可按语义检索`;
  return `向量模型 ${data.model}：已处理 ${embedded}/${total} 个片段，其余 ${pending} 个正在后台生成向量，暂按关键词匹配`;
}

/** The worker's last failure, when there is one to show. */
export function embeddingProblem(status: EmbeddingStatus | null | undefined): string {
  return status?.semantic && status.last_error ? `向量生成失败：${status.last_error}（稍后自动重试）` : '';
}
