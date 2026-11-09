import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { Badge, Button, Card, ErrorBox, Field, Loading, Modal } from '../components/ui';
import { useToast } from '../components/Toast';
import { fmtDateTime } from '../utils/format';

/** Quản lý người dùng theo quyền (mục 4.4a, 4.6) – chỉ ADMIN; backend trả 403 với vai trò khác. */
export default function UsersPage() {
  const { user: me } = useAuth();
  const toast = useToast();
  const [users, setUsers] = useState(null);
  const [error, setError] = useState(null);
  const [creating, setCreating] = useState(false);

  const load = useCallback(() => api('/admin/users').then(setUsers).catch(setError), []);
  useEffect(() => { load(); }, [load]);

  const act = async (fn, msg) => {
    setError(null);
    try { await fn(); toast(msg, 'success'); load(); } catch (e) { setError(e); }
  };

  return (
    <div className="stack">
      <div className="page-head">
        <div><h1>Người dùng</h1><p>Tạo tài khoản, phân vai trò, khóa / mở khóa. Đổi vai trò hoặc khóa sẽ thu hồi mọi phiên đăng nhập của người đó.</p></div>
        {users && <Button onClick={() => setCreating(true)}>+ Tạo người dùng</Button>}
      </div>
      <ErrorBox error={error} />
      {!users ? (!error && <Loading />) : (
        <Card>
          <div className="table-wrap"><table>
            <thead><tr><th>ID</th><th>Tên đăng nhập</th><th>Họ tên</th><th>Email</th><th>Vai trò</th><th>Trạng thái</th><th>Đăng nhập gần nhất</th><th>Tạo lúc</th><th></th></tr></thead>
            <tbody>{users.map((u) => {
              const self = u.id === me.id;
              const tempLocked = u.lockedUntil && new Date(u.lockedUntil) > new Date();
              return (
                <tr key={u.id}>
                  <td>{u.id}</td><td><b>{u.username}</b>{self && <span className="muted small"> (bạn)</span>}</td>
                  <td>{u.fullName || '—'}</td><td>{u.email || '—'}</td>
                  <td>{self ? <Badge value={u.role} /> : (
                    <select value={u.role} onChange={(e) => act(() => api(`/admin/users/${u.id}/role`, { method: 'PUT', body: { role: e.target.value } }), `Đã đổi vai trò ${u.username}`)}>
                      <option value="ADMIN">Quản trị viên</option><option value="OWNER">Chủ thiết bị</option><option value="VIEWER">Người xem</option>
                    </select>)}</td>
                  <td><Badge value={u.status} />{tempLocked && <div className="small" style={{ color: '#b45309' }}>Tạm khóa đến {fmtDateTime(u.lockedUntil)} (sai mật khẩu)</div>}</td>
                  <td className="small">{fmtDateTime(u.lastLoginAt)}</td><td className="small">{fmtDateTime(u.createdAt)}</td>
                  <td>{!self && (u.status === 'ACTIVE' && !tempLocked
                    ? <Button small variant="danger" onClick={() => act(() => api(`/admin/users/${u.id}/lock`, { method: 'POST' }), `Đã khóa ${u.username}`)}>Khóa</Button>
                    : <Button small variant="success" onClick={() => act(() => api(`/admin/users/${u.id}/unlock`, { method: 'POST' }), `Đã mở khóa ${u.username}`)}>Mở khóa</Button>)}</td>
                </tr>
              );
            })}</tbody>
          </table></div>
        </Card>
      )}
      {creating && <CreateUserModal onClose={() => setCreating(false)} onDone={() => { setCreating(false); load(); toast('Đã tạo người dùng', 'success'); }} />}
    </div>
  );
}

function CreateUserModal({ onClose, onDone }) {
  const [form, setForm] = useState({ username: '', fullName: '', email: '', password: '', role: 'OWNER' });
  const [error, setError] = useState(null);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });
  const submit = async (e) => {
    e.preventDefault();
    setError(null);
    try {
      await api('/admin/users', { method: 'POST', body: { ...form, email: form.email || null, fullName: form.fullName || null } });
      onDone();
    } catch (err) { setError(err); }
  };
  return (
    <Modal title="Tạo người dùng" onClose={onClose}
           footer={<><Button variant="ghost" onClick={onClose}>Hủy</Button><Button form="cu" type="submit">Tạo</Button></>}>
      <form id="cu" onSubmit={submit}>
        <ErrorBox error={error} />
        <Field label="Tên đăng nhập *" error={error?.fieldErrors?.username}><input value={form.username} onChange={set('username')} required autoFocus /></Field>
        <Field label="Họ tên"><input value={form.fullName} onChange={set('fullName')} /></Field>
        <Field label="Email" error={error?.fieldErrors?.email}><input type="email" value={form.email} onChange={set('email')} /></Field>
        <Field label="Mật khẩu *" hint="Tối thiểu 8 ký tự, có chữ và số" error={error?.fieldErrors?.password}>
          <input type="password" value={form.password} onChange={set('password')} required />
        </Field>
        <Field label="Vai trò">
          <select value={form.role} onChange={set('role')}>
            <option value="OWNER">Chủ thiết bị</option><option value="VIEWER">Người xem</option><option value="ADMIN">Quản trị viên</option>
          </select>
        </Field>
      </form>
    </Modal>
  );
}
