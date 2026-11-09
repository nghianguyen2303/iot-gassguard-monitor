import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import DeviceCard from '../components/DeviceCard';
import { Badge, Card, Empty, ErrorBox, Loading, StatTile } from '../components/ui';
import { useDeviceEvents, useRealtime } from '../realtime/RealtimeContext';
import { fmtDateTime } from '../utils/format';

/** Dashboard trạng thái hệ thống (mục 4.4d) + dữ liệu thời gian thực (bước 4 của demo). */
export default function DashboardPage() {
  const [data, setData] = useState(null);
  const [alerts, setAlerts] = useState([]);
  const [error, setError] = useState(null);
  const [flash, setFlash] = useState({});
  const [now, setNow] = useState(Date.now());
  const { connected } = useRealtime();

  const load = useCallback(async () => {
    try {
      const [d, a] = await Promise.all([api('/dashboard'), api('/alerts?status=ACTIVE&size=8')]);
      setData(d);
      setAlerts(a.items);
      setError(null);
    } catch (e) {
      setError(e);
    }
  }, []);

  useEffect(() => { load(); }, [load]);
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 5000); return () => clearInterval(t); }, []);

  const codes = data?.devices.map((d) => d.deviceCode) || [];
  useDeviceEvents(codes, (m) => {
    if (m.type === 'telemetry') {
      setData((prev) => prev && ({
        ...prev,
        devices: prev.devices.map((d) => d.deviceCode !== m.deviceCode ? d : {
          ...d,
          lastSeenAt: m.receivedAt,
          latest: { ...d.latest, ...Object.fromEntries(Object.entries(m.readings).map(([k, v]) => [k, { ...v, ts: m.ts }])) },
        }),
      }));
      setFlash((f) => ({ ...f, [m.deviceCode]: Date.now() }));
    } else {
      // Trạng thái/cảnh báo/lệnh thay đổi: tải lại số liệu tổng hợp cho chính xác
      load();
    }
  });

  if (error) return <ErrorBox error={error} />;
  if (!data) return <Loading />;
  const c = data.byConnectivity;
  const al = data.alerts;

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>Dashboard</h1>
          <p>{connected ? 'Đang nhận dữ liệu thời gian thực' : 'Mất kết nối realtime, đang thử lại...'} · cập nhật {fmtDateTime(data.generatedAt)}</p>
        </div>
      </div>

      <div className="grid-tiles">
        <StatTile label="Thiết bị" value={data.devicesTotal} sub={`${data.byLifecycle.ACTIVE} hoạt động · ${data.byLifecycle.MAINTENANCE} bảo trì`} />
        <StatTile label="Trực tuyến" value={c.ONLINE} tone="green" />
        <StatTile label="Mất kết nối" value={c.OFFLINE} tone="gray" />
        <StatTile label="Lỗi cảm biến" value={c.FAULT} tone="amber" />
        <StatTile label="Cảnh báo chưa xử lý" value={al.active} tone={al.active ? 'red' : 'green'}
                  sub={`${al.bySeverity.CRITICAL} nghiêm trọng · ${al.bySeverity.WARNING} cảnh báo`} />
      </div>

      <Card title="Thiết bị" actions={<Link to="/devices">Quản lý thiết bị →</Link>}>
        {data.devices.length === 0
          ? <Empty>Chưa có thiết bị nào. {data.devicesTotal === 0 && <Link to="/devices">Đăng ký thiết bị</Link>}</Empty>
          : <div className="grid-cards">
              {data.devices.map((d) => (
                <DeviceCard key={d.deviceCode} d={d} now={now} flash={flash[d.deviceCode] && now - flash[d.deviceCode] < 1500} />
              ))}
            </div>}
      </Card>

      <Card title="Cảnh báo đang mở" actions={<Link to="/alerts">Xem tất cả →</Link>}>
        {alerts.length === 0 ? <Empty>Không có cảnh báo nào cần xử lý 👍</Empty> : (
          <div className="table-wrap"><table>
            <thead><tr><th>Thời gian</th><th>Thiết bị</th><th>Mức độ</th><th>Nội dung</th><th>Trạng thái</th></tr></thead>
            <tbody>{alerts.map((a) => (
              <tr key={a.id}>
                <td className="small">{fmtDateTime(a.createdAt)}</td>
                <td><Link to={`/devices/${a.deviceCode}`}>{a.deviceCode}</Link></td>
                <td><Badge value={a.severity} /></td>
                <td>{a.message}{a.occurrenceCount > 1 && <span className="muted small"> ×{a.occurrenceCount}</span>}</td>
                <td><Badge value={a.status} />{a.clearedAt && <span className="small muted"> (đã bình thường)</span>}</td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </Card>
    </div>
  );
}
