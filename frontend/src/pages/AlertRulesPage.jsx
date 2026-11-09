import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { Badge, Button, Card, Empty, ErrorBox, Field, Modal, Notice } from '../components/ui';
import { useToast } from '../components/Toast';
import { fmtNum } from '../utils/format';

const OPS = { GT: '>', GTE: '≥', LT: '<', LTE: '≤' };
const EMPTY = { name: '', deviceCode: '', metric: 'gas', operator: 'GT', threshold: 400, severity: 'WARNING', enabled: true };

/** Thiết lập luật cảnh báo (mục 4.4d). Luật chung: ADMIN. Luật riêng: chủ thiết bị. */
export default function AlertRulesPage() {
  const { isAdmin } = useAuth();
  const toast = useToast();
  const [rules, setRules] = useState(null);
  const [devices, setDevices] = useState([]);
  const [edit, setEdit] = useState(null);
  const [error, setError] = useState(null);
  const [formError, setFormError] = useState(null);

  const load = useCallback(() => api('/alert-rules').then(setRules).catch(setError), []);
  useEffect(() => {
    load();
    api('/devices').then((ds) => setDevices(ds.filter((d) => d.canManage))).catch(() => {});
  }, [load]);

  const save = async (e) => {
    e.preventDefault();
    setFormError(null);
    const body = { ...edit, threshold: Number(edit.threshold), deviceCode: edit.deviceCode || null };
    try {
      if (edit.id) await api(`/alert-rules/${edit.id}`, { method: 'PUT', body });
      else await api('/alert-rules', { method: 'POST', body });
      toast('Đã lưu luật cảnh báo', 'success');
      setEdit(null);
      load();
    } catch (err) { setFormError(err); }
  };
  const remove = async (r) => {
    if (!window.confirm(`Xóa luật "${r.name}"? Các cảnh báo đang mở của luật này sẽ được tự đóng.`)) return;
    try { await api(`/alert-rules/${r.id}`, { method: 'DELETE' }); toast('Đã xóa luật', 'success'); load(); } catch (err) { setError(err); }
  };
  const canCreate = isAdmin || devices.length > 0;

  return (
    <div className="stack">
      <div className="page-head">
        <div><h1>Luật cảnh báo</h1><p>Mỗi telemetry mới được so với các luật đang bật. Luật chung áp dụng mọi thiết bị.</p></div>
        {canCreate && <Button onClick={() => { setEdit({ ...EMPTY, deviceCode: isAdmin ? '' : devices[0]?.deviceCode || '' }); setFormError(null); }}>+ Thêm luật</Button>}
      </div>
      <ErrorBox error={error} />
      <Card>
        {!rules ? null : rules.length === 0 ? <Empty /> : (
          <div className="table-wrap"><table>
            <thead><tr><th>Tên luật</th><th>Phạm vi</th><th>Điều kiện</th><th>Mức độ</th><th>Trạng thái</th><th></th></tr></thead>
            <tbody>{rules.map((r) => (
              <tr key={r.id}>
                <td><b>{r.name}</b></td>
                <td>{r.global ? <Badge value="INFO" label="Mọi thiết bị" /> : <code>{r.deviceCode}</code>}</td>
                <td className="mono">{r.metric} {OPS[r.operator]} {fmtNum(r.threshold)} {r.unit}</td>
                <td><Badge value={r.severity} /></td>
                <td>{r.enabled ? <Badge value="ACTIVE" label="Đang bật" /> : <Badge value="NONE" label="Tắt" />}</td>
                <td>{r.canEdit && <><Button small variant="ghost" onClick={() => { setEdit({ ...r, deviceCode: r.deviceCode || '' }); setFormError(null); }}>Sửa</Button>{' '}
                  <Button small variant="ghost" onClick={() => remove(r)}>Xóa</Button></>}</td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </Card>
      {edit && (
        <Modal title={edit.id ? 'Sửa luật cảnh báo' : 'Thêm luật cảnh báo'} onClose={() => setEdit(null)}
               footer={<><Button variant="ghost" onClick={() => setEdit(null)}>Hủy</Button><Button form="rule-form" type="submit">Lưu</Button></>}>
          <form id="rule-form" onSubmit={save}>
            <ErrorBox error={formError} />
            <Field label="Tên luật *"><input value={edit.name} onChange={(e) => setEdit({ ...edit, name: e.target.value })} required /></Field>
            <Field label="Phạm vi" hint={edit.id ? 'Không đổi được phạm vi của luật đã tạo' : 'Luật chung chỉ ADMIN được tạo'}>
              <select value={edit.deviceCode} disabled={!!edit.id} onChange={(e) => setEdit({ ...edit, deviceCode: e.target.value })}>
                {isAdmin && <option value="">Mọi thiết bị (luật chung)</option>}
                {devices.map((d) => <option key={d.deviceCode} value={d.deviceCode}>{d.deviceCode} – {d.name}</option>)}
              </select>
            </Field>
            <div className="grid-3">
              <Field label="Chỉ số">
                <select value={edit.metric} onChange={(e) => setEdit({ ...edit, metric: e.target.value })}>
                  <option value="gas">Khí gas (ppm)</option><option value="temp">Nhiệt độ (°C)</option>
                </select>
              </Field>
              <Field label="Điều kiện">
                <select value={edit.operator} onChange={(e) => setEdit({ ...edit, operator: e.target.value })}>
                  {Object.entries(OPS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </select>
              </Field>
              <Field label="Ngưỡng" hint={edit.metric === 'gas' ? '0 – 10000' : '-55 – 125'}>
                <input type="number" step="any" value={edit.threshold} onChange={(e) => setEdit({ ...edit, threshold: e.target.value })} required />
              </Field>
            </div>
            <Field label="Mức độ">
              <select value={edit.severity} onChange={(e) => setEdit({ ...edit, severity: e.target.value })}>
                <option value="INFO">Thông tin</option><option value="WARNING">Cảnh báo</option><option value="CRITICAL">Nghiêm trọng</option>
              </select>
            </Field>
            <label className="check"><input type="checkbox" checked={edit.enabled} onChange={(e) => setEdit({ ...edit, enabled: e.target.checked })} /> Bật luật</label>
            <Notice kind="info">Cảnh báo không sinh khi thiết bị đang Bảo trì / Tạm khóa.</Notice>
          </form>
        </Modal>
      )}
    </div>
  );
}
