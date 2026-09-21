import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { message } from 'antd';
import { STORAGE_KEYS } from '../constants';
import { fetchMe, login as loginApi } from '../api/auth';
import { clearAuth, setToken } from '../api/request';

const AuthContext = createContext(null);

/**
 * 认证状态：token / 用户信息 / login / logout / hasRole。
 */
export function AuthProvider({ children }) {
  const [token, setTokenState] = useState(() => localStorage.getItem(STORAGE_KEYS.token) || '');
  const [user, setUser] = useState(() => {
    const raw = localStorage.getItem(STORAGE_KEYS.user);
    if (!raw) {
      return null;
    }
    try {
      return JSON.parse(raw);
    } catch (e) {
      localStorage.removeItem(STORAGE_KEYS.user);
      return null;
    }
  });
  const [loading, setLoading] = useState(false);

  /** 持久化用户信息。 */
  const persistUser = useCallback((nextUser) => {
    setUser(nextUser);
    if (nextUser) {
      localStorage.setItem(STORAGE_KEYS.user, JSON.stringify(nextUser));
    } else {
      localStorage.removeItem(STORAGE_KEYS.user);
    }
  }, []);

  /** 登录：成功返回角色，失败抛错由页面处理。 */
  const login = useCallback(async (username, password) => {
    setLoading(true);
    try {
      const data = await loginApi({ username, password });
      setToken(data.token);
      setTokenState(data.token);
      persistUser(data.user);
      return data.user;
    } finally {
      setLoading(false);
    }
  }, [persistUser]);

  /** 退出登录。 */
  const logout = useCallback(() => {
    clearAuth();
    setTokenState('');
    persistUser(null);
  }, [persistUser]);

  /** 角色判断。 */
  const hasRole = useCallback((role) => {
    if (!user) {
      return false;
    }
    return user.role === role;
  }, [user]);

  /** 启动后若有 token 但无用户信息，拉一次 /me。 */
  useEffect(() => {
    if (token && !user) {
      fetchMe()
        .then((info) => persistUser(info))
        .catch(() => {
          clearAuth();
          setTokenState('');
        });
    }
  }, [token, user, persistUser]);

  const value = useMemo(
    () => ({
      token,
      user,
      loading,
      login,
      logout,
      hasRole,
      isAuthenticated: Boolean(token),
    }),
    [token, user, loading, login, logout, hasRole],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

/**
 * 取认证上下文。
 *
 * @returns {object} 认证上下文
 */
export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth 必须在 AuthProvider 内使用');
  }
  return ctx;
}

/** 全局提示（避免多处重复 import antd message）。 */
export function toastError(text) {
  message.error(text);
}
