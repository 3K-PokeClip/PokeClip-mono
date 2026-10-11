'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { apiFetch } from '@/api/client';
import {
  chartSpan,
  subscribeRelay,
  timeBaseOf,
  useLiveData,
  type RelayEvent,
} from './liveDataStore';
import { chartWindows, endedWindowStart } from './useLiveMockState';

// 실시간 채팅 패널(시안 1b). clip 창구에 붙어 있다(POK-251).
// 라이브: 카드 통로(SSE)의 chat·donation 중계가 주 경로이고 chat-messages 폴링이 메우기·폴백이다.
// 지난 방송: 시작~종료 전체를 chat-messages로 한 번에 받는다.
// 방송 번호는 라이브 훅이 정한 것(liveDataStore)을 같이 쓴다 — 방송이 바뀌면 함께 바뀐다.

export const CHAT_PANEL_INTERVAL_MS = 3500;
// 초기 픽스처(INITIAL)보다 넉넉해야 한다 — 작으면 첫 새 메시지가 오는 순간 옛 줄이 잘려
// 2단 레이아웃에서 목록이 다시 안 넘치고, 스크롤·「지난 메시지 보는 중」을 눈으로 볼 수 없게 된다.

export interface ChatSurge {
  keyword: string;
  count: number;
}

export type ChatPanelMessage =
  | {
      id: number;
      kind: 'chat';
      name: string;
      text: string;
      /** 닉네임 색 로테이션 인덱스 (LiveScreen.module.css .chatName*) */
      colorIndex: number;
    }
  | { id: number; kind: 'donation'; name: string; amountLabel: string; text: string }
  /** 하이라이트 감지처럼 서비스가 끼워 넣는 줄 */
  | { id: number; kind: 'system'; text: string };

export interface ChatPanelMockState {
  surges: ChatSurge[];
  messages: ChatPanelMessage[];
  /**
   * 하단 상태줄의 「분당 N」: 최근 1분 채팅 수. 끝난 화면은 차트가 읽은 방송 창의 평균이고(방송 전체, 12시간 넘으면 끝 12시간)
   * 통계의 「분당 평균 채팅」과 같은 수다. 그 차트가 아직 없으면 모른다(null)
   */
  ratePerMinute: number | null;
  /** 고른 시점 앞뒤 채팅을 보여 주나(지난 방송). 아니면 시점을 골라도 최신 채팅 그대로다 */
  followsPlayhead: boolean;
}

// ── clip 창구 배선: chat-messages(메우기·지난 방송) + 카드 통로 중계(라이브) ──
interface WireItem {
  kind: 'chat' | 'donation';
  id: number;
  time: string;
  nickname: string | null;
  senderChannelId: string;
  text: string | null;
  amount: number | null;
}
interface WirePage {
  items: WireItem[];
  nextCursor: string | null;
  appliedOffsetMs: number;
}

const POLL_MS = 5000;

/** 라이브 폴링 한 번에 따라가는 쪽 수 상한(쪽당 200건) — 화면은 최근 300건만 남긴다 */
const LIVE_POLL_PAGE_BUDGET = 10;

/**
 * 지난 방송 채팅을 처음 읽을 때 1시간 창마다의 쪽 수 상한(쪽당 200건). 창마다 따로 둔다 — 방송 전체에 하나를 두면
 * 서버가 오래된 것부터 주므로 앞부분에서 다 써 버려 후반이 통째로 빈다(PR #200 codex P1). 모자란 시점은
 * 카드를 누를 때 그 앞뒤를 따로 끝까지 읽는다(VOD_FOCUS_PAGE_BUDGET)
 */
const VOD_CHAT_PAGES_PER_WINDOW = 5;
/** 카드를 누른 시점 앞뒤 60초를 읽을 때 쪽 수 상한 */
const VOD_FOCUS_PAGE_BUDGET = 10; // SSE 중계가 주 경로, 폴링은 메우기·폴백

/**
 * 채팅 시각(표 축)이 화면(영상) 시각보다 앞서는 폭 — 시차 보정 실측 중앙값 3,884ms(POK-92)를 올려 잡았다.
 * 「최근 1분」「시점 앞뒤 60초」 창을 채팅 축으로 옮길 때 더한다.
 */
const CHAT_LEAD_MS = 3900;

const KEEP_REAL = 300;
function trim(map: Record<string, WireItem>): Record<string, WireItem> {
  const keys = Object.keys(map);
  if (keys.length <= KEEP_REAL) return map;
  const sorted = keys.sort(
    (a, b) => Date.parse((map[a] as WireItem).time) - Date.parse((map[b] as WireItem).time),
  );
  const next: Record<string, WireItem> = {};
  for (const k of sorted.slice(-KEEP_REAL)) next[k] = map[k] as WireItem;
  return next;
}

