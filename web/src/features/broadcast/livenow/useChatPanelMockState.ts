'use client';

import { useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { apiFetch } from '@/api/client';
import { recordingSearchFrom } from '@/features/broadcast/endedWindow';
import { CHAT_PAGE_MAX, type WireItem, type WirePage } from './chatCollector';
import {
  CHAT_KEEP,
  ChatReplayLoader,
  EMPTY_REPLAY,
  replayReadStart,
  replayStartIsGuess,
  replayView,
  type ReplaySnapshot,
  type ReplayStatus,
} from './chatReplay';
import {
  chartSpan,
  subscribeRelay,
  timeBaseOf,
  useLiveData,
  type RelayEvent,
} from './liveDataStore';
import { msToClock } from './useLiveMockState';

// 실시간 채팅 패널(시안 1b). clip 창구에 붙어 있다(POK-251).
// 라이브: 카드 통로(SSE)의 chat·donation 중계가 주 경로이고 chat-messages 폴링이 메우기·폴백이다.
// 지난 방송: 영상 시각까지 나온 줄만 보이고 chat-messages에서 그 둘레만 읽는다(chatReplay.ts, POK-324).
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
   * 하단 상태줄의 「분당 N」: 최근 1분 채팅 수. 끝난 화면은 「분당 평균 N」으로, 차트가 읽은 방송 창의 평균이고(방송 전체, 12시간
   * 넘으면 끝 12시간) 통계의 「분당 평균 채팅」과 같은 수다. 그 차트가 아직 없으면 모른다(null)
   */
  ratePerMinute: number | null;
  /** 고른 시점까지의 채팅을 보여 주나(지난 방송). 아니면 시점을 골라도 최신 채팅 그대로다 */
  followsPlayhead: boolean;
  /**
   * 지난 방송 채팅이 보이는 영상 시점(h:mm:ss). 재생 위치가 없으면 null이다: 방송 끝까지 보이거나(replayWaiting 거짓), 아직 어느
   * 시점을 보일지 모른다(replayWaiting 참)
   */
  replayClock: string | null;
  /** 끝난 방송인데 채팅을 보일 영상 시점을 아직 모른다(명부 답 전, 녹화가 자리를 알리기 전) */
  replayWaiting: boolean;
  /**
   * 지난 방송 채팅을 읽는 중이거나 읽지 못했을 때의 말(목록의 상태 줄과 같다). 채팅 창이 화면 낭독기에 알린다: 목록 안의 줄은
   * 듣는 사람이 목록을 훑기 전에는 모른다(리뷰 1라운드). 다 읽었으면 null
   */
  statusNote: string | null;
}

// ── clip 창구 배선: chat-messages(메우기·지난 방송) + 카드 통로 중계(라이브) ──
const POLL_MS = 5000;

/** 라이브 폴링 한 번에 따라가는 쪽 수 상한(쪽당 200건) — 화면은 최근 300건만 남긴다 */
const LIVE_POLL_PAGE_BUDGET = 10;

/**
 * 지난 방송 채팅을 읽는 하한을 방송 시작보다 이만큼 앞에 둔다. 채팅 시각(표 축)은 영상 축보다 보정(실측 3.9초)만큼 늦어서, 방송
 * 시작 직후의 채팅은 영상 축으로 시작보다 앞이다: 하한을 시작에 두면 그 채팅을 영영 읽지 않았다(리뷰 1라운드). 앞을 넓혀도 같은
 * 채널의 앞 방송 채팅이 섞이지 않는 것은 수집기가 방송 번호로 거르기 때문이다(chat-collector ChatWindowQuery의 `WHERE stream_id`).
 * 그 거르기는 수집기 조회 시험 ChatWindowQueryTest#같은_채널의_앞_방송은_창이_겹쳐도_섞이지_않는다가 고정한다(차트는
 * ChatChartQueryTest#같은_채널의_앞_방송은_창이_겹쳐도_세지_않는다)
 */
const REPLAY_LOWER_SLACK_MS = 60_000;

