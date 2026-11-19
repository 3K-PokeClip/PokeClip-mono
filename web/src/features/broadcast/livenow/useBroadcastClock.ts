'use client';

// 시계의 주인은 clip의 방송 명부다.
// 라이브면 시작 시각부터 매초 센다. 지난 방송이면 시작~종료 길이를 고정으로 준다.
// 방송 번호가 없으면 오프라인이다. 결과는 liveDataStore에도 흘려 채팅·통계가 같은 기준을 쓴다.
import { useEffect, useRef, useState } from 'react';
import { fetchBroadcast } from '@/api/clipEditor';
import { setVisibleInterval } from '@/lib/visibleInterval';
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
  /**
   * 영상 경로의 키(POK-233) — 라이브·녹화 주소에 쓴다. 명부 답을 받기 전이거나 옛 서버면 null이고, 그때는 방송 번호가 곧 영상
   * 경로다. 방송 번호가 바뀌면 곧바로 null로 돌아간다(앞 방송의 키로 새 방송 영상을 찾지 않게)
   */
  ingestStreamId: string | null;
}

const POLL_MS = 30_000;
/**
 * 끝난 방송의 시각이 다 찬 뒤 명부를 다시 보는 간격(끝난 방송의 카드 목록 주기와 같다). 멈추지는 않는다: 종료 편지가 늦게 오면 서버가
 * 끝 시각을 고치고(무음 종료 뒤 applyEnded), 권한이 회수되면 이 주기가 줄이 없다고 알려야 상세 화면이 404로 간다
 */
const SETTLED_POLL_MS = 60_000;
/**
 * 끝남 대기의 재접속 대기 동안 명부를 다시 보는 간격(POK-612). 종료 신호(4D)면 명부는 정지 뒤 수십 초 안에 ended 가 되고
 * (2026-11-19 prod 21초), 덮개 문구가 그 답을 기다린다. 켜고 끄는 쪽(useLiveSession)이 재접속 대기(360초) 안 · 명부가 아직
 * ended 가 아닐 때만 켠다
 */
const ENDING_WAIT_POLL_MS = 5_000;

interface BroadcastClockOptions {
  /**
   * 명부를 빨리 다시 읽는다 — 호출자가 끝남 대기의 재접속 대기 안이고 명부가 아직 ended 가 아닐 때 켠다. 켜지면 곧바로 한 번 ·
   * 그 뒤 ENDING_WAIT_POLL_MS 주기
   */
  rosterFastPoll?: boolean;
}

/** 방송 번호 effect 가 만든 지금 방송의 명부 읽기 — 주기 전환 effect 가 이것으로 곧바로 읽고 주기를 바꾼다 */
interface RosterPoller {
  load: (fresh: boolean) => Promise<void>;
  /** 지금 조건(빠른 읽기 · 시각이 다 찼나)에 맞는 주기로 바꾼다. 같은 주기면 그대로 둔다 */
  retime: () => void;
}

function ms(iso: string | null): number | null {
  if (!iso) return null;
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : t;
}

