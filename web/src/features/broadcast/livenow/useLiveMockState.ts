'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ApiError, apiFetch } from '@/api/client';
import { chzzkLinkQueryOptions } from '@/api/chzzkLink';
import { fetchAllJumpCards } from '@/api/clipEditor';
import { useMe } from '@/features/auth/useSession';
import { useAuthStore } from '@/stores/auth';
import { emitRelay, publishLiveData, useLiveData, type RelayEvent } from './liveDataStore';

// 디자인 1b 라이브 대시보드의 상태. clip 창구에 붙어 있다(POK-251): 카드 목록(GET jump-cards) +
// 실시간(SSE events: 카드·중계 채팅·방송 정보) + 채팅량 차트(chat-chart) + 방송 정보(broadcast-info).
// 이름에 Mock이 남은 것은 화면 쪽 import를 안 흔들기 위해서다.
//
// ⚠ 반환 형태는 계약이다 (POK-180 SSE 실연동이 "화면은 그대로, 훅 내부만" 전제로 선다).
// 값은 시안을 따라 바꿔도 되지만 필드·유니온을 늘리면 저쪽이 채울 수 없는 자리가 생긴다.
// 시안이 요구하는데 여기 없는 표기값은 useLiveDetailsMockState로 간다.

export interface LiveStream {
  title: string;
  platform: string;
  channelName: string;
  startedNote: string;
  /** 플레이어 시뮬레이션 초기값 — 1:24:03 */
  uptimeSeconds: number;
  uptimeLabel: string;
  viewers: string;
  editorName: string;
}

export type HighlightStatus =
  'scored' | 'manual' | 'editing' | 'clipped' | 'unprocessed' | 'expired';

/** 전체/자동/수동 필터의 기준 */
export type HighlightSource = 'auto' | 'manual';

export interface LiveHighlight {
  id: string;
  timestamp: string;
  title: string;
  meta: string;
  status: HighlightStatus;
  source: HighlightSource;
  /** 채점 점수 — 채점 전(unprocessed)·수동 카드에는 없다 */
  score?: number;
  /** editing일 때 편집 중인 사람 */
  editorName?: string;
  /** 내가 집은 카드인가 — 그러면 「편집 중」이어도 다시 들어갈 수 있어야 한다 */
  claimedByMe?: boolean;
  /** 방금 감지된 카드 — 마젠타 틴트로 강조 */
  emphasized?: boolean;
}

export interface ChatVolumeSeries {
  /** SVG 좌표계(0..800 × 0..90)의 꺾은선 점들 */
  points: ReadonlyArray<readonly [number, number]>;
  /** 자동 감지 시점 마커 */
  markers: ReadonlyArray<readonly [number, number]>;
  timeLabels: readonly string[];
}

export interface LiveMockState {
  stream: LiveStream;
  highlights: LiveHighlight[];
  hiddenCount: number;
  chatVolume: ChatVolumeSeries;
  /** 채팅 수집 끊김 경고 배너 노출 여부 */
  chatWarning: boolean;
}

// ── clip 창구 배선 ────────────────────────────────────────────────────────────
// 반환 형태(LiveMockState)는 그대로다 — 화면은 손대지 않는다.

interface Snapshot {
  id: number;
  streamId: string;
  source: string;
  streamTimestampMs: number;
  window: { startMs: number; endMs: number };
  score: number | null;
  evidence: Record<string, unknown> | null;
  claimedBy: string | null;
  hidden: boolean;
  eventSeq: number;
  createdAt: string;
}
interface BroadcastInfoResponse {
  latest: {
    title: string | null;
    tags: string[];
    category: string | null;
    viewers?: number | null;
  } | null;
  series: { observedAt: string; viewers: number | null }[];
}
interface ChartBucket {
  start: string;
  chats: number;
  donations: number;
}
interface ChartPage {
  bucketSeconds: number;
  buckets: ChartBucket[];
  appliedOffsetMs: number;
}

const CHART_MINUTES = 10;
const CHART_BUCKET = 30;

export interface CardActionResult {
  ok: boolean;
  status: number;
  text: string;
}

/** 카드 집기·숨기기. 실패도 값으로 돌려준다 — 카드가 사유(409 already_claimed 등)를 토스트로 보인다 */
async function cardCall(method: string, path: string): Promise<CardActionResult> {
  try {
    const r = await apiFetch(path, { method });
    return { ok: true, status: r.status, text: '' };
  } catch (e) {
    if (e instanceof ApiError) return { ok: false, status: e.status, text: e.code ?? e.message };
    return { ok: false, status: 0, text: String(e) };
  }
}

