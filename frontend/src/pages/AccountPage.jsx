import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { Badge, Button, Card, ErrorBox, Field, Notice } from '../components/ui';
import { useToast } from '../components/Toast';
import { fmtDateTime } from '../utils/format';

/** Cập nhật thông tin tài khoản + đổi mật khẩu (mục 4.4a). */
export default function AccountPage() {
  const { user, refreshMe, clear, setSessionMessage } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();
  const [profile, setProfile] = useState({ fullName: user.fullName || '', email: user.email || '' });
  const [pw, setPw] = useState({ currentPassword: '', newPassword: '', confirm: '' });
  const [pErr, setPErr] = useState(null);
  const [wErr, setWErr] = useState(null);

  const saveProfile = async (e) => {
    e.preventDefault();
    setPErr(null);
    try {
      await api('/auth/me', { method: 'PUT', body: { fullName: profile.fullName || null, email: profile.email || null } });
      await refreshMe();
      toast('Đã cập nhật thông tin', 'success');
    } catch (err) { setPErr(err); }
  };
  const changePw = async (e) => {
    e.preventDefault();
    setWErr(null);
    if (pw.newPassword !== pw.confirm) { setWErr({ message: 'Mật khẩu mới nhập lại không khớp' }); return; }
    try {
      await api('/auth/password', { method: 'PUT', body: { currentPassword: pw.currentPassword, newPassword: pw.newPassword } });
      clear();
      setSessionMessage('Đã đổi mật khẩu. Mọi phiên cũ đã bị thu hồi – hãy đăng nhập lại bằng mật khẩu mới.');
      navigate('/login');
    } catch (err) { setWErr(err); }
  };

  return (
    <div className="stack">
      <div className="page-head"><div><h1>Tài khoản</h1></div></div>
      <div className="grid-2">
        <Card title="Thông tin">
          <dl className="kv" style={{ marginBottom: 16 }}>
            <dt>Tên đăng nhập</dt><dd><b>{user.username}</b></dd>
            <dt>Vai trò</dt><dd><Badge value={user.role} /></dd>
            <dt>Trạng thái</dt><dd><Badge value={user.status} /></dd>
            <dt>Đăng nhập gần nhất</dt><dd>{fmtDateTime(user.lastLoginAt)}</dd>
          </dl>
          <form onSubmit={saveProfile}>
            <ErrorBox error={pErr} />
            <Field label="Họ tên"><input value={profile.fullName} onChange={(e) => setProfile({ ...profile, fullName: e.target.value })} /></Field>
            <Field label="Email" error={pErr?.fieldErrors?.email}><input type="email" value={profile.email} onChange={(e) => setProfile({ ...profile, email: e.target.value })} /></Field>
            <Button type="submit">Lưu thông tin</Button>
          </form>
        </Card>
        <Card title="Đổi mật khẩu">
          <form onSubmit={changePw}>
            <ErrorBox error={wErr} />
            <Field label="Mật khẩu hiện tại"><input type="password" value={pw.currentPassword} onChange={(e) => setPw({ ...pw, currentPassword: e.target.value })} required /></Field>
            <Field label="Mật khẩu mới" hint="Tối thiểu 8 ký tự, có chữ và số" error={wErr?.fieldErrors?.newPassword}>
              <input type="password" value={pw.newPassword} onChange={(e) => setPw({ ...pw, newPassword: e.target.value })} required />
            </Field>
            <Field label="Nhập lại mật khẩu mới"><input type="password" value={pw.confirm} onChange={(e) => setPw({ ...pw, confirm: e.target.value })} required /></Field>
            <Button type="submit">Đổi mật khẩu</Button>
            <p className="small muted">Sau khi đổi, mọi phiên đăng nhập (kể cả trên máy khác) bị thu hồi.</p>
          </form>
        </Card>
      </div>
      <EmailAlertCard />
      <TelegramCard />
    </div>
  );
}