/** 겹친 구간 중복 제거 열쇠 — 채팅 표 지문 UNIQUE와 같다(README 「웹이 지켜야 하는 것 넷」 3) */
function fingerprint(it: {
  kind: string;
  time: string;
  senderChannelId: string;
  text: string | null;
}): string {
  return `${it.kind}|${it.time}|${it.senderChannelId}|${it.text ?? ''}`;
}

function colorOf(senderChannelId: string): number {
  let h = 0;
  for (let i = 0; i < senderChannelId.length; i += 1)
    h = (h * 31 + senderChannelId.charCodeAt(i)) | 0;
  return Math.abs(h) % 6;
}

function toPanel(it: WireItem): ChatPanelMessage {
  const name = it.nickname ?? it.senderChannelId.slice(0, 8);
  if (it.kind === 'donation') {
    return {
      id: it.id,
      kind: 'donation',
      name,
      amountLabel: `치즈 ${(it.amount ?? 0).toLocaleString()}`,
      text: it.text ?? '',
    };
  }
  return {
    id: it.id,
    kind: 'chat',
    name,
    text: it.text ?? '',
    colorIndex: colorOf(it.senderChannelId),
  };
}

/**
 * @param ended 화면이 끝난 방송으로 보이나(LiveDashboard의 viewStatus). 지난 방송 주소는 명부 답 전에도 그렇다. 그동안은 실시간
 *   채팅을 읽지 않는다: 최근 창을 읽으면 끝난 방송의 「채팅 기록」 자리에 엉뚱한 창의 채팅을 채운다(리뷰 3라운드). 통계와 같은
 *   값을 받는다: 채팅만 따로 계산하면 화면 상태 규칙이 바뀔 때 둘이 갈린다(리뷰 5라운드)
 */