/** 지난 방송 채팅 창 상태 줄의 말. 영상 시각 쪽을 아직 다 못 읽었을 때는 맨 아래, 그 앞만 모자랄 때는 맨 위에 선다 */
const REPLAY_LOADING = '채팅을 불러오고 있어요';
const REPLAY_NOTE: Record<ReplayStatus, string | null> = {
  ready: null,
  loading: REPLAY_LOADING,
  failed: '채팅을 불러오지 못했어요. 잠시 뒤 다시 불러와요',
  earlierLoading: '이전 채팅을 불러오고 있어요',
  earlierFailed: '이전 채팅을 불러오지 못했어요. 잠시 뒤 다시 불러와요',
};

/**
 * 채팅 시각(표 축)이 화면(영상) 시각보다 앞서는 폭 — 시차 보정 실측 중앙값 3,884ms(POK-92)를 올려 잡았다.
 * 라이브의 「최근 1분」「최근 2분」 창을 채팅 축으로 옮길 때 더한다. 지난 방송은 답의 appliedOffsetMs로 되돌린다(chatReplay.ts)
 */
const CHAT_LEAD_MS = 3900;

function trim(map: Record<string, WireItem>): Record<string, WireItem> {
  const keys = Object.keys(map);
  if (keys.length <= CHAT_KEEP) return map;
  const sorted = keys.sort(
    (a, b) => Date.parse((map[a] as WireItem).time) - Date.parse((map[b] as WireItem).time),
  );
  const next: Record<string, WireItem> = {};
  for (const k of sorted.slice(-CHAT_KEEP)) next[k] = map[k] as WireItem;
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

/**
 * 영상 축 [fromMs, toMs)의 채팅 한 쪽(CHAT_PAGE_MAX줄까지). 수집기가 보정을 더해 표를 찾고 답의 시각은 표 축이다(services
 * README 「계약 셋」 1번). cursor는 앞 쪽의 nextCursor다. signal을 끝까지 넘긴다: 로더가 처음부터 다시 읽을 때 앞서 보낸 요청을
 * 끊는다(리뷰 1라운드)
 */
async function fetchChatPage(
  streamId: string,
  fromMs: number,
  toMs: number,
  cursor: string | null,
  signal: AbortSignal,
): Promise<WirePage> {
  const qs = new URLSearchParams({
    from: new Date(fromMs).toISOString(),
    to: new Date(toMs).toISOString(),
    limit: String(CHAT_PAGE_MAX),
  });
  if (cursor) qs.set('cursor', cursor);
  const r = await apiFetch(
    `/api/clip/broadcasts/${encodeURIComponent(streamId)}/chat-messages?${qs}`,
    { signal },
  );
  return (await r.json()) as WirePage;
}

/** 로더가 없을 때(라이브·방송 정하기 전·접힘) 다시 보기 저장소의 자리 */
const noReplaySubscribe = (): (() => void) => () => undefined;
const emptyReplay = (): ReplaySnapshot => EMPTY_REPLAY;

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
 * @param awaitPlayhead 녹화가 곧 재생 위치를 알려 온다(찾는 중이거나 찾았다. 찾으면 재생기가 서는 그리기에서 바로 알린다). 위치를
 *   모르는 동안은 지난 방송 채팅을 정하지 않고 「불러오고 있어요」다: 방송 끝을 먼저 읽어 끝 채팅이 잠깐 보였다가 0초 빈 창으로
 *   바뀌었다(리뷰 1라운드)
 */
export function useChatPanelMockState(
  enabled: boolean,
  ended: boolean,
  awaitPlayhead = false,
): ChatPanelMockState {
  const [raw, setRaw] = useState<Record<string, WireItem>>({});
  const lastTime = useRef<number>(0);
  const nextId = useRef<number>(1);
  const lastSeq = useRef<{ seq: number; epoch: number } | null>(null);
  const refillTimer = useRef<number | null>(null);
  const pollRef = useRef<(() => Promise<void>) | null>(null);
  const live = useLiveData();
  const streamId = live.streamId;

  // 지난 방송 채팅은 영상 시각까지의 줄만 보이고 그 둘레만 읽는다(POK-324). 읽을 하한은 녹화와 같은 자리(시작 시각, 모르면 끝
  // 12시간, recordingSearchFrom)에서 보정 여유만큼 앞이다. 그래프의 창(끝 12시간)으로 자르지 않는다: 긴 방송의 앞쪽 카드 시점
  // 채팅도 그 방송의 것이다
  const replayUpper = live.status === 'ended' ? live.endedAt : null;
  const searchFrom = replayUpper === null ? null : recordingSearchFrom(live.startedAt, replayUpper);
  const replayLower = searchFrom === null ? null : searchFrom - REPLAY_LOWER_SLACK_MS;
  const vodBase = timeBaseOf(live);
  // 채팅이 보이는 마지막 영상 시각. 재생 위치(녹화가 흐르는 자리, 영상 없이 누른 카드)를 알면 그 시각이고, 녹화가 곧 알려 올
  // 것이면 아직 모르고(null), 둘 다 아니면 방송 끝이다. 시점의 0초는 영상과 같은 기준점이다(녹화 첫 조각): 방송 시작 시각에
  // 더하면 그 차이만큼 영상과 어긋난다(PR #200 codex)
  const replayCursor =
    replayUpper === null || replayLower === null
      ? null
      : live.playheadMs !== null && vodBase !== null
        ? Math.min(replayUpper, Math.max(replayLower, vodBase + live.playheadMs))
        : awaitPlayhead
          ? null
          : replayUpper;
  const readFrom = useMemo(
    () =>
      replayCursor === null || replayLower === null
        ? null
        : replayReadStart(live.chart, replayCursor, replayLower),
    [live.chart, replayCursor, replayLower],
  );
  // 모자라도 그보다 이른 쪽은 더 읽지 않는 바닥. 시작점이 짐작이면(끝 12시간 차트가 영상 시각 앞 300건을 못 셈) 하한까지 앞을 더
  // 읽는다: 차트 첫 칸에서 멈추면 그 앞 채팅이 있어도 끝 12시간의 몇 줄만 보였다(PR #230 codex)
  const readFloor = useMemo(
    () =>
      replayCursor === null || replayLower === null || readFrom === null
        ? null
        : replayStartIsGuess(live.chart, replayCursor)
          ? replayLower
          : readFrom,
    [live.chart, replayCursor, replayLower, readFrom],
  );

  // 한 방송(하한·상한)에 로더 하나다. 열쇠가 바뀌면 새로 세우고, 세우기 전 한 번은 옛 로더가 아니라 빈 것으로 그린다. 치우면
  // 들고 있지도 않는다: 채팅 창을 접었다 펴면 열쇠가 같은 글자라 치운 로더의 옛 줄을 한 번 그렸다(리뷰 1라운드)
  const replayKey =
    enabled && streamId && replayLower !== null && replayUpper !== null
      ? `${streamId}|${replayLower}|${replayUpper}`
      : null;
  const [replayState, setReplayState] = useState<{
    key: string;
    loader: ChatReplayLoader;
  } | null>(null);
  useEffect(() => {
    if (replayKey === null || replayLower === null || replayUpper === null) return;
    const next = new ChatReplayLoader(
      (from, to, cursor, signal) => fetchChatPage(streamId, from, to, cursor, signal),
      replayLower,
      replayUpper,
    );
    setReplayState({ key: replayKey, loader: next });
    return () => {
      next.dispose();
      setReplayState((current) => (current?.loader === next ? null : current));
    };
  }, [replayKey, streamId, replayLower, replayUpper]);
  const loader = replayState !== null && replayState.key === replayKey ? replayState.loader : null;
  const snapshot = useSyncExternalStore(
    loader?.subscribe ?? noReplaySubscribe,
    loader?.getSnapshot ?? emptyReplay,
    emptyReplay,
  );
  // 그리기 전에 알린다: 처음부터 다시 읽어야 하면 바로 비워 그려, 옮긴 시점에 옛 구간의 줄을 한 번 칠하지 않는다
  // 차트도 넘긴다: 사람이 옮겼을 때 다 읽은 구간과 새 자리 사이 줄을 세어, 채팅이 몰린 구간이면 사이를 다 읽지 않고 다시 읽는다
  const chart = live.chart;
  useLayoutEffect(() => {
    if (loader === null || replayCursor === null || readFrom === null || readFloor === null) return;
    loader.want(replayCursor, readFrom, { floorMs: readFloor, chart });
  }, [loader, replayCursor, readFrom, readFloor, chart]);

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
        // 본문을 받는 사이 효과가 멈췄으면(방송이 끝나 지난 방송 읽기로 바뀜) 싣지 않는다: 덮지 않고 합치게 된 뒤로는 끝난 뒤의
        // 줄이 「채팅 기록」에 남았다(리뷰 6라운드)
        if (stopped || items.length === 0) return;
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

  const items = useMemo(
    () => Object.values(raw).sort((a, b) => Date.parse(a.time) - Date.parse(b.time) || a.id - b.id),
    [raw],
  );
  const replay = useMemo(
    () =>
      replayCursor === null || replayUpper === null || readFloor === null
        ? null
        : replayView(snapshot, replayCursor, replayUpper, readFloor),
    [snapshot, replayCursor, replayUpper, readFloor],
  );
  // 지난 방송 채팅을 읽는 중이거나 읽지 못했을 때의 말. 목록의 상태 줄과 화면 낭독(statusNote)이 이 값 하나를 쓴다: 따로 가르면
  // 한쪽만 바뀔 때 둘이 다른 말을 한다(리뷰 2라운드). 끝난 방송인데 아직 영상 시각을 모르면(명부 답 전, 녹화가 자리를 알리기 전)
  // 빈 창이 아니라 읽는 중이다
  const replayWaiting = ended && replay === null;
  const statusNote = replay === null ? (ended ? REPLAY_LOADING : null) : REPLAY_NOTE[replay.status];
  const messages = useMemo<ChatPanelMessage[]>(() => {
    if (replay === null && !ended) return items.map(toPanel);
    // 줄 번호는 로더가 매긴 일련번호다: 채팅과 후원은 표가 달라 id가 겹친다
    const lines: ChatPanelMessage[] = (replay?.lines ?? []).map((line) => ({
      ...toPanel(line.item),
      id: line.seq,
    }));
    if (statusNote === null) return lines;
    const note: ChatPanelMessage = { id: -1, kind: 'system', text: statusNote };
    // 그 시각까지 다 읽지 못했으면 맨 아래(그 시각 쪽)에 쓴다: 빈 창을 채팅이 없는 것처럼 보이지 않는다. 그 앞만 모자라면 맨 위에
    // 쓴다: 맨 아래에 쓰면 카드 뒤 ←마다 영상 시각 쪽에 「불러오고 있어요」가 끼었다 빠졌다(리뷰 3라운드)
    const earlier = replay?.status === 'earlierLoading' || replay?.status === 'earlierFailed';
    return earlier ? [note, ...lines] : [...lines, note];
  }, [items, replay, ended, statusNote]);

  const ratePerMinute = useMemo<number | null>(() => {
    // 끝난 화면(명부 답을 기다리는 지난 방송 주소 포함)은 차트가 센 수를 차트가 읽은 길이로 나눈다(12시간 넘는 방송은 끝 12시간).
    // 통계와 같은 수라 반올림하지 않는다: 자릿수는 패널이 통계처럼 맞춘다(리뷰 5라운드). 그 차트가 없으면 모른다: 받아 둔 채팅은
    // 영상 시각 둘레뿐이라 방송 길이로 나눌 수 없다. 통계도 그때 「확인 중」이다(리뷰 4라운드)
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
    // 라이브는 지금 앞 2분, 지난 방송은 영상 시각 앞 2분의 채팅이다(POK-324): 지난 방송을 지금 기준으로 세면 늘 비었다. 끝난
    // 방송인데 시점을 아직 모르면 비운다: 라이브 채팅으로 세면 라이브가 끝나 녹화를 기다리는 동안 빈 채팅 창 옆에 방송 끝 무렵
    // 키워드가 섰다(PR #230 codex)
    const recent =
      replay !== null && replayCursor !== null
        ? replay.lines
            .filter((line) => line.at >= replayCursor - 2 * 60_000)
            .map((line) => line.item)
        : ended
          ? []
          : items.filter((it) => Date.parse(it.time) >= Date.now() - 2 * 60_000 + CHAT_LEAD_MS);
    const freq = new Map<string, number>();
    for (const it of recent) {
      if (it.kind !== 'chat') continue;
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
  }, [items, replay, replayCursor, ended]);

  // 채팅이 고른 시점을 따라가려면 그 시점을 절대 시각으로 바꿀 기준점이 있어야 한다(시작 시각을 모르는 방송은 없을 수 있다)
  const followsPlayhead = replayUpper !== null && vodBase !== null;
  return {
    surges,
    messages,
    ratePerMinute,
    followsPlayhead,
    replayClock: followsPlayhead && live.playheadMs !== null ? msToClock(live.playheadMs) : null,
    replayWaiting,
    statusNote,
  };
}
