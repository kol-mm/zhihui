import axios from 'axios';

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://127.0.0.1:8080',
  timeout: 8000
});

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
      throw new Error(`接口 ${url} 返回失败：${response.message || 'unknown error'}`);
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

    return new Error(`接口 ${url} 请求失败${status ? `，HTTP ${status}` : ''}：${backendMessage}`);
  }

  return error instanceof Error ? error : new Error(String(error));
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
