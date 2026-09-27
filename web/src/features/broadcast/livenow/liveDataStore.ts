'use client';

// 라이브 화면 훅들이 같이 쓰는 작은 저장소(POK-251) — 방송 번호·방송 상태·차트·방송 정보를 한 곳에 둔다.
// 훅마다 따로 받으면 같은 화면의 두 패널이 서로 다른 방송·시각을 보게 된다.
import { useSyncExternalStore } from 'react';

export interface LiveChartBucket {
  start: string;
  chats: number;
  donations: number;
}
export interface LiveChart {
  bucketSeconds: number;
  buckets: LiveChartBucket[];
  appliedOffsetMs: number;
}
export interface LiveInfo {
  latest: { title: string | null; tags: string[]; category: string | null } | null;
  series: { observedAt: string; viewers: number | null }[];
}
export type LiveBroadcastStatus = 'live' | 'ended' | 'offline' | 'unknown';
export interface LiveData {
  streamId: string;
  chart: LiveChart | null;
  info: LiveInfo | null;
  cardTimes: number[]; // 카드 생성 시각(epoch ms)
  status: LiveBroadcastStatus;
  startedAt: number | null; // epoch ms
  endedAt: number | null; // epoch ms (지난 방송만)
  /** 카드를 눌러 옮긴 재생 위치(방송 시작 기준 ms). 영상이 없어도 채팅은 그 시점을 보여준다 */
  playheadMs: number | null;
  /** 이 방송과 나의 관계 — OWNER(내 방송) · EDITOR(위임받은 방송). 모르면 null */
  relation: string | null;
}

const INITIAL: LiveData = {
  streamId: '',
  chart: null,
  info: null,
  cardTimes: [],
  status: 'unknown',
  startedAt: null,
  endedAt: null,
  playheadMs: null,
  relation: null,
};
let state: LiveData = INITIAL;
const listeners = new Set<() => void>();

export function publishLiveData(patch: Partial<LiveData>) {
  state = { ...state, ...patch };
  listeners.forEach((l) => l());
}

/** 시험 사이에 앞 시험의 방송이 남지 않게 비운다 */
export function resetLiveData() {
  state = INITIAL;
  listeners.forEach((l) => l());
}

function subscribe(l: () => void) {
  listeners.add(l);
  return () => {
    listeners.delete(l);
  };
}

export function useLiveData(): LiveData {
  return useSyncExternalStore(
    subscribe,
    () => state,
    () => state,
  );
}

// ── 라이브 채팅 중계(POK-234 PR-B): 카드 통로(SSE)로 오는 chat·donation 이벤트를 채팅 패널에 넘긴다 ──
export interface RelayEvent {
  seq: number;
  seqEpoch: number;
  kind: 'chat' | 'donation';
  time: string;
  timeBasis: string;
  nickname: string | null;
  senderChannelId: string;
  role: string | null;
  text: string | null;
  amount: number | null;
  donationType: string | null;
}
const relayListeners = new Set<(ev: RelayEvent) => void>();
export function subscribeRelay(fn: (ev: RelayEvent) => void): () => void {
  relayListeners.add(fn);
  return () => {
    relayListeners.delete(fn);
  };
}
export function emitRelay(ev: RelayEvent) {
  relayListeners.forEach((fn) => fn(ev));
}
