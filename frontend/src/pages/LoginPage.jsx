import { useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { Button, ErrorBox, Field, Notice } from '../components/ui';

export function AuthHero() {
  return (
    <div className="auth-hero">
      <h1>🔥 GasGuard</h1>
      <p>Hệ thống IoT giám sát rò rỉ khí gas và nhiệt độ cho nhà ở, bếp ăn, kho hàng.</p>
      <ul>
        <li>Theo dõi nồng độ gas (MQ-2) và nhiệt độ (DS18B20) theo thời gian thực</li>
        <li>Cảnh báo tức thì khi vượt ngưỡng, mất kết nối hoặc lỗi cảm biến</li>
        <li>Bật/tắt quạt hút từ xa, cấu hình ngưỡng tự động</li>
        <li>Quản lý vòng đời thiết bị và phân quyền người dùng</li>
      </ul>
    </div>
  );
}

export default function LoginPage() {
  const { login, user, sessionMessage } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [form, setForm] = useState({ username: '', password: '' });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  if (user) return <Navigate to="/" replace />;

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(form.username.trim(), form.password);
      navigate(location.state?.from || '/', { replace: true });
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="auth-page">
      <AuthHero />
      <div className="auth-form">
        <form className="auth-card" onSubmit={submit}>
          <h2>Đăng nhập</h2>
          {sessionMessage && <Notice kind="warning">{sessionMessage}</Notice>}
          <ErrorBox error={error} />
          <Field label="Tên đăng nhập">
            <input autoFocus value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} required />
          </Field>
          <Field label="Mật khẩu">
            <input type="password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} required />
          </Field>
          <Button type="submit" disabled={busy}>{busy ? 'Đang đăng nhập...' : 'Đăng nhập'}</Button>
          <p className="small muted">Chưa có tài khoản? <Link to="/register">Đăng ký</Link> (vai trò mặc định: Người xem)</p>
        </form>
      </div>
    </div>
  );
}
