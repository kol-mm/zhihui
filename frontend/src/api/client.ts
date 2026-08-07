import axios from 'axios';

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || (import.meta.env.DEV ? 'http://127.0.0.1:8080' : '/api'),
  timeout: 8000
});

export function resolveApiUrl(path: string): string {
  if (/^(https?:|data:|blob:)/i.test(path)) return path;
  if (!path.startsWith('/')) return path;
  return `${String(api.defaults.baseURL || '').replace(/\/$/, '')}${path}`;
}

const AUTH_TOKEN_KEY = 'ai-knowledge-local-token';
const CAPTCHA_CLIENT_KEY = 'ai-knowledge-captcha-client';
const GENERIC_ERROR_MESSAGE = '操作失败，请稍后重试';

class UserFacingError extends Error {}

function getCaptchaClientKey() {
  const existing = localStorage.getItem(CAPTCHA_CLIENT_KEY);
  if (existing) return existing;
  const generated = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `captcha-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  localStorage.setItem(CAPTCHA_CLIENT_KEY, generated);
  return generated;
}

export function setAuthToken(token: string) {
  localStorage.setItem(AUTH_TOKEN_KEY, token);
}

export function getAuthToken() {
  return localStorage.getItem(AUTH_TOKEN_KEY) || '';
}

api.interceptors.request.use((config) => {
  const token = getAuthToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  if (String(config.url || '').includes('/user/captcha')) {
    config.headers['X-Captcha-Client'] = getCaptchaClientKey();
  }
  return config;
});

function normalizeApiResponse<T>(payload: unknown): T {
  if (payload && typeof payload === 'object' && 'data' in payload) {
    const response = payload as { code?: number; message?: string; data: T };
    if (typeof response.code === 'number' && response.code !== 0) {
      throw new UserFacingError(localizeBackendMessage(response.message || ''));
    }
    return response.data;
  }

  throw new UserFacingError('服务返回异常，请稍后重试');
}

function toReadableError(error: unknown): Error {
  if (error instanceof UserFacingError) return error;

  if (axios.isAxiosError(error)) {
    const status = error.response?.status;
    const body = error.response?.data as unknown;
    const backendMessage =
      body && typeof body === 'object' && 'message' in body
        ? String((body as { message?: unknown }).message)
        : typeof body === 'string'
          ? body
          : '';

    if (backendMessage.trim()) return new UserFacingError(localizeBackendMessage(backendMessage));
    if (status === 401) return new UserFacingError('登录状态已失效，请重新登录');
    if (status === 403) return new UserFacingError('当前账号没有执行此操作的权限');
    if (status === 404) return new UserFacingError('请求的内容不存在或已被删除');
    if (!error.response) return new UserFacingError('暂时无法连接到服务，请稍后重试');
    return new UserFacingError(GENERIC_ERROR_MESSAGE);
  }

  return new UserFacingError(GENERIC_ERROR_MESSAGE);
}

function localizeBackendMessage(message: string): string {
  const text = message.trim();
  const exact: Record<string, string> = {
    'captcha is required or invalid; please obtain a new captcha': '验证码错误或已失效，请在倒计时结束后重新获取',
    'valid user authorization is required': '请先登录后再操作',
    'admin authorization is required': '需要管理员权限',
    'internal authorization is required': GENERIC_ERROR_MESSAGE,
    'user not found': '用户不存在',
    'post not found': '帖子不存在或暂不可查看',
    'knowledge file not found': '知识文件不存在',
    'chat session not found': '私信会话不存在',
    'notification not found': '通知不存在',
    'ticket not found': '反馈工单不存在',
    'report not found': '举报记录不存在',
    'draft not found': '草稿不存在',
    'file is required': '请选择文件',
    'comment content is required': '请输入评论内容',
    'message content is required': '请输入消息内容',
    'category name is required': '请输入分类名称',
    'community feature is disabled': '社区功能已关闭',
    'notifications feature is disabled': '通知功能已关闭',
    'access to this user is denied': '无权访问该用户数据',
    'access to this post is denied': '无权访问该帖子',
    'access to this draft is denied': '无权访问该草稿',
    'invalid post status': '帖子状态不正确',
    'unknown error': GENERIC_ERROR_MESSAGE
  };
  if (exact[text]) return exact[text];
  if (/^select between 1 and \d+ images$/i.test(text)) return '请选择规定数量的图片';
  if (text.startsWith('image upload failed:')) return '图片上传失败，请检查文件后重试';
  return isSafeChineseMessage(text) ? text : GENERIC_ERROR_MESSAGE;
}

function isSafeChineseMessage(message: string): boolean {
  if (!message || message.length > 160 || !/[\u3400-\u9fff]/.test(message)) return false;
  return !/(?:https?:\/\/|localhost|\b\d{1,3}(?:\.\d{1,3}){3}\b|[a-z]:[\\/]|[\\/](?:api|user|knowledge|post|message|ai)\b|exception|stack|trace|sql|database|table|com\.aiknowledge|org\.springframework|\r|\n)/i.test(message);
}

export function toUserMessage(error: unknown, fallback = GENERIC_ERROR_MESSAGE): string {
  if (error instanceof UserFacingError) return error.message;
  if (error instanceof Error && isSafeChineseMessage(error.message)) return error.message;
  if (typeof error === 'string' && isSafeChineseMessage(error)) return error;
  return fallback;
}

export async function getData<T>(url: string): Promise<T> {
  try {
    const response = await api.get(url);
    return normalizeApiResponse<T>(response.data);
  } catch (error) {
    throw toReadableError(error);
  }
}

export async function postData<T>(url: string, payload: unknown): Promise<T> {
  try {
    const response = await api.post(url, payload);
    return normalizeApiResponse<T>(response.data);
  } catch (error) {
    throw toReadableError(error);
  }
}

export async function postFormData<T>(url: string, payload: FormData): Promise<T> {
  try {
    const response = await api.post(url, payload, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 60000 });
    return normalizeApiResponse<T>(response.data);
  } catch (error) {
    throw toReadableError(error);
  }
}

export async function downloadData(url: string): Promise<Blob> {
  try {
    const response = await api.get(url, { responseType: 'blob', timeout: 60000 });
    return response.data as Blob;
  } catch (error) {
    throw toReadableError(error);
  }
}

export async function putData<T>(url: string, payload: unknown): Promise<T> {
  try {
    const response = await api.put(url, payload);
    return normalizeApiResponse<T>(response.data);
  } catch (error) {
    throw toReadableError(error);
  }
}

export async function deleteData<T>(url: string, payload?: unknown): Promise<T> {
  try {
    const response = await api.delete(url, { data: payload });
    return normalizeApiResponse<T>(response.data);
  } catch (error) {
    throw toReadableError(error);
  }
}
