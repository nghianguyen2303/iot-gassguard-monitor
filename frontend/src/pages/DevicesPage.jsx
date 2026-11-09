import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { Badge, Button, Card, Empty, ErrorBox, Field, Loading, Modal } from '../components/ui';
import { useDeviceEvents } from '../realtime/RealtimeContext';
import { fmtAgo } from '../utils/format';

/** Quản lý danh sách thiết bị (mục 4.6) + đăng ký/onboarding (mục 4.4b-1). */
export default function DevicesPage() {
  const { canCreateDevice, isAdmin } = useAuth();
  const [devices, setDevices] = useState(null);
  const [error, setError] = useState(null);
  const [q, setQ] = useState('');
  const [showOld, setShowOld] = useState(false);
  const [creating, setCreating] = useState(false);

  const load = useCallback(() => {
    api(`/devices?includeDecommissioned=${showOld}`).then(setDevices).catch(setError);
  }, [showOld]);
  useEffect(() => { load(); }, [load]);

  useDeviceEvents(devices?.map((d) => d.deviceCode) || [], (m) => {
    if (['connectivity', 'lifecycle', 'config'].includes(m.type)) load();
    if (m.type === 'telemetry') {
      setDevices((ds) => ds && ds.map((d) => d.deviceCode === m.deviceCode ? { ...d, lastSeenAt: m.receivedAt } : d));
    }
  });

  const filtered = useMemo(() => {
    const s = q.trim().toLowerCase();
    if (!devices) return [];
    return devices.filter((d) => !s || [d.deviceCode, d.name, d.location, d.zone, d.ownerUsername]
      .some((x) => x && x.toLowerCase().includes(s)));
  }, [devices, q]);

  return (
    <div className="stack">
      <div className="page-head">
        <div><h1>Thiết bị</h1><p>Danh sách thiết bị bạn sở hữu hoặc được chia sẻ quyền xem.</p></div>
        {canCreateDevice && <Button onClick={() => setCreating(true)}>+ Đăng ký thiết bị</Button>}
      </div>
      <ErrorBox error={error} />
      <Card>
        <div className="filters">
          <input placeholder="Tìm theo mã, tên, vị trí, chủ sở hữu..." value={q} onChange={(e) => setQ(e.target.value)} style={{ minWidth: 300 }} />
          <label className="check" style={{ margin: 0 }}>
            <input type="checkbox" checked={showOld} onChange={(e) => setShowOld(e.target.checked)} /> Hiện thiết bị đã ngừng sử dụng
          </label>
        </div>
        {!devices ? <Loading /> : filtered.length === 0 ? <Empty>Không có thiết bị</Empty> : (
          <div className="table-wrap"><table>
            <thead><tr>
              <th>Mã</th><th>Tên</th><th>Vị trí / khu vực</th><th>Vòng đời</th><th>Kết nối</th>
              <th>Lần cuối</th><th>Firmware</th><th>Cấu hình</th>{isAdmin && <th>Chủ sở hữu</th>}<th>Quyền</th>
            </tr></thead>
            <tbody>{filtered.map((d) => (
              <tr key={d.id}>
                <td><Link to={`/devices/${d.deviceCode}`}><b>{d.deviceCode}</b></Link></td>
                <td>{d.name}</td>
                <td>{[d.location, d.zone].filter(Boolean).join(' / ') || '—'}</td>
                <td><Badge value={d.lifecycleStatus} /></td>
                <td><Badge value={d.connectivity} /></td>
                <td className="small">{fmtAgo(d.lastSeenAt)}</td>
                <td className="small mono">{d.fwVersion || '—'}</td>
                <td className="small">v{d.configAppliedVersion}/{d.configVersion} <Badge value={d.configStatus} /></td>
                {isAdmin && <td>{d.ownerUsername}</td>}
                <td className="small">{d.canManage ? 'Quản lý' : 'Chỉ xem'}</td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </Card>
      {creating && <RegisterDeviceModal isAdmin={isAdmin} onClose={() => setCreating(false)} />}
    </div>
  );
}

function RegisterDeviceModal({ isAdmin, onClose }) {
  const navigate = useNavigate();
  const [form, setForm] = useState({ deviceCode: '', name: '', location: '', zone: '', hwVersion: '', deviceType: 'GAS_NODE', ownerUsername: '' });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const d = await api('/devices', { method: 'POST', body: { ...form, deviceCode: form.deviceCode.trim().toLowerCase(), ownerUsername: form.ownerUsername || null } });
      navigate(`/devices/${d.deviceCode}?tab=credentials`);
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };

  return (
    <Modal title="Đăng ký thiết bị mới" onClose={onClose}
           footer={<><Button variant="ghost" onClick={onClose}>Hủy</Button><Button form="reg-dev" type="submit" disabled={busy}>Đăng ký</Button></>}>
      <form id="reg-dev" onSubmit={submit}>
        <ErrorBox error={error} />
        <Field label="Mã thiết bị *" hint="Chữ thường, số, '-' (vd. esp32-01). Đây cũng là username/clientId MQTT, không đổi được."
               error={error?.fieldErrors?.deviceCode}>
          <input value={form.deviceCode} onChange={set('deviceCode')} required autoFocus />
        </Field>
        <Field label="Tên hiển thị *" error={error?.fieldErrors?.name}><input value={form.name} onChange={set('name')} required /></Field>
        <div className="grid-2">
          <Field label="Vị trí"><input value={form.location} onChange={set('location')} placeholder="vd. Nhà A" /></Field>
          <Field label="Khu vực"><input value={form.zone} onChange={set('zone')} placeholder="vd. Bếp" /></Field>
        </div>
        <div className="grid-2">
          <Field label="Loại thiết bị"><input value={form.deviceType} onChange={set('deviceType')} /></Field>
          <Field label="Phiên bản phần cứng"><input value={form.hwVersion} onChange={set('hwVersion')} placeholder="vd. rev-A" /></Field>
        </div>
        {isAdmin && (
          <Field label="Gán cho chủ sở hữu (username)" hint="Bỏ trống = chính bạn. Người này phải có vai trò Chủ thiết bị.">
            <input value={form.ownerUsername} onChange={set('ownerUsername')} />
          </Field>
        )}
        <p className="small muted">Sau khi đăng ký, thiết bị ở trạng thái <b>Đã đăng ký</b>. Bước tiếp theo: cấp thông tin xác thực MQTT.</p>
      </form>
    </Modal>
  );
}