const cardPath = (cardId: string) => `/api/clip/jump-cards/${cardId.replace('card-', '')}`;
export function claimCard(cardId: string) {
  return cardCall('POST', `${cardPath(cardId)}/claim`);
}
export function hideCard(cardId: string) {
  return cardCall('POST', `${cardPath(cardId)}/hide`);
}

/** 수집기가 한 번에 받는 가장 긴 창 — chat-collector pokeclip.query.window-max */
export const COLLECTOR_WINDOW_MS = 60 * 60_000;

/** [from, to)를 수집기 한 번에 받을 수 있는 1시간 창들로 자른다 */
export function chartWindows(from: number, to: number): [number, number][] {
  const windows: [number, number][] = [];
  for (let a = from; a < to; a += COLLECTOR_WINDOW_MS)
    windows.push([a, Math.min(to, a + COLLECTOR_WINDOW_MS)]);
  return windows;
}

/**
 * 카드가 가리키는 방송 시점의 절대 시각. 카드를 만든 시각(createdAt)은 감지가 늦거나 다시 처리되면 급증보다
 * 뒤에 찍혀 마커가 엇나간다(PR #200 codex). 방송 시작 시각을 모르면 만든 시각으로 대신한다.
 * 기준점은 방송 시작 편지 시각이라 녹화 첫 조각과 수십 초 어긋날 수 있다(README 「시각 기준점」).
 */
function cardAt(
  c: { streamTimestampMs: number; createdAt: string },
  startedAt: number | null,
): number {
  return startedAt !== null ? startedAt + c.streamTimestampMs : Date.parse(c.createdAt);
}

/** 응답을 JSON으로 받는다. 실패는 null — 폴링은 다음 주기에 다시 한다 */
async function getJsonOrNull<T>(path: string): Promise<T | null> {
  try {
    const r = await apiFetch(path);
    return (await r.json()) as T;
  } catch {
    return null;
  }
}

function msToClock(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  return `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`;
}

function timeAgo(iso: string, now: number): string {
  const diff = Math.max(0, Math.floor((now - Date.parse(iso)) / 1000));
  if (diff < 10) return '방금';
  if (diff < 60) return `${diff}초 전`;
  return `${Math.floor(diff / 60)}분 전`;
}

/** 지금 로그인한 회원 번호 — 토큰의 sub. 카드의 claimedBy와 같은 값이다 */
function currentUserId(): string | null {
  try {
    const token = useAuthStore.getState().accessToken;
    const payload = token?.split('.')[1];
    if (!payload) return null;
    const json = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/'))) as {
      sub?: unknown;
    };
    return typeof json.sub === 'string' ? json.sub : null;
  } catch {
    return null;
  }
}

function toHighlight(c: Snapshot, index: number, now: number): LiveHighlight {
  const ev = c.evidence ?? {};
  const ratio = typeof ev.ratio === 'number' ? ev.ratio : null;
  const count =
    typeof ev.count === 'number'
      ? ev.count
      : typeof ev.messageCount === 'number'
        ? ev.messageCount
        : null;
  const winSec = Math.round((c.window.endMs - c.window.startMs) / 1000);
  const manual = c.source === 'hotkey';
  const reason = manual
    ? '수동 마킹'
    : ratio !== null
      ? `채팅 ×${ratio.toFixed(1)} 급증${count !== null ? ` (${count}건)` : ''}`
      : `채팅 급증${count !== null ? ` (${count}건)` : ''}`;
  let status: HighlightStatus = manual ? 'manual' : 'scored';
  if (c.claimedBy) status = 'editing';
  return {
    id: `card-${c.id}`,
    timestamp: msToClock(c.streamTimestampMs),
    title: manual ? '핫키로 남긴 순간' : `채팅이 튄 순간 #${c.id}`,
    meta: `${reason} · ${winSec}초 · ${timeAgo(c.createdAt, now)}`,
    status,
    source: manual ? 'manual' : 'auto',
    score: c.score ?? undefined,
    emphasized: index === 0,
    claimedByMe: c.claimedBy !== null && c.claimedBy === currentUserId(),
  };
}

