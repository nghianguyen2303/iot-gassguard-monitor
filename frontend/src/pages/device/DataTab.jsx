import { useCallback, useEffect, useState } from 'react';
import { Area, CartesianGrid, ComposedChart, Legend, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api, download, qs } from '../../api/client';
import { Button, Card, Empty, ErrorBox, Field, Loading } from '../../components/ui';
import { useToast } from '../../components/Toast';
import { fmtDateTime, fmtNum, fmtShort, fromLocalInput, METRIC, toLocalInput, toLocalInputEnd } from '../../utils/format';

const PRESETS = [
  { key: '1h', label: '1 giờ', ms: 3600e3 },
  { key: '6h', label: '6 giờ', ms: 6 * 3600e3 },
  { key: '24h', label: '24 giờ', ms: 24 * 3600e3 },
  { key: '7d', label: '7 ngày', ms: 7 * 86400e3 },
  { key: '30d', label: '30 ngày', ms: 30 * 86400e3 },
];

/** Tra cứu theo khoảng thời gian, biểu đồ gộp, thống kê, bảng dữ liệu thô, xuất CSV (mục 4.4c). */
export default function DataTab({ device }) {
  const code = device.deviceCode;
  const toast = useToast();
  const [range, setRange] = useState(() => ({ from: toLocalInput(Date.now() - 24 * 3600e3), to: toLocalInput(Date.now()) }));
  const [metric, setMetric] = useState('gas');
  const [bucket, setBucket] = useState('auto');
  const [series, setSeries] = useState(null);
  const [stats, setStats] = useState(null);
  const [raw, setRaw] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  const preset = (ms) => setRange({ from: toLocalInput(Date.now() - ms), to: toLocalInput(Date.now()) });

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    const from = fromLocalInput(range.from);
    const to = toLocalInputEnd(range.to);
    try {
      const [s, st, r] = await Promise.all([
        api(`/devices/${code}/telemetry/series${qs({ metric, from, to, bucket })}`),
        api(`/devices/${code}/telemetry/stats${qs({ from, to })}`),
        api(`/devices/${code}/telemetry${qs({ metric: 'all', from, to: to, limit: 200 })}`).catch((e) => ({ error: e })),
      ]);
      setSeries(s);
      setStats(st);
      setRaw(r);
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  }, [code, range, metric, bucket]);

  useEffect(() => { load(); }, [load]);

  const exportCsv = async () => {
    try {
      await download(`/devices/${code}/telemetry/export.csv${qs({ from: fromLocalInput(range.from), to: toLocalInputEnd(range.to) })}`, `${code}.csv`);
      toast('Đã xuất CSV', 'success');
    } catch (e) {
      toast(`Xuất CSV thất bại: ${e.message}`, 'danger');
    }
  };

  const m = METRIC[metric];
  const chartRows = (series?.points || []).map((p) => ({ t: new Date(p.bucket).getTime(), avg: p.avg, range: [p.min, p.max], count: p.count }));

  return (
    <div className="stack">
      <Card>
        <div className="filters">
          <Field label="Từ"><input type="datetime-local" value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} /></Field>
          <Field label="Đến"><input type="datetime-local" value={range.to} onChange={(e) => setRange({ ...range, to: e.target.value })} /></Field>
          <Field label="Nhanh">
            <div className="row">{PRESETS.map((p) => <Button key={p.key} variant="ghost" small onClick={() => preset(p.ms)}>{p.label}</Button>)}</div>
          </Field>
          <Field label="Chỉ số">
            <select value={metric} onChange={(e) => setMetric(e.target.value)}>
              <option value="gas">Khí gas (ppm)</option><option value="temp">Nhiệt độ (°C)</option>
            </select>
          </Field>
          <Field label="Gộp theo">
            <select value={bucket} onChange={(e) => setBucket(e.target.value)}>
              <option value="auto">Tự động</option><option value="1m">1 phút</option><option value="5m">5 phút</option>
              <option value="15m">15 phút</option><option value="1h">1 giờ</option><option value="1d">1 ngày</option>
            </select>
          </Field>
          <Button onClick={load} disabled={loading}>{loading ? 'Đang tải...' : 'Xem'}</Button>
          <Button variant="ghost" onClick={exportCsv}>⬇ Xuất CSV</Button>
        </div>
        <ErrorBox error={error} />
      </Card>

      <Card title={`${m.label} – trung bình và khoảng min/max (gộp ${series?.bucket || '...'})`}>
        {!series ? <Loading /> : chartRows.length === 0 ? <Empty>Không có dữ liệu trong khoảng này</Empty> : (
          <div className="chart-box tall">
            <ResponsiveContainer>
              <ComposedChart data={chartRows} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                <XAxis dataKey="t" type="number" scale="time" domain={['dataMin', 'dataMax']} tickFormatter={(t) => fmtShort(t)} minTickGap={50} />
                <YAxis label={{ value: m.unit, angle: -90, position: 'insideLeft' }} />
                <Tooltip labelFormatter={(t) => fmtDateTime(t)}
                         formatter={(v, n) => [Array.isArray(v) ? `${fmtNum(v[0])} – ${fmtNum(v[1])}` : fmtNum(v), n]} />
                <Legend />
                <Area dataKey="range" name="Min – Max" stroke="none" fill={m.color} fillOpacity={0.12} isAnimationActive={false} />
                <Line dataKey="avg" name={`Trung bình (${m.unit})`} stroke={m.color} dot={false} isAnimationActive={false} />
              </ComposedChart>
            </ResponsiveContainer>
          </div>
        )}
      </Card>

      <Card title="Thống kê trong khoảng thời gian">
        {!stats ? <Loading /> : stats.metrics.length === 0 ? <Empty>Không có dữ liệu</Empty> : (
          <>
            <div className="table-wrap"><table>
              <thead><tr><th>Chỉ số</th><th className="num">Số mẫu</th><th className="num">Min</th><th className="num">Max</th>
                <th className="num">Trung bình</th><th className="num">P95</th><th>Mẫu đầu</th><th>Mẫu cuối</th><th className="num">Độ trễ TB (ms)</th></tr></thead>
              <tbody>{stats.metrics.map((s) => (
                <tr key={s.metric}>
                  <td><b>{METRIC[s.metric]?.label || s.metric}</b> <span className="muted">({s.unit})</span></td>
                  <td className="num">{s.count.toLocaleString('vi-VN')}</td>
                  <td className="num">{fmtNum(s.min)}</td><td className="num">{fmtNum(s.max)}</td>
                  <td className="num">{fmtNum(s.avg, 2)}</td><td className="num">{fmtNum(s.p95, 2)}</td>
                  <td className="small">{fmtDateTime(s.firstTs)}</td><td className="small">{fmtDateTime(s.lastTs)}</td>
                  <td className="num">{fmtNum(s.avgLatencyMs, 1)}</td>
                </tr>
              ))}</tbody>
            </table></div>
            <p className="small muted" style={{ marginBottom: 0 }}>
              Cảnh báo phát sinh trong khoảng: <b>{stats.alertsInRange}</b>
              {stats.expectedMessages != null && <> · Tỉ lệ mất bản tin (theo seq): <b>{fmtNum(stats.estimatedLossRate, 2)}%</b> ({stats.metrics.find((x) => x.metric === 'gas')?.count} / {stats.expectedMessages} bản tin gas)</>}
            </p>
          </>
        )}
      </Card>

      <Card title="Dữ liệu thô (200 bản ghi mới nhất trong khoảng)">
        {!raw ? <Loading /> : raw.error ? <ErrorBox error={raw.error} /> : raw.points.length === 0 ? <Empty /> : (
          <>
            {raw.truncated && <p className="small muted">Còn nhiều bản ghi hơn – dùng “Xuất CSV” để lấy đầy đủ.</p>}
            <div className="table-wrap" style={{ maxHeight: 420 }}><table>
              <thead><tr><th>Thời điểm đo</th><th>Chỉ số</th><th className="num">Giá trị</th><th>Đơn vị</th><th className="num">seq</th><th className="num">Độ trễ (ms)</th></tr></thead>
              <tbody>{raw.points.map((p, i) => (
                <tr key={`${p.ts}-${p.metric}-${i}`}>
                  <td className="small">{fmtDateTime(p.ts)}</td><td>{METRIC[p.metric]?.label || p.metric}</td>
                  <td className="num">{fmtNum(p.value, 2)}</td><td>{p.unit}</td><td className="num">{p.seq ?? '—'}</td><td className="num">{p.latencyMs}</td>
                </tr>
              ))}</tbody>
            </table></div>
          </>
        )}
      </Card>
    </div>
  );
}
