import { useState } from 'react';
import { api } from '../../api/client';
import { Button, ErrorBox, Field, Modal, Notice } from '../../components/ui';
import { useToast } from '../../components/Toast';

const ACTIONS = {
  ACTIVATE: { label: 'Kích hoạt', variant: 'success', desc: 'Chuyển thiết bị sang Hoạt động (thông thường tự kích hoạt khi nhận dữ liệu đầu tiên).' },
  START_MAINTENANCE: { label: 'Bắt đầu bảo trì', variant: 'warning', desc: 'Trong khi bảo trì: vẫn lưu dữ liệu nhưng KHÔNG sinh cảnh báo.' },
  END_MAINTENANCE: { label: 'Kết thúc bảo trì', variant: 'success', desc: 'Thiết bị trở lại Hoạt động, cảnh báo hoạt động bình thường.' },
  SUSPEND: { label: 'Tạm khóa', variant: 'danger', reason: true, desc: 'Broker NGẮT KẾT NỐI thiết bị ngay và từ chối kết nối lại; backend bỏ qua dữ liệu của thiết bị.' },
  RESUME: { label: 'Mở khóa', variant: 'success', desc: 'Cho phép thiết bị kết nối lại broker.' },
  DECOMMISSION: { label: 'Ngừng sử dụng', variant: 'danger', reason: true, irreversible: true,
                  desc: 'Thu hồi vĩnh viễn tài khoản MQTT. KHÔNG thể hoàn tác; dữ liệu lịch sử vẫn được giữ để tra cứu.' },
};

/** Các nút chuyển trạng thái vòng đời, lấy từ allowedActions do backend tính (mục 4.4b). */
export default function LifecycleActions({ device, reload }) {
  const toast = useToast();
  const [action, setAction] = useState(null);
  const [reason, setReason] = useState('');
  const [confirmText, setConfirmText] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const available = (device.allowedActions || []).filter((a) => ACTIONS[a]);
  if (!device.canManage || available.length === 0) return null;
  const meta = action && ACTIONS[action];

  const run = async () => {
    setBusy(true);
    setError(null);
    try {
      await api(`/devices/${device.deviceCode}/lifecycle`, { method: 'POST', body: { action, reason: reason || null } });
      toast(`${meta.label}: thành công`, 'success');
      setAction(null);
      setReason('');
      setConfirmText('');
      reload();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <div className="row">
        {available.map((a) => (
          <Button key={a} variant={ACTIONS[a].variant} small onClick={() => { setAction(a); setError(null); }}>{ACTIONS[a].label}</Button>
        ))}
      </div>
      {meta && (
        <Modal title={`${meta.label} thiết bị ${device.deviceCode}`} onClose={() => setAction(null)}
               footer={<>
                 <Button variant="ghost" onClick={() => setAction(null)}>Hủy</Button>
                 <Button variant={meta.variant} onClick={run}
                         disabled={busy || (meta.reason && !reason.trim()) || (meta.irreversible && confirmText !== device.deviceCode)}>
                   Xác nhận
                 </Button>
               </>}>
          <ErrorBox error={error} />
          <Notice kind={meta.variant === 'danger' ? 'warning' : 'info'}>{meta.desc}</Notice>
          {meta.reason && (
            <Field label="Lý do *" hint="Được ghi vào audit log">
              <textarea value={reason} onChange={(e) => setReason(e.target.value)} />
            </Field>
          )}
          {meta.irreversible && (
            <Field label={`Gõ "${device.deviceCode}" để xác nhận`}>
              <input value={confirmText} onChange={(e) => setConfirmText(e.target.value)} />
            </Field>
          )}
        </Modal>
      )}
    </>
  );
}
