import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import Layout from './components/Layout';
import { Loading } from './components/ui';
import { RealtimeProvider } from './realtime/RealtimeContext';
import AccountPage from './pages/AccountPage';
import AlertRulesPage from './pages/AlertRulesPage';
import AlertsPage from './pages/AlertsPage';
import AuditPage from './pages/AuditPage';
import DashboardPage from './pages/DashboardPage';
import DeviceDetailPage from './pages/DeviceDetailPage';
import DevicesPage from './pages/DevicesPage';
import LoginPage from './pages/LoginPage';
import NotFoundPage from './pages/NotFoundPage';
import RegisterPage from './pages/RegisterPage';
import SystemPage from './pages/SystemPage';
import UsersPage from './pages/UsersPage';

function RequireAuth({ children }) {
  const { user, loading } = useAuth();
  const location = useLocation();
  if (loading) return <Loading />;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return children;
}

/*
 * Các trang quản trị (Người dùng, Audit, Hệ thống) chỉ hiện trong menu với ADMIN.
 * Nếu người khác gõ thẳng URL, trang vẫn mở nhưng mọi API bị backend trả 403 và hiển thị lỗi:
 * quyền được kiểm tra ở BACKEND, không chỉ ẩn trên giao diện (mục 4.5, bước 9 của demo).
 */
export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route element={<RequireAuth><RealtimeProvider><Layout /></RealtimeProvider></RequireAuth>}>
        <Route index element={<DashboardPage />} />
        <Route path="devices" element={<DevicesPage />} />
        <Route path="devices/:code" element={<DeviceDetailPage />} />
        <Route path="alerts" element={<AlertsPage />} />
        <Route path="alert-rules" element={<AlertRulesPage />} />
        <Route path="users" element={<UsersPage />} />
        <Route path="audit" element={<AuditPage />} />
        <Route path="system" element={<SystemPage />} />
        <Route path="account" element={<AccountPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
