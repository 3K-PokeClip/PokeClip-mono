'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { apiFetch } from '@/api/client';
import { subscribeRelay, useLiveData, type RelayEvent } from './liveDataStore';

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
  /** 하단 상태줄의 「분당 N」 — 최근 1분 채팅 수(지난 방송은 전체 평균) */
  ratePerMinute: number;
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

const POLL_MS = 5000; // SSE 중계가 주 경로, 폴링은 메우기·폴백

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

export function useChatPanelMockState(enabled: boolean): ChatPanelMockState {
  const [raw, setRaw] = useState<Record<string, WireItem>>({});
  const lastTime = useRef<number>(0);
  const nextId = useRef<number>(1);
  const lastSeq = useRef<{ seq: number; epoch: number } | null>(null);
  const refillTimer = useRef<number | null>(null);
  const pollRef = useRef<(() => Promise<void>) | null>(null);
  const live = useLiveData();
  const streamId = live.streamId;
  const isVod = live.status === 'ended' && live.startedAt !== null && live.endedAt !== null;

  // 지난 방송: 시작~종료 전체를 커서로 한 번에 받는다(최대 10장 = 2,000건). 폴링 없음
  useEffect(() => {
    if (!enabled || !streamId || !isVod || live.startedAt === null || live.endedAt === null) return;
    setRaw({});
    let stopped = false;
    (async () => {
      const acc: Record<string, WireItem> = {};
      let cursor: string | null = null;
      for (let page = 0; page < 10; page += 1) {
        const qs = new URLSearchParams({
          from: new Date(live.startedAt as number).toISOString(),
          to: new Date(live.endedAt as number).toISOString(),
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
          if (!cursor) break;
        } catch {
          break;
        }
      }
      if (!stopped) setRaw(acc);
    })();
    return () => {
      stopped = true;
    };
  }, [enabled, streamId, isVod, live.startedAt, live.endedAt]);

  // 실시간 중계 수신(카드 통로의 chat·donation). 구멍·세대 변화면 2초 뒤 범위 창구로 메운다
  useEffect(() => {
    if (!enabled || !streamId || isVod || live.status === 'offline') return;
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
  }, [enabled, streamId, isVod, live.status]);

  useEffect(() => {
    if (!enabled || !streamId || isVod || live.status === 'offline') return;
    setRaw({});
    lastTime.current = 0;
    let stopped = false;
    const poll = async () => {
      try {
        const toMs = Date.now();
        // 화면 축으로 묻는다(서버가 +보정). 처음엔 10분 전부터, 그 뒤엔 마지막으로 본 시각 −10초부터.
        const fromMs = lastTime.current > 0 ? lastTime.current - 10_000 : toMs - 10 * 60_000;
        const qs = new URLSearchParams({
          from: new Date(fromMs).toISOString(),
          to: new Date(toMs).toISOString(),
          limit: '200',
        });
        const r = await apiFetch(
          `/api/clip/broadcasts/${encodeURIComponent(streamId)}/chat-messages?${qs}`,
        );
        if (stopped) return;
        const page = (await r.json()) as WirePage;
        if (page.items.length === 0) return;
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
  }, [enabled, streamId, isVod, live.status]);

  const items = useMemo(
    () => Object.values(raw).sort((a, b) => Date.parse(a.time) - Date.parse(b.time) || a.id - b.id),
    [raw],
  );
  // 지난 방송에서 카드를 눌렀으면 그 시점 앞뒤 60초만 보여준다(영상 대신 채팅이 그 시점을 말한다)
  const messages = useMemo(() => {
    if (isVod && live.playheadMs !== null && live.startedAt !== null) {
      const center = live.startedAt + live.playheadMs;
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
  }, [items, isVod, live.playheadMs, live.startedAt]);

  const ratePerMinute = useMemo(() => {
    if (isVod && live.startedAt !== null && live.endedAt !== null) {
      const minutes = Math.max(1, (live.endedAt - live.startedAt) / 60_000);
      return Math.round(items.length / minutes);
    }
    const cut = Date.now() - 60_000 + CHAT_LEAD_MS;
    return items.filter((it) => Date.parse(it.time) >= cut).length;
  }, [items, isVod, live.startedAt, live.endedAt]);

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

  return { surges, messages, ratePerMinute };
}
