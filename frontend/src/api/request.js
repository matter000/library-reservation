import axios from 'axios';
import { message } from 'antd';
import { STORAGE_KEYS } from '../constants';

/**
 * axios 实例：baseURL=/api，请求自动带 Token，响应统一解析 {code, message, data}。
 */
const request = axios.create({
  baseURL: '/api',
  timeout: 20000,
  headers: { 'Content-Type': 'application/json' },
});

/** 取当前 Token。 */
export function getToken() {
  return localStorage.getItem(STORAGE_KEYS.token) || '';
}

/** 存 Token。 */
export function setToken(token) {
  localStorage.setItem(STORAGE_KEYS.token, token || '');
}

/** 清登录态。 */
export function clearAuth() {
  localStorage.removeItem(STORAGE_KEYS.token);
  localStorage.removeItem(STORAGE_KEYS.user);
}

/** 跳登录页（401 / 未登录）。 */
function redirectToLogin() {
  clearAuth();
  if (window.location.hash !== '#/login' && window.location.pathname !== '/login') {
    window.location.href = '/login';
  }
}

request.interceptors.request.use(
  (config) => {
    const token = getToken();
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error),
);

request.interceptors.response.use(
  (response) => {
    const body = response.data;
    // 非业务包装（如文件流）直接返回
    if (!body || typeof body.code === 'undefined') {
      return body;
    }
    if (body.code === 0) {
      return body.data;
    }
    const msg = body.message || '请求失败';
    if (body.code === 4010) {
      message.error(msg);
      redirectToLogin();
    } else if (body.code === 4030) {
      message.error('无权限访问');
    } else {
      message.error(msg);
    }
    // 把业务错误码带给调用方，便于区分 4091 等场景
    const error = new Error(msg);
    error.code = body.code;
    error.data = body.data;
    return Promise.reject(error);
  },
  (error) => {
    const status = error.response ? error.response.status : 0;
    const body = error.response ? error.response.data : null;
    const bizCode = body ? body.code : 0;
    const msg = (body && body.message) || error.message || '网络异常，请稍后重试';

    if (status === 401 || bizCode === 4010) {
      message.error(msg);
      redirectToLogin();
    } else if (status === 403 || bizCode === 4030) {
      message.error('无权限访问');
    } else {
      message.error(msg);
    }
    const wrapped = new Error(msg);
    wrapped.code = bizCode || status;
    wrapped.status = status;
    wrapped.data = body ? body.data : null;
    return Promise.reject(wrapped);
  },
);

export default request;
