import { useCallback, useEffect, useState } from 'react';
import { api, qs } from '../../api/client';
import AlertsTable from '../../components/AlertsTable';
import { Card, ErrorBox, Pagination } from '../../components/ui';
import { useDeviceEvents } from '../../realtime/RealtimeContext';

export default function AlertsTab({ device }) {
  const code = device.deviceCode;
  const [status, setStatus] = useState('ACTIVE');
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);

  const load = useCallback(() => {
    api(`/alerts${qs({ deviceCode: code, status, page, size: 20 })}`).then(setData).catch(setError);
  }, [code, status, page]);
  useEffect(() => { load(); }, [load]);
  useDeviceEvents([code], (m) => { if (m.type === 'alert') load(); });

  return (
    <Card title="Cảnh báo của thiết bị" actions={
      <select value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
        <option value="ACTIVE">Chưa xử lý xong</option><option value="">Tất cả</option>
        <option value="OPEN">Mới</option><option value="ACKNOWLEDGED">Đang xử lý</option><option value="RESOLVED">Đã xử lý</option>
      </select>}>
      <ErrorBox error={error} />
      {data && <><AlertsTable items={data.items} onChanged={load} showDevice={false} />
        <Pagination page={data.page} size={data.size} total={data.total} onPage={setPage} /></>}
    </Card>
  );
}