export function useBroadcastClock(
  streamId: string,
  { rosterFastPoll = false }: BroadcastClockOptions = {},
): BroadcastClock {
  const [startedAt, setStartedAt] = useState<number | null>(null);
  const [endedAt, setEndedAt] = useState<number | null>(null);
  const [status, setStatus] = useState<BroadcastStatus>('unknown');
  const [missingStreamId, setMissingStreamId] = useState<string | null>(null);
  const [ingestStreamId, setIngestStreamId] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());
  // 주기 전환은 방송 번호 effect 를 다시 돌리지 않는다: 다시 돌리면 status · 시각을 「모름」 으로 되돌린다(POK-612)
  const rosterFastPollRef = useRef(rosterFastPoll);
  const pollerRef = useRef<RosterPoller | null>(null);

  useEffect(() => {
    setStartedAt(null);
    setEndedAt(null);
    setIngestStreamId(null);
    if (!streamId) {
      setStatus('offline');
      publishLiveData({
        status: 'offline',
        startedAt: null,
        endedAt: null,
        playheadMs: null,
        originMs: null,
        listTitle: null,
      });
      return;
    }
    setStatus('unknown');
    publishLiveData({
      status: 'unknown',
      startedAt: null,
      endedAt: null,
      playheadMs: null,
      originMs: null,
      listTitle: null,
    });
    let alive = true;
    let stopPolling = () => {};
    let settled = false;
    let periodMs = 0;
    const retime = () => {
      const next = rosterFastPollRef.current
        ? ENDING_WAIT_POLL_MS
        : settled
          ? SETTLED_POLL_MS
          : POLL_MS;
      if (next === periodMs) return;
      periodMs = next;
      stopPolling();
      stopPolling = setVisibleInterval(() => void load(true), next);
    };
    const load = async (fresh: boolean) => {
      try {
        // 목록을 끝까지 넘기며 찾는다 — 한 쪽만 보면 오래된 지난 방송이 「없다」가 된다. 첫 읽기는 다른 화면이 읽어 둔 줄을
        // 쓰고, 주기는 건너뛴다: 라이브가 끝났는지 보는 것이 주기의 일이다
        const row = await fetchBroadcast(streamId, undefined, { fresh });
        if (!alive) return;
        if (!row) {
          setMissingStreamId(streamId);
          setStatus('offline');
          // 볼 수 없게 된 방송(위임 회수·탈퇴)은 시각도 비운다: 남기면 「방송 중이 아니에요」 옆에서 경과가 흘렀다(리뷰 5라운드)
          setStartedAt(null);
          setEndedAt(null);
          setIngestStreamId(null);
          publishLiveData({
            status: 'offline',
            startedAt: null,
            endedAt: null,
            relation: null,
            originMs: null,
            listTitle: null,
          });
          return;
        }
        setMissingStreamId(null);
        const s = ms(row.startedAt);
        const e = row.status === 'live' ? null : ms(row.endedAt);
        const st: BroadcastStatus = row.status === 'live' ? 'live' : 'ended';
        setStatus(st);
        setStartedAt(s);
        setEndedAt(e);
        setIngestStreamId(row.ingestStreamId || null);
        setNow(Date.now());
        // 시각 기준점(POK-255)은 방송 중에는 첫 조각이 올라온 뒤에야 생기므로 주기마다 다시 싣는다
        publishLiveData({
          status: st,
          startedAt: s,
          endedAt: e,
          relation: row.relation,
          originMs: ms(row.timelineOriginAt ?? null),
          listTitle: row.title?.trim() || null,
          channelNames: row.channelNames ?? null,
        });
        // 끝난 방송이고 시작·끝 시각과 기준점이 다 왔으면 주기를 늦춘다. 기준점(POK-255)은 끝난 뒤에도 조각이 올라가는 동안
        // 늦게 채워질 수 있어, 비어 있으면 올 때까지 빠른 주기로 묻는다
        if (
          !settled &&
          st === 'ended' &&
          s !== null &&
          e !== null &&
          ms(row.timelineOriginAt ?? null) !== null
        ) {
          settled = true;
          retime();
        }
      } catch {
        /* 다음 주기에 다시 */
      }
    };
    void load(false);
    retime();
    pollerRef.current = { load, retime };
    return () => {
      alive = false;
      pollerRef.current = null;
      stopPolling();
    };
  }, [streamId]);

  useEffect(() => {
    if (rosterFastPollRef.current === rosterFastPoll) return;
    rosterFastPollRef.current = rosterFastPoll;
    const poller = pollerRef.current;
    if (poller === null) return;
    if (rosterFastPoll) void poller.load(true);
    poller.retime();
  }, [rosterFastPoll]);

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
  return { status, uptimeSeconds, startedAt, endedAt, missingStreamId, ingestStreamId };
}
