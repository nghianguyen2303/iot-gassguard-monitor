import { useEffect, useState } from 'react';
import { CartesianGrid, Legend, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api, qs } from '../../api/client';
import { Badge, Card, Empty } from '../../components/ui';
import { useDeviceEvents } from '../../realtime/RealtimeContext';
import { fmtAgo, fmtDateTime, fmtNum, fmtTime, actuatorText } from '../../utils/format';
import LifecycleActions from './LifecycleActions';

const WINDOW_MIN = 30;
const MAX_POINTS = 900;

/** Gộp các điểm gas/temp cùng thời điểm thành 1 dòng cho biểu đồ. */
function mergeRows(points) {
  const map = new Map();
  for (const p of points) {
    const t = new Date(p.ts).getTime();
    map.set(t, { ...(map.get(t) || { t }), [p.metric]: p.value });
  }
  return [...map.values()].sort((a, b) => a.t - b.t);
}

export default function OverviewTab({ device, reload }) {
  const [rows, setRows] = useState([]);
  const [latest, setLatest] = useState({});
  const [rules, setRules] = useState([]);
  const [now, setNow] = useState(Date.now());
  const code = device.deviceCode;

  useEffect(() => {
    const from = new Date(Date.now() - WINDOW_MIN * 60000).toISOString();
    api(`/devices/${code}/telemetry${qs({ from, limit: 3000 })}`).then((r) => setRows(mergeRows(r.points))).catch(() => {});
    api(`/devices/${code}/telemetry/latest`).then(setLatest).catch(() => {});
    api(`/alert-rules${qs({ deviceCode: code })}`).then(setRules).catch(() => setRules([]));
    const t = setInterval(() => setNow(Date.now()), 5000);
    return () => clearInterval(t);
  }, [code]);

  useDeviceEvents([code], (m) => {
    if (m.type !== 'telemetry') return;
    const t = new Date(m.ts).getTime();
    const row = { t };
    const next = {};
    for (const [k, v] of Object.entries(m.readings)) { row[k] = v.value; next[k] = { ...v, metric: k, ts: m.ts }; }
    setLatest((l) => ({ ...l, ...next }));
    setRows((rs) => {
      const cutoff = Date.now() - WINDOW_MIN * 60000;
      return [...rs.filter((r) => r.t >= cutoff && r.t !== t), row].sort((a, b) => a.t - b.t).slice(-MAX_POINTS);
    });
  });

  const gasRules = rules.filter((r) => r.metric === 'gas' && r.enabled && (r.operator === 'GT' || r.operator === 'GTE'));
  const cfg = device.config || {};

  return (
    <div className="stack">
      <div className="grid-2">
        <Card title="Thông tin thiết bị" actions={<LifecycleActions device={device} reload={reload} />}>
          <dl className="kv">
            <dt>Mã thiết bị</dt><dd className="mono">{device.deviceCode}</dd>
            <dt>Tên</dt><dd>{device.name}</dd>
            <dt>Loại</dt><dd>{device.deviceType}</dd>
            <dt>Vị trí / khu vực</dt><dd>{[device.location, device.zone].filter(Boolean).join(' / ') || '—'}</dd>
            <dt>Chủ sở hữu</dt><dd>{device.ownerUsername}</dd>
            <dt>Vòng đời</dt><dd><Badge value={device.lifecycleStatus} /></dd>
            <dt>Kết nối</dt><dd><Badge value={device.connectivity} /> <span className="small muted">lần cuối {fmtAgo(device.lastSeenAt, now)}</span></dd>
            <dt>Phần cứng / firmware</dt><dd className="mono">{device.hwVersion || '—'} / {device.fwVersion || '—'}</dd>
            <dt>Cấu hình</dt><dd>v{device.configAppliedVersion}/{device.configVersion} <Badge value={device.configStatus} /></dd>
            <dt>Đăng ký lúc</dt><dd>{fmtDateTime(device.createdAt)}</dd>
            {device.decommissionedAt && <><dt>Ngừng sử dụng</dt><dd>{fmtDateTime(device.decommissionedAt)}</dd></>}
          </dl>
        </Card>
        <Card title="Hiện tại">
          <div className="readings">
            <div className={`reading${latest.gas && gasRules.some((r) => latest.gas.value > r.threshold) ? ' hot' : ''}`}>
              <div className="r-label">Khí gas</div>
              <div className="r-value">{latest.gas ? fmtNum(latest.gas.value) : '—'}<span className="r-unit">ppm</span></div>
              <div className="small muted">{latest.gas ? fmtTime(latest.gas.ts) : ''}</div>
            </div>
            <div className="reading">
              <div className="r-label">Nhiệt độ</div>
              <div className="r-value">{latest.temp ? fmtNum(latest.temp.value) : '—'}<span className="r-unit">°C</span></div>
              <div className="small muted">{latest.temp ? fmtTime(latest.temp.ts) : ''}</div>
            </div>
            <div className="reading">
              <div className="r-label">Quạt hút / Còi</div>
              <div className="r-value" style={{ fontSize: 20 }}>{actuatorText(device.actuatorState, 'fan')}</div>
              <div className="small muted">Còi: {actuatorText(device.actuatorState, 'buzzer')}</div>
            </div>
            <div className="reading">
              <div className="r-label">Ngưỡng tự bật quạt</div>
              <div className="r-value">{fmtNum(cfg.gasThreshold ?? 400)}<span className="r-unit">ppm</span></div>
              <div className="small muted">{cfg.autoFan === false ? 'Tự bật quạt: TẮT' : 'Tự bật quạt: BẬT'}</div>
            </div>
          </div>
        </Card>
      </div>
      <Card title={`Biểu đồ thời gian thực (${WINDOW_MIN} phút gần nhất)`}>
        {rows.length === 0 ? <Empty>Chưa có dữ liệu trong {WINDOW_MIN} phút qua. Biểu đồ sẽ tự cập nhật khi thiết bị gửi dữ liệu.</Empty> : (
          <div className="chart-box tall">
            <ResponsiveContainer>
              <LineChart data={rows} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                <XAxis dataKey="t" type="number" scale="time" domain={['dataMin', 'dataMax']} tickFormatter={(t) => fmtTime(t)} minTickGap={40} />
                <YAxis yAxisId="gas" stroke="#dc2626" label={{ value: 'ppm', angle: -90, position: 'insideLeft' }} />
                <YAxis yAxisId="temp" orientation="right" stroke="#2563eb" label={{ value: '°C', angle: 90, position: 'insideRight' }} />
                <Tooltip labelFormatter={(t) => fmtDateTime(t)} formatter={(v, n) => [fmtNum(v), n]} />
                <Legend />
                {gasRules.map((r) => (
                  <ReferenceLine key={r.id} yAxisId="gas" y={r.threshold} stroke={r.severity === 'CRITICAL' ? '#991b1b' : '#d97706'}
                                 strokeDasharray="6 4" label={{ value: `${r.name} (${r.threshold})`, fontSize: 11, position: 'insideTopLeft' }} />
                ))}
                <Line yAxisId="gas" type="monotone" dataKey="gas" name="Khí gas (ppm)" stroke="#dc2626" dot={false} isAnimationActive={false} connectNulls />
                <Line yAxisId="temp" type="monotone" dataKey="temp" name="Nhiệt độ (°C)" stroke="#2563eb" dot={false} isAnimationActive={false} connectNulls />
              </LineChart>
            </ResponsiveContainer>
          </div>
        )}
      </Card>
    </div>
  );
}
