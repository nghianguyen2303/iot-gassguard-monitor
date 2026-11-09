import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client';
import { Badge, Button, Card, Empty, ErrorBox, Field } from '../../components/ui';
import { useToast } from '../../components/Toast';
import { fmtDateTime } from '../../utils/format';

/** Chia sẻ quyền XEM thiết bị cho người dùng khác (mục 4.4b-3, 4.5). */
export default function SharesTab({ device }) {
  const code = device.deviceCode;
  const toast = useToast();
  const [list, setList] = useState(null);
  const [username, setUsername] = useState('');
  const [error, setError] = useState(null);

  const load = useCallback(() => api(`/devices/${code}/shares`).then(setList).catch(setError), [code]);
  useEffect(() => { load(); }, [load]);

  const add = async (e) => {
    e.preventDefault();
    setError(null);
    try {
      await api(`/devices/${code}/shares`, { method: 'POST', body: { username: username.trim() } });
      toast(`Đã chia sẻ cho ${username}`, 'success');
      setUsername('');
      load();
    } catch (err) { setError(err); }
  };
  const remove = async (u) => {
    setError(null);
    try {
      await api(`/devices/${code}/shares/${encodeURIComponent(u)}`, { method: 'DELETE' });
      toast(`Đã thu hồi quyền xem của ${u}`, 'success');
      load();
    } catch (err) { setError(err); }
  };

  return (
    <Card title="Người được xem thiết bị">
      <ErrorBox error={error} />
      <form className="filters" onSubmit={add}>
        <Field label="Thêm theo tên đăng nhập"><input value={username} onChange={(e) => setUsername(e.target.value)} required /></Field>
        <Button type="submit" disabled={device.lifecycleStatus === 'DECOMMISSIONED'}>Chia sẻ quyền xem</Button>
      </form>
      <p className="small muted">Người được chia sẻ chỉ <b>xem</b> dữ liệu, cảnh báo, lịch sử; không điều khiển, cấu hình hay xử lý cảnh báo.</p>
      {!list ? null : list.length === 0 ? <Empty>Chưa chia sẻ cho ai</Empty> : (
        <div className="table-wrap"><table>
          <thead><tr><th>Người dùng</th><th>Họ tên</th><th>Vai trò</th><th>Quyền</th><th>Từ lúc</th><th></th></tr></thead>
          <tbody>{list.map((s) => (
            <tr key={s.userId}><td><b>{s.username}</b></td><td>{s.fullName || '—'}</td><td><Badge value={s.role} /></td>
              <td>Xem</td><td className="small">{fmtDateTime(s.sharedAt)}</td>
              <td><Button small variant="ghost" onClick={() => remove(s.username)}>Thu hồi</Button></td></tr>
          ))}</tbody>
        </table></div>
      )}
    </Card>
  );
}