export function useChatPanelMockState(enabled: boolean, ended: boolean): ChatPanelMockState {
  const [raw, setRaw] = useState<Record<string, WireItem>>({});
  const lastTime = useRef<number>(0);
  const nextId = useRef<number>(1);
  const lastSeq = useRef<{ seq: number; epoch: number } | null>(null);
  const refillTimer = useRef<number | null>(null);
  const pollRef = useRef<(() => Promise<void>) | null>(null);
  const live = useLiveData();
  const streamId = live.streamId;
  // 지난 방송 채팅을 읽기 시작할 시각. 그래프·시청자 기록과 같은 창이다(endedWindowStart): 시작 시각을 모르는 방송은 아무 창도
  // 안 읽어 채팅만 비었고(리뷰 4라운드), 12시간 넘는 방송은 채팅만 처음부터 읽어 통계와 다른 창을 보였다(리뷰 5라운드)
  const vodFrom =
    live.status === 'ended' && live.endedAt !== null
      ? endedWindowStart(live.startedAt, live.endedAt)
      : null;
  const isVod = vodFrom !== null;

  // 지난 방송: 그 창(방송 전체, 12시간 넘으면 끝 12시간)을 받는다. 앞쪽 카드는 그 시점 앞뒤 60초를 따로 읽는다(아래).
  // 수집기는 한 번에 1시간까지만 받으므로(넘으면 400 too_wide)
  // 1시간 창마다 커서로 넘긴다(PR #200 codex). 창마다 최대 5장(1,000건). 폴링 없음
  useEffect(() => {
    if (!enabled || !streamId || vodFrom === null || live.endedAt === null) return;
    setRaw({});
    let stopped = false;
    const windows = chartWindows(vodFrom, live.endedAt);
    (async () => {
      const acc: Record<string, WireItem> = {};
      for (const [from, to] of windows) {
        let cursor: string | null = null;
        let budget = VOD_CHAT_PAGES_PER_WINDOW;
        do {
          if (budget-- <= 0) break;
          const qs = new URLSearchParams({
            from: new Date(from).toISOString(),
            to: new Date(to).toISOString(),
            limit: '200',
          });
          if (cursor) qs.set('cursor', cursor);
          try {
            const r = await apiFetch(
              `/api/clip/broadcasts/${encodeURIComponent(streamId)}/chat-messages?${qs}`,
            );
            if (stopped) return;
            const pg = (await r.json()) as WirePage;
            for (const it of pg.items) acc[fingerprint(it)] = it;
            cursor = pg.nextCursor;
          } catch {
            // 이 창만 건너뛴다 — 한 창의 실패로 방송 전체 채팅을 비우지 않는다
            cursor = null;
          }
        } while (cursor !== null);
      }
      // 덮지 않고 합친다: 그 사이 카드 시점 앞뒤 60초를 따로 읽어 넣었을 수 있다. 1시간 창은 창마다 상한(1,000건)이라 채팅이
      // 몰린 카드 시점이 빠지는데, 덮으면 그것까지 지워 「채팅 0건」이 됐다(리뷰 5라운드). 이 효과가 처음에 비웠으므로 앞 방송 것은 없다
      if (!stopped) setRaw((prev) => ({ ...acc, ...prev }));
    })();
    return () => {
      stopped = true;
    };
  }, [enabled, streamId, vodFrom, live.endedAt]);

  // 실시간 중계 수신(카드 통로의 chat·donation). 구멍·세대 변화면 2초 뒤 범위 창구로 메운다
  useEffect(() => {
    if (!enabled || !streamId || ended || live.status === 'offline') return;
    lastSeq.current = null;
    return subscribeRelay((ev: RelayEvent) => {
      const item: WireItem = {
        kind: ev.kind,
        id: 0,
        time: ev.time,
        nickname: ev.nickname,
        senderChannelId: ev.senderChannelId,
        text: ev.text,
        amount: ev.amount,
      };
      setRaw((prev) => {
        const k = fingerprint(item);
        if (prev[k]) return prev;
        const withId = { ...item, id: nextId.current++ };
        return trim({ ...prev, [k]: withId });
      });
      const prevSeq = lastSeq.current;
      const gap = prevSeq !== null && (ev.seqEpoch !== prevSeq.epoch || ev.seq > prevSeq.seq + 1);
      if (!prevSeq || ev.seqEpoch !== prevSeq.epoch || ev.seq > prevSeq.seq)
        lastSeq.current = { seq: ev.seq, epoch: ev.seqEpoch };
      if (gap && refillTimer.current === null) {
        // 놓친 채팅은 표에 최대 1초 뒤 들어간다 — 바로 부르면 빈손이다(README 규칙 2)
        refillTimer.current = window.setTimeout(() => {
          refillTimer.current = null;
          pollRef.current?.();
        }, 2000);
      }
    });
  }, [enabled, streamId, ended, live.status]);

  useEffect(() => {
    if (!enabled || !streamId || ended || live.status === 'offline') return;
    setRaw({});
    lastTime.current = 0;
    let stopped = false;
    const poll = async () => {
      try {
        const toMs = Date.now();
        // 화면 축으로 묻는다(서버가 +보정). 처음엔 10분 전부터, 그 뒤엔 마지막으로 본 시각 −10초부터.
        const fromMs = lastTime.current > 0 ? lastTime.current - 10_000 : toMs - 10 * 60_000;
        // 한 번에 200건을 넘으면 서버는 오래된 쪽부터 주고 다음 커서를 단다 — 커서를 끝까지 따라가야
        // 최신 채팅까지 닿는다. 첫 장만 읽으면 창이 실시간을 영영 못 따라잡는다(PR #200 codex P1)
        const items: WireItem[] = [];
        let appliedOffsetMs = 0;
        let cursor: string | null = null;
        let pages = 0;
        do {
          const qs = new URLSearchParams({
            from: new Date(fromMs).toISOString(),
            to: new Date(toMs).toISOString(),
            limit: '200',
          });
          if (cursor) qs.set('cursor', cursor);
          const r = await apiFetch(
            `/api/clip/broadcasts/${encodeURIComponent(streamId)}/chat-messages?${qs}`,
          );
          if (stopped) return;
          const pg = (await r.json()) as WirePage;
          items.push(...pg.items);
          appliedOffsetMs = pg.appliedOffsetMs;
          cursor = pg.nextCursor;
          pages += 1;
        } while (cursor !== null && pages < LIVE_POLL_PAGE_BUDGET);
        if (items.length === 0) return;
        const page = { items, appliedOffsetMs };
        setRaw((prev) => {
          const next = { ...prev };
          for (const it of page.items) {
            const k = fingerprint(it);
            if (!next[k]) next[k] = it;
          }
          return trim(next);
        });
        // 응답 시각은 표 축이라 화면 축으로 되돌려 둔다(다음 from에 쓴다)
        const newest = Math.max(...page.items.map((it) => Date.parse(it.time)));
        lastTime.current = Math.max(lastTime.current, newest - page.appliedOffsetMs);
      } catch {
        /* 다음 주기에 다시 */
      }
    };
    pollRef.current = poll;
    void poll();
    const t = window.setInterval(poll, POLL_MS);
    return () => {
      stopped = true;
      window.clearInterval(t);
    };
  }, [enabled, streamId, ended, live.status]);

  // 지난 방송에서 카드를 누르면 그 시점 앞뒤 60초를 끝까지 따로 읽는다 — 처음 읽기는 창마다 상한이 있어
  // 채팅이 많은 시간대는 비어 있을 수 있다(PR #200 codex P1)
  const vodBase = timeBaseOf(live);
  useEffect(() => {
    if (!enabled || !streamId || !isVod || live.playheadMs === null || vodBase === null) return;
    const center = vodBase + live.playheadMs;
    const from = center - 60_000 + CHAT_LEAD_MS;
    const to = center + 60_000 + CHAT_LEAD_MS;
    let stopped = false;
    (async () => {
      const got: WireItem[] = [];
      let cursor: string | null = null;
      let budget = VOD_FOCUS_PAGE_BUDGET;
      do {
        if (budget-- <= 0) break;
        const qs = new URLSearchParams({
          from: new Date(from).toISOString(),
          to: new Date(to).toISOString(),
          limit: '200',
        });
        if (cursor) qs.set('cursor', cursor);
        try {
          const r = await apiFetch(
            `/api/clip/broadcasts/${encodeURIComponent(streamId)}/chat-messages?${qs}`,
          );
          if (stopped) return;
          const pg = (await r.json()) as WirePage;
          got.push(...pg.items);
          cursor = pg.nextCursor;
        } catch {
          cursor = null;
        }
      } while (cursor !== null);
      if (stopped || got.length === 0) return;
      setRaw((prev) => {
        const next = { ...prev };
        for (const it of got) {
          const k = fingerprint(it);
          if (!next[k]) next[k] = it;
        }
        return next;
      });
    })();
    return () => {
      stopped = true;
    };
  }, [enabled, streamId, isVod, live.playheadMs, vodBase]);

  const items = useMemo(
    () => Object.values(raw).sort((a, b) => Date.parse(a.time) - Date.parse(b.time) || a.id - b.id),
    [raw],
  );
  // 지난 방송에서 카드를 눌렀으면 그 시점 앞뒤 60초만 보여준다(영상 대신 채팅이 그 시점을 말한다)
  const messages = useMemo(() => {
    // 시점의 0초는 영상과 같은 기준점이다(녹화 첫 조각) — 방송 시작 시각에 더하면 그 차이만큼 영상과 어긋난다(PR #200 codex)
    if (isVod && live.playheadMs !== null && vodBase !== null) {
      const center = vodBase + live.playheadMs;
      const lo = center - 60_000 + CHAT_LEAD_MS;
      const hi = center + 60_000 + CHAT_LEAD_MS;
      const inWin = items.filter((it) => {
        const t = Date.parse(it.time);
        return t >= lo && t <= hi;
      });
      const sec = Math.floor(live.playheadMs / 1000);
      const h = Math.floor(sec / 3600),
        m = Math.floor((sec % 3600) / 60),
        sc = sec % 60;
      const label = `${h}:${String(m).padStart(2, '0')}:${String(sc).padStart(2, '0')}`;
      return [
        { id: -1, kind: 'system' as const, text: `${label} 시점 앞뒤 60초 채팅 ${inWin.length}건` },
        ...inWin.map(toPanel),
      ];
    }
    return items.map(toPanel);
  }, [items, isVod, live.playheadMs, vodBase]);

  const ratePerMinute = useMemo<number | null>(() => {
    // 끝난 화면(명부 답을 기다리는 지난 방송 주소 포함)은 차트가 센 수를 차트가 읽은 길이로 나눈다(12시간 넘는 방송은 끝 12시간).
    // 통계와 같은 수라 반올림하지 않는다: 자릿수는 패널이 통계처럼 맞춘다(리뷰 5라운드). 그 차트가 없으면 모른다: 받아 둔 채팅은
    // 창마다 상한이 있어 방송 길이로 나누면 늘 적게 나온다. 통계도 그때 「확인 중」이다(리뷰 4라운드)
    if (ended) {
      const chart = live.chart;
      if (chart === null || chart.scope === 'recent') return null;
      const { minutes, chats } = chartSpan(chart);
      return chats / minutes;
    }
    const cut = Date.now() - 60_000 + CHAT_LEAD_MS;
    return items.filter((it) => Date.parse(it.time) >= cut).length;
  }, [items, ended, live.chart]);

  const surges = useMemo<ChatSurge[]>(() => {
    const cut = Date.now() - 2 * 60_000 + CHAT_LEAD_MS;
    const freq = new Map<string, number>();
    for (const it of items) {
      if (it.kind !== 'chat' || Date.parse(it.time) < cut) continue;
      for (const w of (it.text ?? '').split(/\s+/)) {
        const k = w.trim();
        if (k.length < 2) continue;
        freq.set(k, (freq.get(k) ?? 0) + 1);
      }
    }
    return [...freq.entries()]
      .filter(([, c]) => c >= 2)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 3)
      .map(([keyword, count]) => ({ keyword, count }));
  }, [items]);

  // 채팅이 고른 시점을 따라가려면 그 시점을 절대 시각으로 바꿀 기준점이 있어야 한다(시작 시각을 모르는 방송은 없을 수 있다)
  return { surges, messages, ratePerMinute, followsPlayhead: isVod && vodBase !== null };
}
