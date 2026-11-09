import { useCallback, useEffect, useState } from 'react';
import { api, qs } from '../../api/client';
import { AuditTable } from '../AuditPage';
import { Card, ErrorBox, Pagination } from '../../components/ui';

/** Lịch sử hoạt động của thiết bị (mục 4.6): đăng ký, cấp quyền, vòng đời, lệnh, cấu hình, xuất dữ liệu... */
export default function ActivityTab({ device }) {
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const load = useCallback(() => {
    api(`/devices/${device.deviceCode}/activity${qs({ page, size: 30 })}`).then(setData).catch(setError);
  }, [device.deviceCode, page]);
  useEffect(() => { load(); }, [load]);
  return (
    <Card title="Lịch sử hoạt động">
      <ErrorBox error={error} />
      {data && <><AuditTable items={data.items} /><Pagination page={data.page} size={data.size} total={data.total} onPage={setPage} /></>}
    </Card>
  );
}
