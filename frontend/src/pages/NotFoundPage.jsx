import { Link } from 'react-router-dom';

export default function NotFoundPage() {
  return <div className="empty"><h2>Không tìm thấy trang</h2><p><Link to="/">Về Dashboard</Link></p></div>;
}
