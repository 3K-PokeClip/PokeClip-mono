'use client';

// 시계의 주인은 clip의 방송 명부다.
// 라이브면 시작 시각부터 매초 센다. 지난 방송이면 시작~종료 길이를 고정으로 준다.
// 방송 번호가 없으면 오프라인이다. 결과는 liveDataStore에도 흘려 채팅·통계가 같은 기준을 쓴다.
import { useEffect, useState } from 'react';
import { fetchBroadcast } from '@/api/clipEditor';
import { publishLiveData } from './liveDataStore';

export type BroadcastStatus = 'live' | 'ended' | 'offline' | 'unknown';

export interface BroadcastClock {
  status: BroadcastStatus;
  /** 라이브: 시작부터 지금까지(초) · 지난 방송: 총 길이(초) · 그 외 null */
  uptimeSeconds: number | null;
  startedAt: number | null;
  endedAt: number | null;
  /**
   * 명부에서 찾지 못한 방송 번호 — 그 방송에 대한 답일 때만 값이 있다. 「꺼짐」 상태만 보면 번호가 막 바뀐
   * 순간 앞 번호의 답을 새 번호의 답으로 읽는다(404 판단이 그 틈에 멀쩡한 방송을 없는 것으로 본다).
   */
  missingStreamId: string | null;
}

const POLL_MS = 30_000;

function ms(iso: string | null): number | null {
  if (!iso) return null;
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : t;
}

export function useBroadcastClock(streamId: string): BroadcastClock {
  const [startedAt, setStartedAt] = useState<number | null>(null);
  const [endedAt, setEndedAt] = useState<number | null>(null);
  const [status, setStatus] = useState<BroadcastStatus>('unknown');
  const [missingStreamId, setMissingStreamId] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    setStartedAt(null);
    setEndedAt(null);
    if (!streamId) {
      setStatus('offline');
      publishLiveData({ status: 'offline', startedAt: null, endedAt: null, playheadMs: null });
      return;
    }
    setStatus('unknown');
    publishLiveData({ status: 'unknown', startedAt: null, endedAt: null, playheadMs: null });
    let alive = true;
    const load = async () => {
      try {
        // 목록을 끝까지 넘기며 찾는다 — 한 쪽만 보면 오래된 지난 방송이 「없다」가 된다
        const row = await fetchBroadcast(streamId);
        if (!alive) return;
        if (!row) {
          setMissingStreamId(streamId);
          setStatus('offline');
          publishLiveData({ status: 'offline', startedAt: null, endedAt: null, relation: null });
          return;
        }
        setMissingStreamId(null);
        const s = ms(row.startedAt);
        const e = row.status === 'live' ? null : ms(row.endedAt);
        const st: BroadcastStatus = row.status === 'live' ? 'live' : 'ended';
        setStatus(st);
        setStartedAt(s);
        setEndedAt(e);
        setNow(Date.now());
        publishLiveData({ status: st, startedAt: s, endedAt: e, relation: row.relation });
      } catch {
        /* 다음 주기에 다시 */
      }
    };
    void load();
    const t = setInterval(load, POLL_MS);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, [streamId]);

  useEffect(() => {
    if (startedAt === null || endedAt !== null) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [startedAt, endedAt]);

  let uptimeSeconds: number | null = null;
  if (startedAt !== null) {
    const end = endedAt ?? now;
    uptimeSeconds = Math.max(0, Math.floor((end - startedAt) / 1000));
  }
  return { status, uptimeSeconds, startedAt, endedAt, missingStreamId };
}
