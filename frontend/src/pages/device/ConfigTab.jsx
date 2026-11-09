import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client';
import { Badge, Button, Card, ErrorBox, Field, Loading, Notice } from '../../components/ui';
import { useToast } from '../../components/Toast';
import { useDeviceEvents } from '../../realtime/RealtimeContext';
import { fmtDateTime } from '../../utils/format';

/** Cấu hình tham số hoạt động từ xa (mục 4.4b-5). Gửi retained + có phiên bản, thiết bị xác nhận đã áp dụng. */
export default function ConfigTab({ device }) {
  const code = device.deviceCode;
  const toast = useToast();
  const [cfg, setCfg] = useState(null);
  const [form, setForm] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => api(`/devices/${code}/config`).then((c) => {
    setCfg(c);
    setForm({ telemetryIntervalSec: c.config.telemetryIntervalSec, gasThreshold: c.config.gasThreshold, autoFan: c.config.autoFan });
  }).catch(setError), [code]);
  useEffect(() => { load(); }, [load]);
  useDeviceEvents([code], (m) => { if (m.type === 'config') setCfg(m.config); });

  const save = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const c = await api(`/devices/${code}/config`, { method: 'PUT', body: {
        telemetryIntervalSec: Number(form.telemetryIntervalSec), gasThreshold: Number(form.gasThreshold), autoFan: form.autoFan,
      } });
      setCfg(c);
      toast(`Đã gửi cấu hình v${c.configVersion}, chờ thiết bị xác nhận`, 'info');
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  if (!cfg || !form) return error ? <ErrorBox error={error} /> : <Loading />;
  const readOnly = !device.canManage || device.lifecycleStatus === 'DECOMMISSIONED';

  return (
    <div className="grid-2">
      <Card title="Thông số hoạt động">
        <form onSubmit={save}>
          <ErrorBox error={error} />
          {!device.canManage && <Notice kind="info">Bạn chỉ có quyền xem cấu hình.</Notice>}
          <Field label="Chu kỳ gửi dữ liệu (giây)" hint="1 – 3600 giây" error={error?.fieldErrors?.telemetryIntervalSec}>
            <input type="number" min="1" max="3600" value={form.telemetryIntervalSec} disabled={readOnly}
                   onChange={(e) => setForm({ ...form, telemetryIntervalSec: e.target.value })} />
          </Field>
          <Field label="Ngưỡng gas tự bật quạt (ppm)" hint="50 – 10000 ppm. Thiết bị tự bật quạt khi vượt ngưỡng, kể cả khi mất mạng."
                 error={error?.fieldErrors?.gasThreshold}>
            <input type="number" min="50" max="10000" step="10" value={form.gasThreshold} disabled={readOnly}
                   onChange={(e) => setForm({ ...form, gasThreshold: e.target.value })} />
          </Field>
          <label className="check">
            <input type="checkbox" checked={!!form.autoFan} disabled={readOnly} onChange={(e) => setForm({ ...form, autoFan: e.target.checked })} />
            Tự động bật quạt khi gas vượt ngưỡng
          </label>
          {!readOnly && <Button type="submit" disabled={busy}>{busy ? 'Đang gửi...' : 'Lưu và gửi xuống thiết bị'}</Button>}
        </form>
      </Card>
      <Card title="Trạng thái áp dụng">
        <dl className="kv">
          <dt>Phiên bản đã gửi</dt><dd><b>v{cfg.configVersion}</b></dd>
          <dt>Thiết bị đã áp dụng</dt><dd><b>v{cfg.appliedVersion}</b></dd>
          <dt>Trạng thái</dt><dd><Badge value={cfg.status} /></dd>
          {cfg.message && <><dt>Lý do</dt><dd>{cfg.message}</dd></>}
          <dt>Xác nhận lúc</dt><dd>{fmtDateTime(cfg.ackedAt)}</dd>
          <dt>Cấu hình hiện tại</dt><dd className="mono">{JSON.stringify(cfg.config)}</dd>
        </dl>
        <p className="small muted" style={{ marginBottom: 0 }}>
          Cấu hình được gửi dạng <b>retained</b>: thiết bị đang mất kết nối sẽ nhận ngay khi kết nối lại.
          Trạng thái chuyển từ <Badge value="PENDING" /> sang <Badge value="APPLIED" /> khi thiết bị gửi xác nhận cùng phiên bản.
        </p>
      </Card>
    </div>
  );
}
