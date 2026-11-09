import { useEffect, useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { useDeviceEvents, useRealtime } from '../realtime/RealtimeContext';
import { Badge } from './ui';
import { useToast } from './Toast';

const NAV = [
  { to: '/', label: 'Dashboard', icon: '◉', end: true },
  { to: '/devices', label: 'Thiết bị', icon: '⌂' },
  { to: '/alerts', label: 'Cảnh báo', icon: '⚠' },
  { to: '/alert-rules', label: 'Luật cảnh báo', icon: '≡' },
  { to: '/users', label: 'Người dùng', icon: '☺', admin: true },
  { to: '/audit', label: 'Audit log', icon: '☰', admin: true },
  { to: '/system', label: 'Hệ thống', icon: '⚙', admin: true },
  { to: '/account', label: 'Tài khoản', icon: '✎' },
];

export default function Layout() {
  const { user, logout, isAdmin } = useAuth();
  const { connected } = useRealtime();
  const toast = useToast();
  const navigate = useNavigate();
  const [codes, setCodes] = useState([]);
  const [menuOpen, setMenuOpen] = useState(false);

  // Theo dõi mọi thiết bị được xem để báo ngay khi có cảnh báo mới (bước 8 demo)
  useEffect(() => {
    api('/devices').then((ds) => setCodes(ds.map((d) => d.deviceCode))).catch(() => setCodes([]));
  }, [user?.id]);
  useDeviceEvents(codes, (m) => {
    if (m.type === 'alert' && (m.change === 'CREATED' || m.change === 'RECURRED')) {
      const a = m.alert;
      toast(`⚠ [${a.deviceCode}] ${a.message}`, a.severity === 'CRITICAL' ? 'danger' : 'warning', 8000);
    }
    if (m.type === 'connectivity' && m.to === 'OFFLINE') toast(`[${m.deviceCode}] mất kết nối (${m.reason})`, 'warning');
  });

  const doLogout = async () => { await logout(); navigate('/login'); };

  return (
    <div className="app">
      <aside className={`sidebar${menuOpen ? ' open' : ''}`}>
        <div className="brand">
          <span className="brand-logo">🔥</span>
          <div><div className="brand-name">GasGuard</div><div className="brand-sub">Giám sát rò rỉ khí gas</div></div>
        </div>
        <nav className="nav">
          {NAV.filter((n) => !n.admin || isAdmin).map((n) => (
            <NavLink key={n.to} to={n.to} end={n.end} onClick={() => setMenuOpen(false)}
                     className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
              <span className="nav-icon">{n.icon}</span>{n.label}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-foot">
          <div className={`rt ${connected ? 'on' : 'off'}`}>{connected ? '● Realtime đang kết nối' : '○ Realtime mất kết nối'}</div>
        </div>
      </aside>
      <div className="main">
        <header className="topbar">
          <button className="icon-btn menu-btn" onClick={() => setMenuOpen((v) => !v)} aria-label="Menu">☰</button>
          <div className="spacer" />
          <div className="who">
            <span>{user.fullName || user.username}</span>
            <Badge value={user.role} />
          </div>
          <button className="btn btn-ghost btn-sm" onClick={doLogout}>Đăng xuất</button>
        </header>
        <main className="content"><Outlet /></main>
      </div>
    </div>
  );
}
