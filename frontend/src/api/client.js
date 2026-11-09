// Gọi REST API của backend. Token JWT lưu trong localStorage (chỉ dùng cho tiện, không chứa mật khẩu).
const TOKEN_KEY = 'gasguard_token';

export function getToken() {
  try { return localStorage.getItem(TOKEN_KEY); } catch { return null; }
}

export function setToken(token) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch { /* trình duyệt chặn storage: vẫn chạy trong phiên hiện tại */ }
}

/** Lỗi từ backend theo định dạng ErrorResponse {status, error, message, fieldErrors}. */
export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || (status === 0 ? 'Không kết nối được máy chủ' : `Lỗi ${status}`));
    this.status = status;
    this.code = body?.error;
    this.fieldErrors = body?.fieldErrors || null;
  }
}

let unauthorizedHandler = () => {};
export function setUnauthorizedHandler(fn) { unauthorizedHandler = fn; }

export async function api(path, { method = 'GET', body, raw = false } = {}) {
  const headers = {};
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let res;
  try {
    res = await fetch(`/api${path}`, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined });
  } catch {
    throw new ApiError(0, null);
  }
  if (!res.ok) {
    let data = null;
    try { data = await res.json(); } catch { /* không phải JSON */ }
    // 401 khi đang có phiên = token hết hạn / bị thu hồi -> đăng xuất. (401 ở /auth/login là sai mật khẩu.)
    if (res.status === 401 && token && !path.startsWith('/auth/login')) unauthorizedHandler(data?.message);
    throw new ApiError(res.status, data);
  }
  if (raw) return res;
  if (res.status === 204) return null;
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

/** Tạo query string, bỏ qua giá trị rỗng. */
export function qs(params) {
  const p = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') p.append(k, v);
  });
  const s = p.toString();
  return s ? `?${s}` : '';
}

/** Tải file (CSV) có kèm token. */
export async function download(path, fallbackName) {
  const res = await api(path, { raw: true });
  const blob = await res.blob();
  const cd = res.headers.get('Content-Disposition') || '';
  const match = cd.match(/filename="([^"]+)"/);
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = match ? match[1] : fallbackName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
