import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { fmtDateTime, fmtNum } from '../utils/format';
import { useToast } from './Toast';
import { Badge, Button, Empty, ErrorBox, Field, LABEL, Modal } from './ui';

/** Bảng cảnh báo + xác nhận / xử lý (mục 4.4d). Dùng ở trang Cảnh báo và tab Cảnh báo của thiết bị. */
export default function AlertsTable({ items, onChanged, showDevice = true }) {
  const toast = useToast();
  const [action, setAction] = useState(null); // {alert, kind: 'ack'|'resolve'}
  const [note, setNote] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const run = async () => {
    setBusy(true);
    setError(null);
    try {
      await api(`/alerts/${action.alert.id}/${action.kind}`, { method: 'POST', body: { note: note || null } });
      toast(action.kind === 'ack' ? 'Đã xác nhận cảnh báo' : 'Đã đánh dấu xử lý xong', 'success');
      setAction(null);
      setNote('');
      onChanged?.();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  if (!items || items.length === 0) return <Empty>Không có cảnh báo</Empty>;
  return (
    <>
      <div className="table-wrap"><table>
        <thead><tr>
          <th>Thời gian</th>{showDevice && <th>Thiết bị</th>}<th>Loại</th><th>Mức độ</th><th>Nội dung</th>
          <th className="num">Số lần</th><th>Trạng thái</th><th>Xử lý</th><th></th>
        </tr></thead>
        <tbody>{items.map((a) => (
          <tr key={a.id}>
            <td className="small">{fmtDateTime(a.createdAt)}<div className="muted">gần nhất {fmtDateTime(a.lastSeenAt)}</div></td>
            {showDevice && <td><Link to={`/devices/${a.deviceCode}?tab=alerts`}>{a.deviceCode}</Link><div className="small muted">{a.deviceName}</div></td>}
            <td>{LABEL[a.type] || a.type}</td>
            <td><Badge value={a.severity} /></td>
            <td>{a.message}{a.value != null && a.type === 'THRESHOLD' && <div className="small muted">Giá trị gần nhất: {fmtNum(a.value)}</div>}</td>
            <td className="num">{a.occurrenceCount}</td>
            <td><Badge value={a.status} />{a.status !== 'RESOLVED' && <div className="small muted">{a.clearedAt ? 'đã trở lại bình thường' : 'điều kiện vẫn còn'}</div>}</td>
            <td className="small">
              {a.acknowledgedBy && <div>Xác nhận: <b>{a.acknowledgedBy}</b> {fmtDateTime(a.acknowledgedAt)}</div>}
              {a.resolvedBy && <div>Xử lý: <b>{a.resolvedBy}</b> {fmtDateTime(a.resolvedAt)}</div>}
              {a.resolutionNote && <div className="muted">“{a.resolutionNote}”</div>}
            </td>
            <td>
              {a.canHandle && a.status === 'OPEN' && <Button small variant="warning" onClick={() => { setAction({ alert: a, kind: 'ack' }); setNote(''); setError(null); }}>Xác nhận</Button>}{' '}
              {a.canHandle && a.status !== 'RESOLVED' && <Button small variant="success" onClick={() => { setAction({ alert: a, kind: 'resolve' }); setNote(''); setError(null); }}>Xử lý xong</Button>}
            </td>
          </tr>
        ))}</tbody>
      </table></div>
      {action && (
        <Modal title={action.kind === 'ack' ? 'Xác nhận cảnh báo' : 'Đánh dấu đã xử lý'} onClose={() => setAction(null)}
               footer={<><Button variant="ghost" onClick={() => setAction(null)}>Hủy</Button>
                 <Button onClick={run} disabled={busy || (action.kind === 'resolve' && note.trim().length < 3)}>Xác nhận</Button></>}>
          <ErrorBox error={error} />
          <p><Badge value={action.alert.severity} /> {action.alert.message}</p>
          {action.kind === 'resolve' && !action.alert.clearedAt && (
            <p className="small" style={{ color: '#b45309' }}>⚠ Điều kiện vẫn đang vi phạm: nếu chưa khắc phục, hệ thống sẽ sinh cảnh báo mới ở lần đo tiếp theo.</p>
          )}
          <Field label={action.kind === 'resolve' ? 'Cách đã xử lý *' : 'Ghi chú (tùy chọn)'} hint={action.kind === 'resolve' ? 'Ít nhất 3 ký tự, ghi vào lịch sử' : ''}>
            <textarea value={note} onChange={(e) => setNote(e.target.value)} autoFocus />
          </Field>
        </Modal>
      )}
    </>
  );
}
