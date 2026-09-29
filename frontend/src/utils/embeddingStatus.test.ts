import { describe, expect, it } from 'vitest';
import { embeddingProblem, embeddingStatusLine } from './embeddingStatus';

describe('the vector status line', () => {
  it('says plainly when retrieval is by keywords only', () => {
    expect(embeddingStatusLine({ semantic: false, total: 30 })).toBe('未配置向量模型：按关键词匹配知识片段');
    expect(embeddingStatusLine(undefined)).toBe('未配置向量模型：按关键词匹配知识片段');
  });

  it('shows the progress while chunks are still being described', () => {
    expect(embeddingStatusLine({ model: 'text-embedding-v3', semantic: true, total: 40, embedded: 10, pending: 30 }))
      .toBe('向量模型 text-embedding-v3：已处理 10/40 个片段，其余 30 个正在后台生成向量，暂按关键词匹配');
  });

  it('says when every chunk is searchable by meaning', () => {
    expect(embeddingStatusLine({ model: 'text-embedding-v3', semantic: true, total: 40, embedded: 40, pending: 0 }))
      .toBe('向量模型 text-embedding-v3：全部 40 个片段已可按语义检索');
  });

  it('shows a failure only while a model is in use', () => {
    expect(embeddingProblem({ semantic: true, last_error: '向量接口返回错误（HTTP 401）' }))
      .toBe('向量生成失败：向量接口返回错误（HTTP 401）（稍后自动重试）');
    expect(embeddingProblem({ semantic: false, last_error: 'old' })).toBe('');
    expect(embeddingProblem({ semantic: true, last_error: null })).toBe('');
  });
});