function parseSseBlock(block: string): { event: string; data: string } | null {
  let event = 'message';
  const data: string[] = [];
  for (const line of block.split('\n')) {
    if (line.startsWith(':')) continue;
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
  }
  if (data.length === 0) return null;
  return { event, data: data.join('\n') };
}

export function useLiveMockState(): LiveMockState {
  const [cards, setCards] = useState<Record<number, Snapshot>>({});
  const [chart, setChart] = useState<ChartPage | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const [sseOk, setSseOk] = useState(false);
  const [info, setInfo] = useState<BroadcastInfoResponse | null>(null);
  const live = useLiveData();
  // 방송 번호는 화면 맨 위(useLiveStreamSelection)가 정한다. 정해지기 전(빈 문자열)에는 아무것도 안 받는다
  const streamId = live.streamId;
  const rangeRef = useRef<{ status: string; startedAt: number | null; endedAt: number | null }>({
    status: 'unknown',
    startedAt: null,
    endedAt: null,
  });
  rangeRef.current = { status: live.status, startedAt: live.startedAt, endedAt: live.endedAt };

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);
  // 방송 상태(라이브→종료, 시작·종료 시각 도착)가 바뀌면 차트 범위와 시청자 기록의 시작이 바뀐다 — 그 둘만 다시 읽는다.
  // 🔴 아래 큰 효과(카드·통로·방송 정보)를 다시 돌리면 카드가 비고 통로가 끊겨 그 사이 중계 채팅을 잃는다(POK-251 리뷰 2라운드)
  const reloadRangeRef = useRef<(() => void) | null>(null);
  useEffect(() => {
    reloadRangeRef.current?.();
  }, [live.status, live.startedAt, live.endedAt]);

  useEffect(() => {
    if (!streamId) return;
    // 방송이 바뀌면 앞 방송의 것은 전부 비운다
    setCards({});
    setChart(null);
    setInfo(null);
    publishLiveData({ streamId, chart: null, info: null, cardTimes: [] });
    let stopped = false;
    const merge = (list: Snapshot[]) => {
      if (stopped) return;
      setCards((prev) => {
        const next = { ...prev };
        for (const c of list) next[c.id] = c;
        return next;
      });
    };

    const loadList = async () => {
      try {
        // 끝까지 넘기고 숨긴 카드도 받는다 — 숨김이 목록에 반영돼야 한다(통로가 끊겨 폴링이 대신할 때도).
        // 한 쪽만 읽으면 51번째 카드부터 빠진다(PR #200 codex)
        merge(await fetchAllJumpCards<Snapshot>(streamId, { includeHidden: true }));
      } catch {
        /* 다음 주기에 다시 */
      }
    };
    const loadChart = async () => {
      try {
        const rg = rangeRef.current;
        const to = new Date();
        const from = new Date(to.getTime() - CHART_MINUTES * 60_000);
        const bucket = CHART_BUCKET;
        // 지난 방송은 시작~종료 전체를 본다. 수집기는 한 번에 1시간까지만 받으므로(window-max PT1H,
        // 넘으면 400 too_wide) 1시간씩 나눠 읽어 이어 붙인다(PR #200 codex). 버킷은 길이에 맞춰 고른다
        if (rg.status === 'ended' && rg.startedAt !== null && rg.endedAt !== null) {
          const dur = rg.endedAt - rg.startedAt;
          const vodBucket = dur <= 60 * 60_000 ? 10 : dur <= 6 * 60 * 60_000 ? 30 : 60;
          const start = dur > 12 * 60 * 60_000 ? rg.endedAt - 12 * 60 * 60_000 : rg.startedAt;
          const pages = await Promise.all(
            chartWindows(start, rg.endedAt).map(([a, b]) =>
              getJsonOrNull<ChartPage>(
                `/api/clip/broadcasts/${encodeURIComponent(streamId)}/chat-chart?${new URLSearchParams(
                  {
                    from: new Date(a).toISOString(),
                    to: new Date(b).toISOString(),
                    bucket: String(vodBucket),
                  },
                )}`,
              ),
            ),
          );
          const first = pages[0];
          // 한 조각이라도 못 받으면 구멍 난 차트를 그리지 않는다 — 다음 주기에 다시
          if (first && pages.every((pg) => pg !== null)) {
            const joined: ChartPage = {
              ...first,
              buckets: pages.flatMap((pg) => (pg as ChartPage).buckets),
            };
            setChart(joined);
            publishLiveData({ chart: joined });
          }
          return;
        } else if (rg.status === 'offline') {
          setChart(null);
          publishLiveData({ chart: null });
          return;
        }
        const qs = new URLSearchParams({
          from: from.toISOString(),
          to: to.toISOString(),
          bucket: String(bucket),
        });
        const page = await getJsonOrNull<ChartPage>(
          `/api/clip/broadcasts/${encodeURIComponent(streamId)}/chat-chart?${qs}`,
        );
        if (page) {
          setChart(page);
          publishLiveData({ chart: page });
        }
      } catch {
        /* 다음 주기에 다시 */
      }
    };

    const loadInfo = async () => {
      // 지난 방송은 시작부터의 시청자 기록을 달라고 한다 — 안 주면 서버가 최근 1시간만 준다(PR #200 codex)
      const rg = rangeRef.current;
      const since =
        rg.status === 'ended' && rg.startedAt !== null
          ? `?${new URLSearchParams({ since: new Date(rg.startedAt).toISOString() })}`
          : '';
      const page = await getJsonOrNull<BroadcastInfoResponse>(
        `/api/clip/broadcasts/${encodeURIComponent(streamId)}/broadcast-info${since}`,
      );
      if (page) {
        setInfo(page);
        publishLiveData({ info: page });
      }
    };
    const ctrl = new AbortController();
    let sseCooldown = 5_000;
    const listenSse = async () => {
      try {
        let r: Response;
        try {
          r = await apiFetch(`/api/clip/broadcasts/${encodeURIComponent(streamId)}/events`, {
            headers: { Accept: 'text/event-stream' },
            signal: ctrl.signal,
          });
        } catch (e) {
          // 503은 용량 초과라 길게 쉰다 — 폴링이 카드를 메운다. 404는 방송 편지가 아직 안 온 것
          sseCooldown = e instanceof ApiError && e.status === 503 ? 60_000 : 15_000;
          return;
        }
        if (!r.body) {
          sseCooldown = 15_000;
          return;
        }
        sseCooldown = 5_000;
        setSseOk(true);
        const reader = r.body.getReader();
        const dec = new TextDecoder();
        let buf = '';
        while (!stopped) {
          const { value, done } = await reader.read();
          if (done) break;
          buf += dec.decode(value, { stream: true });
          let idx = buf.indexOf('\n\n');
          while (idx >= 0) {
            const parsed = parseSseBlock(buf.slice(0, idx));
            buf = buf.slice(idx + 2);
            if (parsed && parsed.event === 'card') {
              try {
                merge([JSON.parse(parsed.data) as Snapshot]);
              } catch {
                /* 무시 */
              }
            } else if (parsed && (parsed.event === 'chat' || parsed.event === 'donation')) {
              // 중계 채팅·후원(PR-B) — 채팅 패널이 받아 그린다
              try {
                emitRelay(JSON.parse(parsed.data) as RelayEvent);
              } catch {
                /* 무시 */
              }
            } else if (parsed && parsed.event === 'broadcast-info') {
              // 1분마다 오는 제목·태그·카테고리·시청자 수(PR-C) — 화면 정보 바·통계가 바로 반영된다
              try {
                const ev = JSON.parse(parsed.data) as {
                  time: string;
                  title: string | null;
                  tags: string[];
                  category: string | null;
                  viewers: number | null;
                };
                setInfo((prev) => {
                  const next: BroadcastInfoResponse = {
                    latest: {
                      title: ev.title,
                      tags: ev.tags ?? [],
                      category: ev.category,
                      viewers: ev.viewers,
                    },
                    series: [
                      ...(prev?.series ?? []),
                      { observedAt: ev.time, viewers: ev.viewers },
                    ].slice(-720),
                  };
                  publishLiveData({ info: next });
                  return next;
                });
              } catch {
                /* 무시 */
              }
            }
            idx = buf.indexOf('\n\n');
          }
        }
      } catch {
        /* abort 또는 연결 끊김 — 폴링이 메운다 */
      } finally {
        setSseOk(false);
      }
    };
    // 방송 편지가 아직 안 왔으면 404다 — 5초마다 다시 붙는다(폴링은 별도로 계속 돈다)
    const sseLoop = async () => {
      while (!stopped) {
        await listenSse();
        if (stopped) break;
        await new Promise((res) => setTimeout(res, sseCooldown));
      }
    };

    const reloadRange = () => {
      void loadChart();
      void loadInfo();
    };
    reloadRangeRef.current = reloadRange;
    void loadList();
    void loadChart();
    void loadInfo();
    void sseLoop();
    const t1 = setInterval(loadList, 3000);
    const t2 = setInterval(loadChart, 10_000);
    const t3 = setInterval(loadInfo, 10_000);
    return () => {
      stopped = true;
      ctrl.abort();
      clearInterval(t1);
      clearInterval(t2);
      clearInterval(t3);
      if (reloadRangeRef.current === reloadRange) reloadRangeRef.current = null;
    };
  }, [streamId]);

  useEffect(() => {
    publishLiveData({
      cardTimes: Object.values(cards)
        .filter((c) => !c.hidden)
        .map((c) => cardAt(c, live.startedAt)),
    });
  }, [cards, live.startedAt]);

  const highlights = useMemo(() => {
    const list = Object.values(cards)
      .filter((c) => !c.hidden)
      .sort((a, b) => b.eventSeq - a.eventSeq);
    return list.map((c, i) => toHighlight(c, i, now));
  }, [cards, now]);
  const hiddenCount = useMemo(() => Object.values(cards).filter((c) => c.hidden).length, [cards]);

  const chatVolume = useMemo<ChatVolumeSeries>(() => {
    if (!chart || chart.buckets.length === 0)
      return {
        points: [
          [0, 88],
          [800, 88],
        ],
        markers: [],
        timeLabels: [],
      };
    const b = chart.buckets;
    const max = Math.max(1, ...b.map((x) => x.chats));
    const n = b.length;
    const x = (i: number) => (n === 1 ? 0 : (i / (n - 1)) * 800);
    const y = (v: number) => 88 - (v / max) * 80;
    const points = b.map((bk, i) => [Math.round(x(i)), Math.round(y(bk.chats))] as const);
    const firstB = b[0];
    const lastB = b[n - 1];
    if (!firstB || !lastB)
      return {
        points: [
          [0, 88],
          [800, 88],
        ],
        markers: [],
        timeLabels: [],
      };
    const t0 = Date.parse(firstB.start);
    const t1 = Date.parse(lastB.start) + chart.bucketSeconds * 1000;
    const markers = Object.values(cards)
      .filter((c) => !c.hidden)
      .map((c) => {
        const t = cardAt(c, live.startedAt);
        if (t < t0 || t > t1) return null;
        const i = Math.min(n - 1, Math.floor(((t - t0) / (t1 - t0)) * n));
        const bk = b[i];
        if (!bk) return null;
        return [Math.round(x(i)), Math.round(y(bk.chats))] as const;
      })
      .filter((m): m is readonly [number, number] => m !== null);
    const lab = (ms: number) => {
      const d = new Date(ms);
      return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
    };
    return {
      points,
      markers,
      timeLabels: [lab(t0), lab(t0 + (t1 - t0) / 3), lab(t0 + ((t1 - t0) * 2) / 3), '지금'],
    };
  }, [chart, cards, live.startedAt]);

  const { data: chzzk } = useQuery(chzzkLinkQueryOptions);
  const { data: me } = useMe();
  // 채널 이름은 내 방송일 때만 내 치지직 연동에서 읽는다. 위임받은 방송(EDITOR)은 방송 줄에 스트리머 채널이
  // 실려 오지 않아 비운다 — 내 채널 이름을 남의 방송에 붙이면 거짓이다(POK-251 리뷰)
  const channelName = live.relation === 'OWNER' ? (chzzk?.channelName ?? '') : '';
  const myName = me?.name ?? '';
  const stream = useMemo<LiveStream>(
    () => ({
      title: info?.latest?.title ?? (streamId ? streamId : '방송 중인 채널이 없어요'),
      platform: '치지직',
      channelName,
      startedNote: !streamId
        ? '방송을 시작하면 자동으로 나타나요'
        : live.status === 'ended'
          ? '지난 방송'
          : sseOk
            ? '실시간 연결됨'
            : '실시간 연결 중',
      uptimeSeconds: 0,
      uptimeLabel: '0:00:00',
      viewers: (() => {
        const lastPt = info?.series?.length ? info.series[info.series.length - 1] : undefined;
        const v = lastPt ? lastPt.viewers : null;
        return v === null || v === undefined ? '-' : String(v);
      })(),
      editorName: myName,
    }),
    [streamId, sseOk, info, live.status, channelName, myName],
  );

  return { stream, highlights, hiddenCount, chatVolume, chatWarning: false };
}
