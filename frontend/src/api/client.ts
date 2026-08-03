import axios from 'axios';

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://127.0.0.1:8080',
  timeout: 8000
});

function normalizeApiResponse<T>(url: string, payload: unknown): T {
  if (payload && typeof payload === 'object' && 'data' in payload) {
    return (payload as { data: T }).data;
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