/**
 * Liên kết Telegram: backend tạo mã dùng 1 lần, người dùng mở t.me/<bot>?start=<mã> và bấm Start.
 * Không nhập chat id bằng tay -> chỉ chủ tài khoản Telegram mới liên kết được.
 */
function TelegramCard() {
  const toast = useToast();
  const [st, setSt] = useState(null);
  const [link, setLink] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => api('/auth/me/telegram').then((d) => { setSt(d); if (d.linked) setLink(null); }).catch(setError), []);
  useEffect(() => { load(); }, [load]);
  // Đang chờ người dùng bấm Start trong Telegram -> hỏi lại mỗi 3 giây
  useEffect(() => {
    if (!link) return undefined;
    const t = setInterval(load, 3000);
    return () => clearInterval(t);
  }, [link, load]);
  useEffect(() => { if (st?.linked && link) toast('Đã liên kết Telegram', 'success'); }, [st?.linked]); // eslint-disable-line

  const run = async (fn) => {
    setBusy(true);
    setError(null);
    try { await fn(); } catch (e) { setError(e); } finally { setBusy(false); }
  };

  if (st && !st.botConfigured) return null;  // Telegram là kênh tùy chọn: chưa cấu hình bot thì không hiện
  return (
    <Card title="Nhận cảnh báo qua Telegram">
      <ErrorBox error={error} />
      {!st ? null : st.linked ? (
        <>
          <p>Trạng thái: <Badge value="ACTIVE" label="Đã liên kết" /> {st.botUsername && <>với bot <b>@{st.botUsername}</b></>} lúc {fmtDateTime(st.linkedAt)}.</p>
          <p className="small muted">Bạn nhận tin khi có cảnh báo mới, cảnh báo tái diễn, trở lại bình thường và khi được xử lý xong – chỉ với các thiết bị bạn có quyền xem.</p>
          <div className="row">
            <Button disabled={busy} onClick={() => run(async () => { await api('/auth/me/telegram/test', { method: 'POST' }); toast('Đã gửi tin nhắn thử, kiểm tra Telegram', 'success'); })}>Gửi tin nhắn thử</Button>
            <Button variant="danger" disabled={busy} onClick={() => run(async () => { await api('/auth/me/telegram', { method: 'DELETE' }); toast('Đã hủy liên kết', 'info'); load(); })}>Hủy liên kết</Button>
          </div>
        </>
      ) : link ? (
        <>
          <ol style={{ paddingLeft: 18, marginTop: 0 }}>
            <li>Bấm nút dưới đây để mở Telegram (điện thoại hoặc máy tính đã cài Telegram).</li>
            <li>Trong cuộc trò chuyện với <b>@{link.botUsername}</b>, bấm <b>Start / Bắt đầu</b>.</li>
            <li>Trang này tự chuyển sang “Đã liên kết” sau vài giây.</li>
          </ol>
          <div className="row">
            <a className="btn btn-primary" href={link.deepLink} target="_blank" rel="noreferrer">Mở Telegram</a>
            <span className="small muted">Hoặc gửi cho bot tin nhắn: <code>/start {link.code}</code> · mã hết hạn lúc {fmtDateTime(link.expiresAt)}</span>
          </div>
        </>
      ) : (
        <>
          <p>Chưa liên kết. Liên kết để nhận cảnh báo rò rỉ gas, quá nhiệt, mất kết nối ngay trên điện thoại.</p>
          <Button disabled={busy} onClick={() => run(async () => setLink(await api('/auth/me/telegram/link', { method: 'POST' })))}>Liên kết Telegram</Button>
        </>
      )}
    </Card>
  );
}

/**
 * Cảnh báo qua email: nhập email -> nhận mã 6 số -> nhập mã để xác nhận sở hữu hộp thư -> bật.
 * Chỉ gửi cảnh báo của các thiết bị người dùng có quyền xem.
 */
