import { useCallback, useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import { Badge, ErrorBox, Loading, Tabs } from '../components/ui';
import { useDeviceEvents } from '../realtime/RealtimeContext';
import ActivityTab from './device/ActivityTab';
import AlertsTab from './device/AlertsTab';
import ConfigTab from './device/ConfigTab';
import ControlTab from './device/ControlTab';
import CredentialsTab from './device/CredentialsTab';
import DataTab from './device/DataTab';
import OverviewTab from './device/OverviewTab';
import SharesTab from './device/SharesTab';

/** Chi tiết thiết bị: thông tin, trạng thái, dữ liệu, điều khiển, cấu hình, cảnh báo, quyền, lịch sử. */
export default function DeviceDetailPage() {
  const { code } = useParams();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') || 'overview';
  const [device, setDevice] = useState(null);
  const [error, setError] = useState(null);
  const [lastEvent, setLastEvent] = useState(null);

  const load = useCallback(() => {
    api(`/devices/${code}`).then((d) => { setDevice(d); setError(null); }).catch(setError);
  }, [code]);
  useEffect(() => { load(); }, [load]);

  useDeviceEvents(device ? [device.deviceCode] : [], (m) => {
    setLastEvent(m);
    if (['connectivity', 'lifecycle', 'config', 'command', 'event', 'state'].includes(m.type)) load();
    if (m.type === 'telemetry') setDevice((d) => d && { ...d, lastSeenAt: m.receivedAt });
  });

  if (error) return <div className="stack"><Link to="/devices">‹ Danh sách thiết bị</Link><ErrorBox error={error} /></div>;
  if (!device) return <Loading />;

  const tabs = [
    { key: 'overview', label: 'Tổng quan' },
    { key: 'data', label: 'Dữ liệu & biểu đồ' },
    { key: 'control', label: 'Điều khiển' },
    { key: 'config', label: 'Cấu hình' },
    { key: 'alerts', label: 'Cảnh báo' },
    ...(device.canManage ? [{ key: 'credentials', label: 'Xác thực MQTT' }, { key: 'shares', label: 'Chia sẻ' }] : []),
    { key: 'activity', label: 'Lịch sử hoạt động' },
  ];
  const props = { device, reload: load, lastEvent };

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <Link to="/devices" className="small">‹ Danh sách thiết bị</Link>
          <h1 style={{ marginTop: 6 }}>{device.name} <span className="muted" style={{ fontWeight: 400 }}>({device.deviceCode})</span></h1>
          <div className="row" style={{ marginTop: 8 }}>
            <Badge value={device.lifecycleStatus} />
            <Badge value={device.connectivity} />
            {!device.canManage && <Badge value="VIEWER" label="Bạn chỉ có quyền xem" />}
          </div>
        </div>
      </div>
      <Tabs tabs={tabs} active={tab} onChange={(k) => setParams({ tab: k }, { replace: true })} />
      {tab === 'overview' && <OverviewTab {...props} />}
      {tab === 'data' && <DataTab {...props} />}
      {tab === 'control' && <ControlTab {...props} />}
      {tab === 'config' && <ConfigTab {...props} />}
      {tab === 'alerts' && <AlertsTab {...props} />}
      {tab === 'credentials' && device.canManage && <CredentialsTab {...props} />}
      {tab === 'shares' && device.canManage && <SharesTab {...props} />}
      {tab === 'activity' && <ActivityTab {...props} />}
    </div>
  );
}
