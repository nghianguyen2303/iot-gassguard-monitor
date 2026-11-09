import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';
import { Badge, Button, Card, Empty, ErrorBox, Loading, StatTile } from '../components/ui';
import { fmtDateTime } from '../utils/format';

/** Thông tin vận hành tiếp nhận dữ liệu (ADMIN): phục vụ kiểm thử phi chức năng mục 7.2. */
export default function SystemPage() {
  const [s, setS] = useState(null);
  const [error, setError] = useState(null);
  const load = useCallback(() => api('/system/ingest-stats').then((d) => { setS(d); setError(null); }).catch(setError), []);
  useEffect(() => { load(); const t = setInterval(load, 5000); return () => clearInterval(t); }, [load]);

  if (error) return <div className="stack"><div className="page-head"><h1>Hệ thống</h1></div><ErrorBox error={error} /></div>;
  if (!s) return <Loading />;
  const lat = s.latencyMs || {};
  return (
    <div className="stack">
      <div className="page-head">
        <div><h1>Hệ thống</h1><p>Số liệu tiếp nhận MQTT từ lúc backend khởi động ({fmtDateTime(s.since)}). Tự làm mới mỗi 5 giây.</p></div>
        <Button variant="ghost" onClick={load}>Làm mới</Button>
      </div>
      <div className="grid-tiles">
        <StatTile label="MQTT broker" value={s.mqttConnected ? 'Đã kết nối' : 'Mất kết nối'} tone={s.mqttConnected ? 'green' : 'red'} />
        <StatTile label="Bản tin MQTT nhận" value={s.mqttMessagesReceived.toLocaleString('vi-VN')} />
        <StatTile label="Telemetry hợp lệ" value={s.telemetryAccepted.toLocaleString('vi-VN')} sub={`/${s.telemetryMessages} bản tin telemetry`} tone="green" />
        <StatTile label="Số đo đã lưu" value={s.readingsStored.toLocaleString('vi-VN')} />
        <StatTile label="Bản trùng bị loại" value={s.duplicateReadingsIgnored} tone="amber" />
        <StatTile label="Bản tin bị từ chối" value={s.rejectedMessages} tone={s.rejectedMessages ? 'red' : 'gray'} />
      </div>
      <div className="grid-2">
        <Card title="Độ trễ thiết bị → backend (ms)">
          {lat.windowSize ? (
            <div className="grid-tiles">
              <StatTile label="Trung bình" value={lat.avg} /><StatTile label="P50" value={lat.p50} />
              <StatTile label="P95" value={lat.p95} /><StatTile label="Max" value={lat.max} />
            </div>
          ) : <Empty>Chưa có mẫu</Empty>}
          <p className="small muted">Tính trên {lat.windowSize || 0} bản tin gần nhất (received_at − ts).</p>
        </Card>
        <Card title="Từ chối theo lý do">
          {Object.keys(s.rejectedByReason).length === 0 ? <Empty>Chưa từ chối bản tin nào</Empty> : (
            <table><tbody>{Object.entries(s.rejectedByReason).map(([k, v]) => <tr key={k}><td className="mono">{k}</td><td className="num"><b>{v}</b></td></tr>)}</tbody></table>
          )}
        </Card>
      </div>
      <Card title="Bản tin bị từ chối gần đây">
        {s.recentRejections.length === 0 ? <Empty /> : (
          <div className="table-wrap"><table>
            <thead><tr><th>Lúc</th><th>Kênh</th><th>Thiết bị</th><th>Lý do</th><th>Chi tiết</th></tr></thead>
            <tbody>{s.recentRejections.map((r, i) => (
              <tr key={i}><td className="small">{fmtDateTime(r.at)}</td><td className="mono">{r.channel}</td><td>{r.device}</td>
                <td><Badge value="DENIED" label={r.reason} /></td><td className="small">{r.detail}</td></tr>
            ))}</tbody>
          </table></div>
        )}
      </Card>
    </div>
  );
}
