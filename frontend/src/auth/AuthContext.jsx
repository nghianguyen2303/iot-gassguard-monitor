import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { api, getToken, setToken, setUnauthorizedHandler } from '../api/client';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);
  const [sessionMessage, setSessionMessage] = useState(null);

  const clear = useCallback((message) => {
    setToken(null);
    setUser(null);
    if (message) setSessionMessage(message);
  }, []);

  useEffect(() => {
    setUnauthorizedHandler((msg) => clear(`Phiên đăng nhập đã kết thúc: ${msg || 'token không còn hiệu lực'}`));
    if (!getToken()) { setLoading(false); return; }
    api('/auth/me').then(setUser).catch(() => setToken(null)).finally(() => setLoading(false));
  }, [clear]);

  const login = useCallback(async (username, password) => {
    const res = await api('/auth/login', { method: 'POST', body: { username, password } });
    setToken(res.token);
    setSessionMessage(null);
    setUser(res.user);
    return res.user;
  }, []);

  const logout = useCallback(async () => {
    try { await api('/auth/logout', { method: 'POST' }); } catch { /* vẫn xóa phiên ở trình duyệt */ }
    clear();
  }, [clear]);

  const refreshMe = useCallback(async () => setUser(await api('/auth/me')), []);

  const value = useMemo(() => ({
    user, loading, login, logout, clear, refreshMe, sessionMessage, setSessionMessage,
    isAdmin: user?.role === 'ADMIN',
    canCreateDevice: user?.role === 'ADMIN' || user?.role === 'OWNER',
  }), [user, loading, login, logout, clear, refreshMe, sessionMessage]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
