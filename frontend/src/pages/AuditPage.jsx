import { useCallback, useEffect, useState } from 'react';
import { api, qs } from '../api/client';
import { Badge, Card, Empty, ErrorBox, Field, Pagination } from '../components/ui';
import { fmtDateTime, fromLocalInput, toLocalInputEnd } from '../utils/format';

export function AuditTable({ items }) {
  if (!items || items.length === 0) return <Empty>Chưa có hoạt động</Empty>;
  return (
    <div className="table-wrap"><table>
      <thead><tr><th>Thời gian</th><th>Người thực hiện</th><th>Hành động</th><th>Đối tượng</th><th>Kết quả</th><th>Chi tiết</th><th>IP</th></tr></thead>
      <tbody>{items.map((a) => (
        <tr key={a.id}>
          <td className="small">{fmtDateTime(a.createdAt)}</td>
          <td>{a.actorUsername || <span className="muted">—</span>}</td>
          <td className="mono">{a.action}</td>
          <td className="small">{a.targetType ? `${a.targetType}: ${a.targetId ?? ''}` : '—'}</td>
          <td><Badge value={a.result} /></td>
          <td>{a.details ? <div className="json">{JSON.stringify(a.details)}</div> : '—'}</td>
          <td className="mono small">{a.ipAddress || '—'}</td>
        </tr>
      ))}</tbody>
    </table></div>
  );
}

/** Audit log toàn hệ thống (mục 4.5, 4.6) – chỉ ADMIN. */
export default function AuditPage() {
  const [f, setF] = useState({ actor: '', action: '', result: '', from: '', to: '' });
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const load = useCallback(() => {
    setError(null);
    api(`/admin/audit-logs${qs({ ...f, from: fromLocalInput(f.from), to: toLocalInputEnd(f.to), page, size: 50 })}`).then(setData).catch(setError);
  }, [f, page]);
  useEffect(() => { load(); }, [load]);
  const set = (k) => (e) => { setF({ ...f, [k]: e.target.value }); setPage(0); };

  return (
    <div className="stack">
      <div className="page-head"><div><h1>Audit log</h1><p>Mọi thao tác quan trọng, kể cả các lần bị từ chối quyền (DENIED).</p></div></div>
      <Card>
        <div className="filters">
          <Field label="Người thực hiện"><input value={f.actor} onChange={set('actor')} placeholder="username" /></Field>
          <Field label="Hành động (tiền tố)">
            <select value={f.action} onChange={set('action')}>
              <option value="">Tất cả</option><option value="LOGIN">LOGIN</option><option value="DEVICE">DEVICE_*</option>
              <option value="COMMAND">COMMAND_*</option><option value="ALERT">ALERT_*</option><option value="ACCESS_DENIED">ACCESS_DENIED</option>
              <option value="DATA_EXPORT">DATA_EXPORT</option><option value="CHANGE">CHANGE_*</option><option value="LOCK">LOCK_USER</option>
            </select>
          </Field>
          <Field label="Kết quả">
            <select value={f.result} onChange={set('result')}>
              <option value="">Tất cả</option><option value="SUCCESS">SUCCESS</option><option value="DENIED">DENIED</option><option value="FAILED">FAILED</option>
            </select>
          </Field>
          <Field label="Từ"><input type="datetime-local" value={f.from} onChange={set('from')} /></Field>
          <Field label="Đến"><input type="datetime-local" value={f.to} onChange={set('to')} /></Field>
        </div>
        <ErrorBox error={error} />
        {data && <><AuditTable items={data.items} /><Pagination page={data.page} size={data.size} total={data.total} onPage={setPage} /></>}
      </Card>
    </div>
  );
}
