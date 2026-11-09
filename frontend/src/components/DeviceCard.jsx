import { Link } from 'react-router-dom';
import { fmtAgo, fmtNum, actuatorText } from '../utils/format';
import { Badge } from './ui';

/** Thẻ thiết bị trên dashboard: trạng thái + số đo mới nhất (cập nhật realtime). */
export default function DeviceCard({ d, flash, now, gasThreshold = 400 }) {
  const gas = d.latest?.gas;
  const temp = d.latest?.temp;
  const hot = gas && gas.value > gasThreshold;
  return (
    <Link to={`/devices/${d.deviceCode}`} className={`device-card${d.activeAlerts > 0 ? ' danger' : ''}`}>
      <div className="dc-head">
        <div>
          <div className="dc-name">{d.name}</div>
          <div className="dc-code">{d.deviceCode}{d.location ? ` · ${d.location}` : ''}{d.zone ? ` / ${d.zone}` : ''}</div>
        </div>
        <Badge value={d.connectivity} />
      </div>
      <div className="readings">
        <div className={`reading${hot ? ' hot' : ''}${flash ? ' flash' : ''}`}>
          <div className="r-label">Khí gas</div>
          <div className="r-value">{gas ? fmtNum(gas.value) : '—'}<span className="r-unit">ppm</span></div>
        </div>
        <div className={`reading${flash ? ' flash' : ''}`}>
          <div className="r-label">Nhiệt độ</div>
          <div className="r-value">{temp ? fmtNum(temp.value) : '—'}<span className="r-unit">°C</span></div>
        </div>
      </div>
      <div className="row small">
        <Badge value={d.lifecycleStatus} />
        <span className="muted">Quạt: <b>{actuatorText(d.actuatorState, 'fan')}</b> · Còi: <b>{actuatorText(d.actuatorState, 'buzzer')}</b></span>
        <span className="spacer" />
        {d.activeAlerts > 0 && <Badge value="OPEN" label={`${d.activeAlerts} cảnh báo`} />}
      </div>
      <div className="small muted">Cập nhật: {fmtAgo(d.lastSeenAt, now)}</div>
    </Link>
  );
}
