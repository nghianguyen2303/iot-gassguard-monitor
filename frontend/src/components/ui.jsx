import { useEffect } from 'react';

const BADGE = {
  ONLINE: 'green', OFFLINE: 'gray', FAULT: 'orange',
  REGISTERED: 'gray', PROVISIONED: 'blue', ACTIVE: 'green', MAINTENANCE: 'amber', SUSPENDED: 'red', DECOMMISSIONED: 'dark',
  CRITICAL: 'red', WARNING: 'amber', INFO: 'blue',
  OPEN: 'red', ACKNOWLEDGED: 'amber', RESOLVED: 'green',
  PENDING: 'amber', SENT: 'blue', SUCCESS: 'green', FAILED: 'red', TIMEOUT: 'orange',
  APPLIED: 'green', REJECTED: 'red', NONE: 'gray',
  ADMIN: 'purple', OWNER: 'blue', VIEWER: 'gray', LOCKED: 'red',
  DENIED: 'red', REVOKED: 'gray', DISABLED: 'orange',
};

export const LABEL = {
  ONLINE: 'Trực tuyến', OFFLINE: 'Mất kết nối', FAULT: 'Lỗi cảm biến',
  REGISTERED: 'Đã đăng ký', PROVISIONED: 'Đã cấp quyền', ACTIVE: 'Hoạt động', MAINTENANCE: 'Bảo trì',
  SUSPENDED: 'Tạm khóa', DECOMMISSIONED: 'Ngừng sử dụng',
  CRITICAL: 'Nghiêm trọng', WARNING: 'Cảnh báo', INFO: 'Thông tin',
  OPEN: 'Mới', ACKNOWLEDGED: 'Đang xử lý', RESOLVED: 'Đã xử lý',
  PENDING: 'Chờ gửi', SENT: 'Đã gửi, chờ ACK', SUCCESS: 'Thành công', FAILED: 'Thất bại', TIMEOUT: 'Hết hạn chờ',
  APPLIED: 'Đã áp dụng', REJECTED: 'Bị từ chối', NONE: 'Chưa cấu hình',
  ADMIN: 'Quản trị viên', OWNER: 'Chủ thiết bị', VIEWER: 'Người xem', LOCKED: 'Bị khóa', DENIED: 'Bị từ chối',
  THRESHOLD: 'Vượt ngưỡng', REVOKED: 'Đã thu hồi', DISABLED: 'Đang khóa',
};

export function Badge({ value, label, title }) {
  if (!value) return null;
  return <span className={`badge badge-${BADGE[value] || 'gray'}`} title={title}>{label || LABEL[value] || value}</span>;
}

export function Button({ variant = 'primary', small, children, ...rest }) {
  return <button className={`btn btn-${variant}${small ? ' btn-sm' : ''}`} {...rest}>{children}</button>;
}

export function Card({ title, actions, children, className = '' }) {
  return (
    <section className={`card ${className}`}>
      {(title || actions) && (
        <header className="card-head">
          {title && <h3>{title}</h3>}
          {actions && <div className="card-actions">{actions}</div>}
        </header>
      )}
      {children}
    </section>
  );
}

export function ErrorBox({ error }) {
  if (!error) return null;
  const fields = error.fieldErrors ? Object.entries(error.fieldErrors) : [];
  return (
    <div className="alert-box error" role="alert">
      <strong>{error.status ? `[${error.status}] ` : ''}{error.message}</strong>
      {fields.length > 0 && <ul>{fields.map(([k, v]) => <li key={k}><code>{k}</code>: {v}</li>)}</ul>}
    </div>
  );
}

export const Notice = ({ kind = 'info', children }) => <div className={`alert-box ${kind}`}>{children}</div>;
export const Loading = ({ text = 'Đang tải...' }) => <div className="muted pad">{text}</div>;
export const Empty = ({ children = 'Không có dữ liệu' }) => <div className="empty">{children}</div>;

export function Modal({ title, onClose, children, footer, wide }) {
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose?.();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose?.()}>
      <div className={`modal${wide ? ' modal-wide' : ''}`} role="dialog" aria-modal="true">
        <header className="modal-head"><h3>{title}</h3><button className="icon-btn" onClick={onClose} aria-label="Đóng">×</button></header>
        <div className="modal-body">{children}</div>
        {footer && <footer className="modal-foot">{footer}</footer>}
      </div>
    </div>
  );
}

export function Field({ label, hint, error, children }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {hint && !error && <span className="field-hint">{hint}</span>}
      {error && <span className="field-error">{error}</span>}
    </label>
  );
}

export function Tabs({ tabs, active, onChange }) {
  return (
    <nav className="tabs">
      {tabs.map((t) => (
        <button key={t.key} className={`tab${active === t.key ? ' active' : ''}`} onClick={() => onChange(t.key)}>
          {t.label}{t.count ? <span className="tab-count">{t.count}</span> : null}
        </button>
      ))}
    </nav>
  );
}

export function Pagination({ page, size, total, onPage }) {
  const pages = Math.max(1, Math.ceil(total / size));
  if (total <= size) return <div className="muted small">{total} bản ghi</div>;
  return (
    <div className="pagination">
      <Button variant="ghost" small disabled={page <= 0} onClick={() => onPage(page - 1)}>‹ Trước</Button>
      <span className="small">Trang {page + 1}/{pages} · {total} bản ghi</span>
      <Button variant="ghost" small disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>Sau ›</Button>
    </div>
  );
}

export function StatTile({ label, value, sub, tone }) {
  return (
    <div className={`stat-tile${tone ? ` tone-${tone}` : ''}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {sub && <div className="stat-sub">{sub}</div>}
    </div>
  );
}
