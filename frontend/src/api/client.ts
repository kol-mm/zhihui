import axios from 'axios';
import { backendMessage, GENERIC_ERROR_MESSAGE, isSafeChineseMessage, localizeBackendMessage } from '../utils/backendErrors';

// The session lives in an httpOnly cookie that scripts cannot read. The gateway only honours it for writes that
// carry X-Requested-With, which other sites' forms cannot send.
export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || (import.meta.env.DEV ? 'http://127.0.0.1:8080' : '/api'),
  timeout: 8000,
  withCredentials: true,
  headers: { 'X-Requested-With': 'XMLHttpRequest' }
});

export function resolveApiUrl(path: string): string {
  if (/^(https?:|data:|blob:)/i.test(path)) return path;
  if (!path.startsWith('/')) return path;
  return `${String(api.defaults.baseURL || '').replace(/\/$/, '')}${path}`;
}

/** Where earlier versions kept the session token; only read to move a signed-in visitor onto the cookie. */
const LEGACY_AUTH_TOKEN_KEY = 'ai-knowledge-local-token';
const CAPTCHA_CLIENT_KEY = 'ai-knowledge-captcha-client';
export const TIMEOUT_MESSAGE = '服务响应超时，操作可能已经完成，请刷新后确认';

/**
 * How long to wait for a write that may be held for AI review before it answers — publishing a post, editing
 * one, publishing a draft. Every other request keeps the 8-second default.
 *
 * The waits nest: ai-service gives the model AI_REVIEW_TIMEOUT_SECONDS (8 by default), the Java service waits
 * two seconds longer for ai-service, and the page must wait longer than both. With the page's default 8 seconds
 * it gave up first: a slow review looked like a failed post while the post was in fact saved, and publishing
 * again made a second one.
 */
export const REVIEWED_WRITE_TIMEOUT_MS = 30_000;

type RequestOptions = { timeout?: number };
const volatileStorage = new Map<string, string>();
const removedStorageKeys = new Set<string>();

class UserFacingError extends Error {}
/** The gateway ended the session; the page already tells the visitor once, so callers need not repeat it. */
class SessionExpiredError extends UserFacingError {}

export function isSessionExpiredError(error: unknown): boolean {
  return error instanceof SessionExpiredError;
}

export function getStoredValue(key: string, fallback = ''): string {
  if (removedStorageKeys.has(key)) return fallback;
  const volatileValue = volatileStorage.get(key);
  if (volatileValue !== undefined) return volatileValue;
  try {
    return localStorage.getItem(key) ?? fallback;
  } catch {
    return fallback;
  }
}

export function setStoredValue(key: string, value: string): void {
  removedStorageKeys.delete(key);
  volatileStorage.set(key, value);
  try {
    localStorage.setItem(key, value);
  } catch {
    // 严格隐私模式下仅在当前页面会话内保存。
  }
}

export function removeStoredValue(key: string): void {
  volatileStorage.delete(key);
  removedStorageKeys.add(key);
  try {
    localStorage.removeItem(key);
  } catch {
    // 持久存储不可用时，内存状态已经清理。
  }
}

function getCaptchaClientKey() {
  const existing = getStoredValue(CAPTCHA_CLIENT_KEY);
  if (existing) return existing;
  const generated = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `captcha-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  setStoredValue(CAPTCHA_CLIENT_KEY, generated);
  return generated;
}

export function getLegacyAuthToken() {
  return getStoredValue(LEGACY_AUTH_TOKEN_KEY);
}

export function clearLegacyAuthToken() {
  removeStoredValue(LEGACY_AUTH_TOKEN_KEY);
}

let sessionExpiredHandler: (() => void) | undefined;

/** Called when the gateway reports that the session was ended elsewhere (logout, password change, suspension). */
export function onSessionExpired(handler: () => void) {
  sessionExpiredHandler = handler;
}

api.interceptors.request.use((config) => {
  // Until restoreSession moves it into the cookie, a stored token still signs requests.
  const legacyToken = getLegacyAuthToken();
  if (legacyToken) {
    config.headers.Authorization = `Bearer ${legacyToken}`;
  }
  if (/\/user\/(captcha|login|register|password-reset\/(?:request|complete))(?:$|[?#])/i.test(String(config.url || ''))) {
    config.headers['X-Captcha-Client'] = getCaptchaClientKey();
  }
  return config;
});

api.interceptors.response.use(undefined, (error) => {
  if (axios.isAxiosError(error) && error.response?.status === 401 && !/\/user\/logout(?:$|[?#])/.test(String(error.config?.url || ''))) {
    sessionExpiredHandler?.();
  }
  return Promise.reject(error);
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
    const message = backendMessage(error.response?.data);

    if (status === 401) return new SessionExpiredError(message.trim() ? localizeBackendMessage(message) : '登录状态已失效，请重新登录');
    if (message.trim()) return new UserFacingError(localizeBackendMessage(message));
    if (status === 403) return new UserFacingError('当前账号没有执行此操作的权限');
    if (status === 404) return new UserFacingError('请求的内容不存在或已被删除');
    // A timeout is not a failed connection: the service had the request and may well have carried it out.
    if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') return new UserFacingError(TIMEOUT_MESSAGE);
    if (!error.response) return new UserFacingError('暂时无法连接到服务，请稍后重试');
    return new UserFacingError(GENERIC_ERROR_MESSAGE);
  }

  return new UserFacingError(GENERIC_ERROR_MESSAGE);
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

export async function postData<T>(url: string, payload: unknown, options: RequestOptions = {}): Promise<T> {
  try {
    const response = await api.post(url, payload, options);
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

export async function putData<T>(url: string, payload: unknown, options: RequestOptions = {}): Promise<T> {
  try {
    const response = await api.put(url, payload, options);
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
