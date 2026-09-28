import { describe, expect, it } from 'vitest';
import { backendMessage, GENERIC_ERROR_MESSAGE, INVALID_INPUT_MESSAGE, localizeBackendMessage } from './backendErrors';

describe('reading a failed response', () => {
  it('takes the Java services\' message', () => {
    expect(backendMessage({ code: 500, message: 'post not found', data: null })).toBe('post not found');
  });

  it('takes ai-service\'s detail, which is where FastAPI puts its errors', () => {
    expect(backendMessage({ detail: 'AI 问答功能当前未开放' })).toBe('AI 问答功能当前未开放');
    expect(backendMessage({ detail: '需要超级管理员权限' })).toBe('需要超级管理员权限');
  });

  it('prefers a message to a detail, and ignores an empty message', () => {
    expect(backendMessage({ message: '帖子正文不能为空', detail: 'other' })).toBe('帖子正文不能为空');
    expect(backendMessage({ message: '  ', detail: '无权访问' })).toBe('无权访问');
  });

  it('reads a plain-text body, and nothing from a body without words', () => {
    expect(backendMessage('too many requests')).toBe('too many requests');
    expect(backendMessage(undefined)).toBe('');
    expect(backendMessage({ data: {} })).toBe('');
    expect(backendMessage({ detail: { nested: true } })).toBe('');
  });

  it('turns a validation failure into words about the field', () => {
    const tooLong = [{ loc: ['body', 'question'], type: 'string_too_long', ctx: { max_length: 4000 } }];
    expect(backendMessage({ detail: tooLong })).toBe('问题不能超过 4000 个字符');
    expect(backendMessage({ detail: [{ loc: ['body', 'title'], type: 'missing' }] })).toBe('请填写标题');
    expect(backendMessage({ detail: [{ loc: ['body', 'platform_name'], type: 'string_too_short' }] })).toBe('请填写平台名称');
    expect(backendMessage({ detail: [{ loc: ['body', 'name'], type: 'string_type' }] })).toBe('名称格式不正确');
  });

  it('falls back to a general sentence for fields the page has no name for', () => {
    expect(backendMessage({ detail: [{ loc: ['body', 'selected_file_ids', 0], type: 'int_parsing' }] })).toBe(INVALID_INPUT_MESSAGE);
    expect(backendMessage({ detail: [] })).toBe(INVALID_INPUT_MESSAGE);
    expect(backendMessage({ detail: [{ type: 'missing' }] })).toBe(INVALID_INPUT_MESSAGE);
  });
});

describe('what the page may show', () => {
  it('translates ai-service\'s English refusals', () => {
    expect(localizeBackendMessage('AI session not found')).toBe('AI 会话不存在或已被删除');
    expect(localizeBackendMessage('private AI upstream addresses are disabled')).toBe('不能使用内网或本机地址作为模型接口');
    expect(localizeBackendMessage('AI request URL must use https')).toBe('接口地址必须使用 https');
  });

  it('shows Chinese as written, and hides anything that could leak internals', () => {
    expect(localizeBackendMessage('帖子标题不能为空')).toBe('帖子标题不能为空');
    expect(localizeBackendMessage('SQL error near table user')).toBe(GENERIC_ERROR_MESSAGE);
    expect(localizeBackendMessage('连接 http://10.0.0.5 失败')).toBe(GENERIC_ERROR_MESSAGE);
  });
});
