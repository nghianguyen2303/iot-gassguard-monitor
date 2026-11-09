const TZ = 'Asia/Ho_Chi_Minh';

const dateTimeFmt = new Intl.DateTimeFormat('vi-VN', {
  timeZone: TZ, hour: '2-digit', minute: '2-digit', second: '2-digit',
  day: '2-digit', month: '2-digit', year: 'numeric', hour12: false,
});
const timeFmt = new Intl.DateTimeFormat('vi-VN', { timeZone: TZ, hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
const shortFmt = new Intl.DateTimeFormat('vi-VN', { timeZone: TZ, hour: '2-digit', minute: '2-digit', day: '2-digit', month: '2-digit', hour12: false });

export const fmtDateTime = (iso) => (iso ? dateTimeFmt.format(new Date(iso)) : '—');
export const fmtTime = (iso) => (iso ? timeFmt.format(new Date(iso)) : '—');
export const fmtShort = (iso) => (iso ? shortFmt.format(new Date(iso)) : '—');

export function fmtAgo(iso, now = Date.now()) {
  if (!iso) return 'chưa có';
  const s = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000));
  if (s < 60) return `${s} giây trước`;
  if (s < 3600) return `${Math.floor(s / 60)} phút trước`;
  if (s < 86400) return `${Math.floor(s / 3600)} giờ trước`;
  return `${Math.floor(s / 86400)} ngày trước`;
}

export function fmtNum(v, digits = 1) {
  if (v === null || v === undefined || Number.isNaN(v)) return '—';
  return Number(v).toLocaleString('vi-VN', { maximumFractionDigits: digits });
}

export const METRIC = {
  gas: { label: 'Khí gas', unit: 'ppm', color: '#dc2626' },
  temp: { label: 'Nhiệt độ', unit: '°C', color: '#2563eb' },
};

/** Chuyển Date sang chuỗi cho <input type="datetime-local"> theo giờ máy. */
export function toLocalInput(date) {
  const d = new Date(date);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Giá trị datetime-local (giờ máy) -> ISO UTC cho API. */
export const fromLocalInput = (v) => (v ? new Date(v).toISOString() : undefined);
/** Ô datetime-local chỉ chính xác tới phút: mốc "Đến" lấy hết phút đó (hh:mm:59.999) để không bỏ sót dữ liệu mới nhất. */
export const toLocalInputEnd = (v) => (v ? new Date(new Date(v).getTime() + 59999).toISOString() : undefined);

/** Trạng thái quạt/còi: "BẬT" / "TẮT" / "—", kèm "(tay)" khi người dùng đang giữ chế độ thủ công. */
export function actuatorText(state, key) {
  const v = state?.[key];
  const text = v === true ? (key === 'buzzer' ? 'KÊU' : 'BẬT') : v === false ? (key === 'buzzer' ? 'IM' : 'TẮT') : '—';
  return state?.[`${key}Mode`] === 'MANUAL' && v != null ? `${text} (tay)` : text;
}
