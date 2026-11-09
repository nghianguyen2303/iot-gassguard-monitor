import { useCallback, useEffect, useState } from 'react';
import { api, qs } from '../api/client';
import AlertsTable from '../components/AlertsTable';
import { Card, ErrorBox, Field, Pagination, StatTile } from '../components/ui';
import { useDeviceEvents } from '../realtime/RealtimeContext';
import { fromLocalInput, toLocalInputEnd } from '../utils/format';

/** Xem và xử lý cảnh báo, lịch sử cảnh báo có lọc (mục 4.4d, 4.6). */
export default function AlertsPage() {
  const [f, setF] = useState({ status: 'ACTIVE', severity: '', type: '', deviceCode: '', from: '', to: '' });
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);
  const [summary, setSummary] = useState(null);
  const [codes, setCodes] = useState([]);
  const [error, setError] = useState(null);

  const load = useCallback(() => {
    setError(null);
    api(`/alerts${qs({ ...f, from: fromLocalInput(f.from), to: toLocalInputEnd(f.to), page, size: 20 })}`).then(setData).catch(setError);
    api('/alerts/summary').then(setSummary).catch(() => {});
  }, [f, page]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => { api('/devices?includeDecommissioned=true').then((ds) => setCodes(ds.map((d) => d.deviceCode))).catch(() => {}); }, []);
  useDeviceEvents(codes, (m) => { if (m.type === 'alert') load(); });

  const set = (k) => (e) => { setF({ ...f, [k]: e.target.value }); setPage(0); };

  return (
    <div className="stack">
      <div className="page-head"><div><h1>Cảnh báo</h1><p>Cảnh báo vượt ngưỡng, mất kết nối và lỗi cảm biến. Danh sách tự cập nhật theo thời gian thực.</p></div></div>
      {summary && (
        <div className="grid-tiles">
          <StatTile label="Chưa xử lý xong" value={summary.active} tone={summary.active ? 'red' : 'green'} />
          <StatTile label="Mới" value={summary.open} tone={summary.open ? 'red' : 'gray'} />
          <StatTile label="Đang xử lý" value={summary.acknowledged} tone="amber" />
          <StatTile label="Nghiêm trọng" value={summary.bySeverity.CRITICAL} tone={summary.bySeverity.CRITICAL ? 'red' : 'gray'} />
        </div>
      )}
      <Card>
        <div className="filters">
          <Field label="Trạng thái">
            <select value={f.status} onChange={set('status')}>
              <option value="ACTIVE">Chưa xử lý xong</option><option value="">Tất cả</option><option value="OPEN">Mới</option>
              <option value="ACKNOWLEDGED">Đang xử lý</option><option value="RESOLVED">Đã xử lý</option>
            </select>
          </Field>
          <Field label="Mức độ">
            <select value={f.severity} onChange={set('severity')}>
              <option value="">Tất cả</option><option value="CRITICAL">Nghiêm trọng</option><option value="WARNING">Cảnh báo</option><option value="INFO">Thông tin</option>
            </select>
          </Field>
          <Field label="Loại">
            <select value={f.type} onChange={set('type')}>
              <option value="">Tất cả</option><option value="THRESHOLD">Vượt ngưỡng</option><option value="OFFLINE">Mất kết nối</option><option value="FAULT">Lỗi cảm biến</option>
            </select>
          </Field>
          <Field label="Thiết bị">
            <select value={f.deviceCode} onChange={set('deviceCode')}>
              <option value="">Tất cả</option>{codes.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
          </Field>
          <Field label="Từ"><input type="datetime-local" value={f.from} onChange={set('from')} /></Field>
          <Field label="Đến"><input type="datetime-local" value={f.to} onChange={set('to')} /></Field>
        </div>
        <ErrorBox error={error} />
        {data && <><AlertsTable items={data.items} onChanged={load} /><Pagination page={data.page} size={data.size} total={data.total} onPage={setPage} /></>}
      </Card>
    </div>
  );
}