function EmailAlertCard() {
  const toast = useToast();
  const [st, setSt] = useState(null);
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [sent, setSent] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => api('/auth/me/email-alerts').then((d) => {
    setSt(d);
    setEmail((e) => e || d.pendingEmail || d.alertEmail || d.profileEmail || '');
    if (d.pendingEmail) setSent((x) => x || { sentTo: d.pendingEmail });
  }).catch(setError), []);
  useEffect(() => { load(); }, [load]);

  const run = async (fn) => {
    setBusy(true);
    setError(null);
    try { await fn(); } catch (e) { setError(e); } finally { setBusy(false); }
  };
  const requestCode = (e) => {
    e.preventDefault();
    run(async () => {
      setSent(await api('/auth/me/email-alerts/request', { method: 'POST', body: { email } }));
      setCode('');
      toast('Đã gửi mã xác nhận, kiểm tra hộp thư (cả mục Spam)', 'info');
    });
  };
  const confirm = (e) => {
    e.preventDefault();
    run(async () => {
      setSt(await api('/auth/me/email-alerts/confirm', { method: 'POST', body: { code } }));
      setSent(null);
      toast('Đã bật nhận cảnh báo qua email', 'success');
    });
  };

  return (
    <Card title="Nhận cảnh báo qua email">
      <ErrorBox error={error} />
      {!st ? null : !st.configured ? (
        <Notice kind="info">Hệ thống chưa cấu hình email gửi. Quản trị viên cần đặt <code>MAIL_USERNAME</code> và <code>MAIL_PASSWORD</code> (Mật khẩu ứng dụng Gmail) trong file <code>.env</code> rồi chạy lại <code>app-up</code>.</Notice>
      ) : st.enabled ? (
        <>
          <p>Trạng thái: <Badge value="ACTIVE" label="Đang bật" /> gửi tới <b>{st.alertEmail}</b> (xác nhận lúc {fmtDateTime(st.verifiedAt)}).</p>
          <p className="small muted">Bạn nhận email khi cảnh báo mới, tái diễn, trở lại bình thường và khi được xử lý xong – chỉ với các thiết bị bạn có quyền xem.</p>
          <div className="row">
            <Button disabled={busy} onClick={() => run(async () => { await api('/auth/me/email-alerts/test', { method: 'POST' }); toast('Đã gửi email thử, kiểm tra hộp thư', 'success'); })}>Gửi email thử</Button>
            <Button variant="ghost" disabled={busy} onClick={() => setSt({ ...st, enabled: false })}>Đổi email</Button>
            <Button variant="danger" disabled={busy} onClick={() => run(async () => { await api('/auth/me/email-alerts', { method: 'DELETE' }); toast('Đã tắt email cảnh báo', 'info'); setSent(null); load(); })}>Tắt</Button>
          </div>
        </>
      ) : (
        <div className="grid-2">
          <form onSubmit={requestCode}>
            <Field label="1. Email nhận cảnh báo" hint="Hệ thống gửi mã 6 số tới email này để xác nhận bạn là chủ hộp thư.">
              <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required placeholder="ban@gmail.com" />
            </Field>
            <Button type="submit" disabled={busy || !email}>{sent ? 'Gửi lại mã' : 'Gửi mã xác nhận'}</Button>
          </form>
          <form onSubmit={confirm}>
            <Field label="2. Mã xác nhận" hint={sent ? `Đã gửi tới ${sent.sentTo}. Mã hết hạn sau 10 phút; không thấy thì xem mục Spam.` : 'Bấm “Gửi mã xác nhận” trước.'}>
              <input value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))} inputMode="numeric" placeholder="6 chữ số" disabled={!sent} />
            </Field>
            <Button type="submit" variant="success" disabled={busy || !sent || code.length !== 6}>Xác nhận và bật</Button>
          </form>
        </div>
      )}
    </Card>
  );
}
