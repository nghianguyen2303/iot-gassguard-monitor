import { Client } from '@stomp/stompjs';
import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { getToken } from '../api/client';
import { useAuth } from '../auth/AuthContext';

/**
 * Một kết nối STOMP dùng chung cho cả ứng dụng.
 * - CONNECT gửi JWT; backend kiểm tra quyền khi subscribe /topic/devices/{code}.
 * - Tự kết nối lại sau 3 s và subscribe lại mọi thiết bị đang được theo dõi.
 */
const RealtimeContext = createContext(null);

export function RealtimeProvider({ children }) {
  const { user } = useAuth();
  const clientRef = useRef(null);
  const listeners = useRef(new Map());   // deviceCode -> Set<callback>
  const subs = useRef(new Map());        // deviceCode -> StompSubscription
  const [connected, setConnected] = useState(false);

  const stompSubscribe = useCallback((code) => {
    const c = clientRef.current;
    if (!c || !c.connected || subs.current.has(code)) return;
    const s = c.subscribe(`/topic/devices/${code}`, (frame) => {
      let data;
      try { data = JSON.parse(frame.body); } catch { return; }
      (listeners.current.get(code) || []).forEach((cb) => cb(data));
    });
    subs.current.set(code, s);
  }, []);

  useEffect(() => {
    if (!user) return undefined;
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const client = new Client({
      brokerURL: `${proto}://${window.location.host}/ws`,
      connectHeaders: { Authorization: `Bearer ${getToken()}` },
      reconnectDelay: 3000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        setConnected(true);
        subs.current.clear();
        for (const code of listeners.current.keys()) stompSubscribe(code);
      },
      onWebSocketClose: () => { setConnected(false); subs.current.clear(); },
      onStompError: (f) => console.warn('Realtime bị từ chối:', f.headers?.message),
    });
    clientRef.current = client;
    client.activate();
    return () => {
      client.deactivate();
      clientRef.current = null;
      subs.current.clear();
      setConnected(false);
    };
  }, [user?.id, stompSubscribe]); // eslint-disable-line react-hooks/exhaustive-deps

  const subscribe = useCallback((code, cb) => {
    let set = listeners.current.get(code);
    if (!set) { set = new Set(); listeners.current.set(code, set); }
    set.add(cb);
    stompSubscribe(code);
    return () => {
      set.delete(cb);
      if (set.size === 0) {
        listeners.current.delete(code);
        const s = subs.current.get(code);
        if (s) { try { s.unsubscribe(); } catch { /* đã ngắt */ } subs.current.delete(code); }
      }
    };
  }, [stompSubscribe]);

  return <RealtimeContext.Provider value={{ connected, subscribe }}>{children}</RealtimeContext.Provider>;
}

export const useRealtime = () => useContext(RealtimeContext);

/** Nhận sự kiện realtime của danh sách thiết bị. handler(data) với data.type = telemetry|connectivity|... */
export function useDeviceEvents(codes, handler) {
  const { subscribe } = useRealtime();
  const ref = useRef(handler);
  ref.current = handler;
  const key = (codes || []).filter(Boolean).join(',');
  useEffect(() => {
    if (!key) return undefined;
    const offs = key.split(',').map((c) => subscribe(c, (d) => ref.current(d)));
    return () => offs.forEach((off) => off());
  }, [key, subscribe]);
}
