import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client';
import { Badge, Button, Card, Empty, ErrorBox, Modal, Notice } from '../../components/ui';
import { fmtDateTime } from '../../utils/format';

/** Cấp / đổi thông tin xác thực MQTT riêng cho thiết bị (mục 4.4b-2, 4.5). */
export default function CredentialsTab({ device, reload }) {
  const code = device.deviceCode;
  const [history, setHistory] = useState(null);
  const [secret, setSecret] = useState(null);
  const [confirmRotate, setConfirmRotate] = useState(false);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => api(`/devices/${code}/credentials`).then(setHistory).catch(setError), [code]);
  useEffect(() => { load(); }, [load]);

  const issue = async (kind) => {
    setBusy(true);
    setError(null);
    try {
      const r = await api(kind === 'provision' ? `/devices/${code}/provision` : `/devices/${code}/credentials/rotate`, { method: 'POST' });
      setSecret(r);
      setConfirmRotate(false);
      load();
      reload();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  const canProvision = device.allowedActions.includes('PROVISION');
  const canRotate = device.allowedActions.includes('ROTATE_CREDENTIALS');
  // mqttUrl dạng ssl://IP:8883 (MQTT qua TLS) hoặc tcp://IP:1883 (không mã hóa)
  const m = secret?.mqttUrl?.match(/^(\w+):\/\/([^:/]+)(?::(\d+))?/) || [];
  const tls = ['ssl', 'mqtts', 'tls'].includes((m[1] || '').toLowerCase());
  const snippet = secret ? `// secrets.h – KHÔNG commit file này lên GitHub
#define MQTT_HOST      "${m[2] || ''}"
#define MQTT_PORT      ${m[3] || (tls ? 8883 : 1883)}
#define MQTT_USE_TLS   ${tls ? 1 : 0}            // 1 = mã hóa TLS, cần firmware/gas-node/ca_cert.h
#define DEVICE_ID      "${secret.deviceId}"   // = MQTT username = clientId
#define MQTT_PASSWORD  "${secret.password}"` : '';

  return (
    <div className="stack">
      <Card title="Thông tin xác thực MQTT">
        <ErrorBox error={error} />
        <p>Mỗi thiết bị có <b>tài khoản MQTT riêng</b> (username = clientId = <code>{code}</code>) và chỉ được publish/subscribe
           các topic <code>iot/v1/devices/{code}/…</code> của chính nó. Mật khẩu chỉ hiển thị <b>một lần</b>; hệ thống chỉ lưu SHA-256.</p>
        <div className="row">
          {canProvision && <Button disabled={busy} onClick={() => issue('provision')}>Cấp thông tin xác thực</Button>}
          {canRotate && <Button variant="warning" disabled={busy} onClick={() => setConfirmRotate(true)}>Cấp mật khẩu mới (thu hồi mật khẩu cũ)</Button>}
          {!canProvision && !canRotate && <span className="muted">Thiết bị đã ngừng sử dụng – không thể cấp thông tin xác thực.</span>}
        </div>
      </Card>
      <Card title="Lịch sử cấp / thu hồi">
        {!history ? null : history.length === 0 ? <Empty>Chưa cấp thông tin xác thực</Empty> : (
          <div className="table-wrap"><table>
            <thead><tr><th>Cấp lúc</th><th>Username</th><th>Dấu vân tay</th><th>Trạng thái</th><th>Thu hồi lúc</th><th>Lý do</th></tr></thead>
            <tbody>{history.map((c) => (
              <tr key={c.id}><td className="small">{fmtDateTime(c.issuedAt)}</td><td className="mono">{c.mqttUsername}</td>
                <td className="mono">{c.fingerprint}…</td><td><Badge value={c.status} /></td>
                <td className="small">{fmtDateTime(c.revokedAt)}</td><td className="small">{c.revokedReason || '—'}</td></tr>
            ))}</tbody>
          </table></div>
        )}
      </Card>
      {confirmRotate && (
        <Modal title="Cấp mật khẩu mới" onClose={() => setConfirmRotate(false)}
               footer={<><Button variant="ghost" onClick={() => setConfirmRotate(false)}>Hủy</Button><Button variant="warning" disabled={busy} onClick={() => issue('rotate')}>Cấp mới</Button></>}>
          <Notice kind="warning">Mật khẩu cũ bị thu hồi ngay; thiết bị đang kết nối sẽ bị ngắt và phải nạp mật khẩu mới.</Notice>
        </Modal>
      )}
      {secret && (
        <Modal wide title="Thông tin xác thực – chỉ hiển thị MỘT LẦN" onClose={() => setSecret(null)}
               footer={<Button onClick={() => setSecret(null)}>Tôi đã lưu lại</Button>}>
          <Notice kind="warning">{secret.note}</Notice>
          <dl className="kv">
            <dt>Broker</dt><dd className="mono">{secret.mqttUrl}{' '}<Badge value={tls ? 'SUCCESS' : 'WARNING'} label={tls ? 'Mã hóa TLS' : 'Không mã hóa'} /></dd>
            <dt>Username / clientId</dt><dd className="mono">{secret.username}</dd>
            <dt>Mật khẩu</dt><dd className="mono"><b>{secret.password}</b>{' '}
              <Button small variant="ghost" onClick={() => navigator.clipboard?.writeText(secret.password)}>Sao chép</Button></dd>
          </dl>
          <p className="small muted">Nội dung cho firmware ESP32:</p>
          <div className="secret">{snippet}</div>
          <div className="small muted">Topic: {Object.entries(secret.topics).map(([k, v]) => <div key={k}><code>{k}</code>: <code>{v}</code></div>)}</div>
        </Modal>
      )}
    </div>
  );
}
