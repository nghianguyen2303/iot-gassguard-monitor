import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client';
import { Badge, Button, Card, Empty, ErrorBox, Notice } from '../../components/ui';
import { useToast } from '../../components/Toast';
import { useDeviceEvents } from '../../realtime/RealtimeContext';
import { fmtDateTime } from '../../utils/format';

const ACTION_LABEL = {
  FAN_SET: 'Quạt', FAN_AUTO: 'Quạt → tự động', BUZZER_SET: 'Còi', BUZZER_AUTO: 'Còi → tự động', PING: 'Ping',
};
const actionLabel = (a, p) => (ACTION_LABEL[a] || a) + (p && typeof p.on === 'boolean' ? (p.on ? ' BẬT' : ' TẮT') : '');

/**
 * Điều khiển thiết bị (mục 4.4e): gửi lệnh -> backend kiểm tra quyền -> MQTT -> thiết bị thực hiện
 * -> ACK -> lưu lịch sử. Kết quả và độ trễ lệnh hiển thị ngay qua realtime.
 */
export default function ControlTab({ device }) {
  const code = device.deviceCode;
  const toast = useToast();
  const [history, setHistory] = useState(null);
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState(null);
  const [last, setLast] = useState(null);

  const load = useCallback(() => api(`/devices/${code}/commands?limit=50`).then(setHistory).catch(setError), [code]);
  useEffect(() => { load(); }, [load]);

  useDeviceEvents([code], (m) => {
    if (m.type !== 'command') return;
    const c = m.command;
    setHistory((h) => {
      const list = h || [];
      const i = list.findIndex((x) => x.id === c.id);
      return i >= 0 ? list.map((x) => (x.id === c.id ? c : x)) : [c, ...list];
    });
    setLast((l) => (l && l.id === c.id ? c : l));
  });

  const send = async (action, params) => {
    setBusy(action + JSON.stringify(params || {}));
    setError(null);
    try {
      const c = await api(`/devices/${code}/commands?waitMs=5000`, { method: 'POST', body: { action, params } });
      setLast(c);
      const name = actionLabel(action, params);
      if (c.status === 'SUCCESS') toast(`${name}: thành công sau ${c.latencyMs} ms`, 'success');
      else if (c.status === 'SENT') toast(`${name}: đã gửi, đang chờ thiết bị phản hồi...`, 'info');
      else toast(`${name}: ${c.status} – ${c.resultMessage || ''}`, 'warning');
      load();
    } catch (e) {
      setError(e);
      load();
    } finally {
      setBusy(null);
    }
  };

  const controllable = ['ACTIVE', 'MAINTENANCE'].includes(device.lifecycleStatus);
  const online = device.connectivity !== 'OFFLINE';
  const st = device.actuatorState || {};
  const disabled = !device.canManage || !!busy;

  return (
    <div className="stack">
      {!device.canManage && <Notice kind="info">Bạn chỉ có quyền <b>xem</b> thiết bị này nên không thể gửi lệnh (backend sẽ từ chối với mã 403).</Notice>}
      {device.canManage && !controllable && <Notice kind="warning">Thiết bị đang <b>{device.lifecycleStatus}</b>, chỉ nhận lệnh khi Hoạt động hoặc Bảo trì.</Notice>}
      {device.canManage && controllable && !online && <Notice kind="warning">Thiết bị đang <b>mất kết nối</b>; lệnh sẽ bị từ chối (409) và được ghi vào lịch sử.</Notice>}
      <ErrorBox error={error} />

      <div className="grid-2">
        <ActuatorCard
          title="Quạt hút khí" value={st.fan} mode={st.fanMode} onText="BẬT" offText="TẮT"
          autoHelp="Tự bật khi gas vượt ngưỡng hoặc quá nhiệt (nếu cấu hình cho phép tự bật quạt), tự tắt sau khi hết báo động 60 giây."
          manualOffWarn="Quạt đang bị TẮT bằng tay: khi rò rỉ gas quạt sẽ KHÔNG tự bật cho đến khi bạn bật lại hoặc chọn Tự động."
          disabled={disabled} busy={busy}
          onOn={() => send('FAN_SET', { on: true })} onOff={() => send('FAN_SET', { on: false })} onAuto={() => send('FAN_AUTO')}
        />
        <ActuatorCard
          title="Còi báo động" value={st.buzzer} mode={st.buzzerMode} onText="ĐANG KÊU" offText="IM LẶNG"
          autoHelp="Tự kêu khi gas vượt ngưỡng hoặc quá nhiệt, tự im khi hết báo động."
          manualOffWarn="Còi đang bị TẮT bằng tay: khi rò rỉ gas còi sẽ KHÔNG kêu cho đến khi bạn bật lại hoặc chọn Tự động. Đèn đỏ, cảnh báo trên web và Telegram vẫn hoạt động."
          disabled={disabled} busy={busy}
          onOn={() => send('BUZZER_SET', { on: true })} onOff={() => send('BUZZER_SET', { on: false })} onAuto={() => send('BUZZER_AUTO')}
        />
      </div>

      <Card>
        <div className="row">
          <Button variant="ghost" disabled={disabled} onClick={() => send('PING')}>Ping thiết bị</Button>
          {busy && <span className="muted">Đang gửi, chờ thiết bị phản hồi (ACK) tối đa 5 giây...</span>}
          {!busy && last && (
            <span className="small">
              Lệnh gần nhất: <b>{actionLabel(last.action, last.params)}</b> <Badge value={last.status} />
              {last.latencyMs != null && <> · độ trễ <b>{last.latencyMs} ms</b></>}
              {last.resultMessage && <> · {last.resultMessage}</>}
            </span>
          )}
        </div>
      </Card>

      <Card title="Lịch sử lệnh" actions={<Button variant="ghost" small onClick={load}>Tải lại</Button>}>
        {!history ? null : history.length === 0 ? <Empty>Chưa có lệnh nào</Empty> : (
          <div className="table-wrap"><table>
            <thead><tr><th>Thời gian</th><th>Người gửi</th><th>Lệnh</th><th>Tham số</th><th>Kết quả</th><th className="num">Độ trễ</th><th>Phản hồi</th><th>Mã lệnh</th></tr></thead>
            <tbody>{history.map((c) => (
              <tr key={c.id}>
                <td className="small">{fmtDateTime(c.createdAt)}</td>
                <td>{c.requestedByUsername}</td>
                <td><b>{actionLabel(c.action, c.params)}</b></td>
                <td className="mono">{JSON.stringify(c.params)}</td>
                <td><Badge value={c.status} /></td>
                <td className="num">{c.latencyMs != null ? `${c.latencyMs} ms` : '—'}</td>
                <td className="small">{c.resultMessage || '—'}</td>
                <td className="mono small muted">{c.id.slice(0, 8)}</td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </Card>
    </div>
  );
}

function ActuatorCard({ title, value, mode, onText, offText, autoHelp, manualOffWarn, disabled, onOn, onOff, onAuto }) {
  const manual = mode === 'MANUAL';
  return (
    <Card title={title}>
      <div className="row" style={{ marginBottom: 12 }}>
        <div className="reading" style={{ minWidth: 150 }}>
          <div className="r-label">Trạng thái</div>
          <div className="r-value" style={{ fontSize: 22 }}>{value === true ? onText : value === false ? offText : '—'}</div>
        </div>
        <div>
          <div className="small muted">Chế độ</div>
          {mode ? <Badge value={manual ? 'WARNING' : 'ACTIVE'} label={manual ? 'Giữ bằng tay' : 'Tự động'} /> : <span className="muted">—</span>}
        </div>
      </div>
      <div className="row">
        <Button variant="success" disabled={disabled} onClick={onOn}>Bật</Button>
        <Button variant="danger" disabled={disabled} onClick={onOff}>Tắt</Button>
        <Button variant={manual ? 'primary' : 'ghost'} disabled={disabled} onClick={onAuto}>Tự động</Button>
      </div>
      <p className="small muted" style={{ marginBottom: 0 }}>
        <b>Bật/Tắt</b>: giữ nguyên trạng thái đó (kể cả khi có báo động) đến khi bạn đổi lại. <b>Tự động</b>: {autoHelp}
      </p>
      {manual && value === false && <Notice kind="warning">{manualOffWarn}</Notice>}
    </Card>
  );
}
