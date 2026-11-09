import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { Button, ErrorBox, Field, Notice } from '../components/ui';
import { AuthHero } from './LoginPage';

export default function RegisterPage() {
  const [form, setForm] = useState({ username: '', email: '', fullName: '', password: '', confirm: '' });
  const [error, setError] = useState(null);
  const [done, setDone] = useState(null);
  const [busy, setBusy] = useState(false);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  const submit = async (e) => {
    e.preventDefault();
    if (form.password !== form.confirm) { setError({ message: 'Mật khẩu nhập lại không khớp' }); return; }
    setBusy(true);
    setError(null);
    try {
      const u = await api('/auth/register', { method: 'POST', body: {
        username: form.username.trim(), email: form.email.trim() || null, fullName: form.fullName.trim() || null, password: form.password,
      } });
      setDone(u);
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
          <h2>Đăng ký tài khoản</h2>
          {done ? (
            <>
              <Notice kind="success">Đã tạo tài khoản <b>{done.username}</b> với vai trò <b>Người xem</b>. Quản trị viên có thể nâng quyền.</Notice>
              <Link to="/login">Đăng nhập ngay →</Link>
            </>
          ) : (
            <>
              <ErrorBox error={error} />
              <Field label="Tên đăng nhập" hint="3-50 ký tự: chữ, số, '_' hoặc '.'" error={error?.fieldErrors?.username}>
                <input value={form.username} onChange={set('username')} required />
              </Field>
              <Field label="Họ tên"><input value={form.fullName} onChange={set('fullName')} /></Field>
              <Field label="Email" error={error?.fieldErrors?.email}><input type="email" value={form.email} onChange={set('email')} /></Field>
              <Field label="Mật khẩu" hint="Tối thiểu 8 ký tự, có chữ và số" error={error?.fieldErrors?.password}>
                <input type="password" value={form.password} onChange={set('password')} required />
              </Field>
              <Field label="Nhập lại mật khẩu"><input type="password" value={form.confirm} onChange={set('confirm')} required /></Field>
              <Button type="submit" disabled={busy}>{busy ? 'Đang tạo...' : 'Đăng ký'}</Button>
              <p className="small muted">Đã có tài khoản? <Link to="/login">Đăng nhập</Link></p>
            </>
          )}
        </form>
      </div>
    </div>
  );
}
