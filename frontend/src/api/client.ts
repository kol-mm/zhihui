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
  return config;
});

function normalizeApiResponse<T>(url: string, payload: unknown): T {
  if (payload && typeof payload === 'object' && 'data' in payload) {
    const response = payload as { code?: number; message?: string; data: T };
    if (typeof response.code === 'number' && response.code !== 0) {
      throw new Error(`接口 ${url} 返回失败：${localizeBackendMessage(response.message || '未知错误')}`);
    }
    return response.data;
  }

  throw new Error(`接口 ${url} 返回格式不符合预期`);
}

function toReadableError(url: string, error: unknown): Error {
  if (axios.isAxiosError(error)) {
    const status = error.response?.status;
    const body = error.response?.data as unknown;
    const backendMessage =
      body && typeof body === 'object' && 'message' in body
        ? String((body as { message?: unknown }).message)
        : typeof body === 'string'
          ? body
          : error.message;

    return new Error(`接口 ${url} 请求失败${status ? `，HTTP ${status}` : ''}：${localizeBackendMessage(backendMessage)}`);
  }

  return error instanceof Error ? error : new Error(String(error));
}

function localizeBackendMessage(message: string): string {
  const text = message.trim();
  const exact: Record<string, string> = {
    'valid user authorization is required': '请先登录后再操作',
    'admin authorization is required': '需要管理员权限',
    'internal authorization is required': '内部服务认证失败',
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
    'unknown error': '未知错误'
  };
  if (exact[text]) return exact[text];
  if (/^select between 1 and \d+ images$/i.test(text)) return '请选择规定数量的图片';
  if (text.startsWith('image upload failed:')) return `图片上传失败：${text.slice('image upload failed:'.length).trim()}`;
  return text;
}

export async function getData<T>(url: string): Promise<T> {
  try {
    const response = await api.get(url);
    return normalizeApiResponse<T>(url, response.data);
  } catch (error) {
    throw toReadableError(url, error);
  }
}

export async function postData<T>(url: string, payload: unknown): Promise<T> {
  try {
    const response = await api.post(url, payload);
    return normalizeApiResponse<T>(url, response.data);
  } catch (error) {
    throw toReadableError(url, error);
  }
}

export async function postFormData<T>(url: string, payload: FormData): Promise<T> {
  try {
    const response = await api.post(url, payload, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 60000 });
    return normalizeApiResponse<T>(url, response.data);
  } catch (error) {
    throw toReadableError(url, error);
  }
}

export async function downloadData(url: string): Promise<Blob> {
  try {
    const response = await api.get(url, { responseType: 'blob', timeout: 60000 });
    return response.data as Blob;
  } catch (error) {
    throw toReadableError(url, error);
  }
}

export async function putData<T>(url: string, payload: unknown): Promise<T> {
  try {
    const response = await api.put(url, payload);
    return normalizeApiResponse<T>(url, response.data);
  } catch (error) {
    throw toReadableError(url, error);
  }
}

export async function deleteData<T>(url: string, payload?: unknown): Promise<T> {
  try {
    const response = await api.delete(url, { data: payload });
    return normalizeApiResponse<T>(url, response.data);
  } catch (error) {
    throw toReadableError(url, error);
  }
}
