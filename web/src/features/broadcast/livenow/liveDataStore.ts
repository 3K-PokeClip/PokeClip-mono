'use client';

// 라이브 화면 훅들이 같이 쓰는 작은 저장소(POK-251) — 방송 번호·방송 상태·차트·방송 정보를 한 곳에 둔다.
// 훅마다 따로 받으면 같은 화면의 두 패널이 서로 다른 방송·시각을 보게 된다.
import { useSyncExternalStore } from 'react';
import type { LivePosition } from '@/features/player/useHlsPlayback';

export interface LiveChartBucket {
  start: string;
  chats: number;
  donations: number;
}
/**
 * 차트가 실제로 읽은 창. 방송 전체 · 끝 12시간(그보다 긴 방송, 시작 시각을 모르는 방송) · 지금 기준 최근 10분(라이브, 라이브
 * 주소에서 방송 상태를 아직 모를 때). 이름표(「방송 중 채팅」·그래프 끝 시각)와 분당 수는 방송 상태가 아니라 이 값을 본다:
 * 상태로 다시 계산하면 아직 남아 있는 다른 창의 차트에 엉뚱한 이름을 붙인다(QA 2026-10-11 리뷰 2라운드)
 */
export type LiveChartScope = 'broadcast' | 'tail' | 'recent';
export interface LiveChart {
  bucketSeconds: number;
  buckets: LiveChartBucket[];
  appliedOffsetMs: number;
  scope: LiveChartScope;
}
/**
 * 방송 정보의 시청자 기록(series)을 어느 창으로 읽었나. 끝난 방송의 구간(since)이면 broadcast, 수집기 기본인 지금 기준 최근
 * 1시간이면 recent다. 끝난 방송의 시청자 수·통계는 broadcast만 믿는다: 명부 답 전에 recent로 읽은 빈 기록을 끝난 방송의 것으로
 * 보고 「기록 없음」이라 했다(리뷰 4라운드). 제목·카테고리·태그(latest)는 창과 무관해 언제든 쓴다
 */
export type LiveInfoScope = 'broadcast' | 'recent';
export interface LiveInfo {
  latest: { title: string | null; tags: string[]; category: string | null } | null;
  /** category는 수집기가 관측마다 싣기 전엔 없다 — 오면 통계가 카테고리가 바뀐 구간을 나눈다 */
  series: { observedAt: string; viewers: number | null; category?: string | null }[];
  scope: LiveInfoScope;
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
  /**
   * 지난 방송의 재생 위치(시각 기준점 축 ms). 녹화가 흐르는 자리이고, 영상이 없으면 누른 카드 시점이다. 채팅이 이 시각까지의 줄만
   * 보인다(POK-324). 모르면 null이고 채팅은 방송 끝까지다
   */
  playheadMs: number | null;
  /**
   * 라이브 재생기를 실시간에서 충분히(← 한 번) 되감았을 때 그 자리(POK-326). 채팅이 그 시각까지의 줄만 보인다. 실시간에 있거나
   * 영상 시각을 모르면(목록에 시각 표시가 없음) null이고 채팅은 최신이다
   */
  liveRewind: LivePosition | null;
  /** 이 방송과 나의 관계 — OWNER(내 방송) · EDITOR(위임받은 방송). 모르면 null */
  relation: string | null;
  /**
   * 방송 기준 0초의 절대 시각(epoch ms) — 녹화 첫 조각의 시작. 카드·플레이어의 시점(playheadMs)이 이 축이다.
   * 녹화를 알면 플레이어가 채우고, 모르면 null(그때는 방송 시작 시각으로 대신한다 — README 「시각 기준점」)
   */
  timeBaseMs: number | null;
  /** 서버가 준 시각 기준점(epoch ms, POK-255) — 녹화 첫 조각의 절대 시각. 조각이 아직 없으면 null */
  originMs: number | null;
  /** 방송 목록 줄의 제목(POK-259). 방송 정보 관측에 제목이 비었을 때 대신 쓴다. 없으면 null */
  listTitle: string | null;
  /** 방송 목록 줄의 스트리머 채널 이름(POK-430). 위임받은 방송의 채널 이름이다. 옛 서버면 null */
  channelNames: { CHZZK: string | null; SOOP: string | null } | null;
}

/**
 * 카드 ms·재생 위치(playheadMs)의 0초가 되는 절대 시각. <b>틀고 있는 녹화가 있으면 그 시작</b>이 먼저다 — 다시보기 플레이어가 그 축으로
 * 옮기므로 채팅·차트도 같은 축이어야 화면의 영상과 맞는다. 녹화가 없으면 서버 기준점(POK-255), 그것도 없으면 방송 시작 시각으로
 * 대신한다(수십 초 어긋날 수 있다 — README 「시각 기준점」). 플레이어를 서버 기준점 축으로 옮기는 것은 재생기 카드(POK-253)
 */
export function timeBaseOf(
  live: Pick<LiveData, 'originMs' | 'timeBaseMs' | 'startedAt'>,
): number | null {
  return live.timeBaseMs ?? live.originMs ?? live.startedAt;
}

/**
 * 차트가 읽은 길이(분)와 그 안의 채팅 수. 수집기가 빈 칸도 0으로 채워 주므로 칸 수 × 칸 길이가 읽은 길이다. 통계의 분당 평균과
 * 지난 방송 채팅 패널의 「분당 평균 N」이 이것 하나로 센다: 따로 세면 한 화면의 두 수가 갈라진다(리뷰 3라운드). 차트가 없으면
 * 둘 다 수를 적지 않는다(리뷰 4라운드)
 */
export function chartSpan(chart: LiveChart): { minutes: number; chats: number } {
  return {
    minutes: Math.max(1, (chart.buckets.length * chart.bucketSeconds) / 60),
    chats: chart.buckets.reduce((sum, bucket) => sum + bucket.chats, 0),
  };
}

/**
 * 분당 수를 쓰는 꼴(소수 한 자리, 천 단위 쉼표). 통계의 「분당 평균 채팅」과 끝난 방송 채팅 바닥이 같은 평균이라 같은 꼴로 쓴다:
 * 자릿수가 다르면 한 화면에 1과 0.7이 섰고(리뷰 5라운드), 쉼표가 빠지면 큰 방송만 「1234.5」가 됐다(리뷰 6라운드)
 */
export function formatPerMinute(rate: number): string {
  return rate.toLocaleString('ko-KR', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
}

/**
 * 시청자 기록 중 화면이 믿는 것. 끝난 방송은 방송 창으로 읽은 기록만(명부 답 전에 최근 창으로 읽은 빈 기록은 그 방송의 것이
 * 아니다, 리뷰 4라운드), 라이브는 받은 기록이다. 상태를 모르거나 받기 전이면 null이다. 정보 바의 시청자 수와 통계의 최고·평균이
 * 이것 하나로 센다(리뷰 5라운드)
 */
export function trustedViewerSeries(
  view: LiveBroadcastStatus,
  info: LiveInfo | null,
): LiveInfo['series'] | null {
  if (view === 'ended') return info?.scope === 'broadcast' ? info.series : null;
  if (view === 'live') return info?.series ?? null;
  return null;
}

/**
 * 값이 없는 자리에 쓰는 말. 모르면(받기 전, 상태를 모름, 명부에 없음) 「확인 중」, 끝난 방송에 없으면 「기록 없음」, 라이브에서
 * 받았는데 아직 못 쟀으면 「수집 전」이다. 칸마다 따로 정해서 한 화면에 「확인 중」과 「수집 전」이 같이 섰다(리뷰 5라운드)
 */
export function missingWord(view: LiveBroadcastStatus, received: boolean): string {
  if (!received || view === 'unknown' || view === 'offline') return '확인 중';
  return view === 'ended' ? '기록 없음' : '수집 전';
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
  liveRewind: null,
  relation: null,
  timeBaseMs: null,
  originMs: null,
  listTitle: null,
  channelNames: null,
};
let state: LiveData = INITIAL;
const listeners = new Set<() => void>();

export function publishLiveData(patch: Partial<LiveData>) {
  // 방송 번호가 바뀌면 앞 방송의 값은 통째로 버린다. 칸마다 따로 비우면 빠뜨린 칸(관계 등)이 다음 방송에 남았다(리뷰 4라운드).
  // 방송 번호를 올리는 곳은 방송을 고르는 훅 하나다
  const base = patch.streamId !== undefined && patch.streamId !== state.streamId ? INITIAL : state;
  // 바뀐 칸이 없으면 알리지 않는다: 라이브 재생기는 실시간에 있는 동안에도 매초 같은 값(liveRewind: null)을 싣는데, 알리면
  // 구독하는 화면(카드마다·통계)이 매초 다시 그려졌다(POK-326 리뷰 1라운드)
  const keys = Object.keys(patch) as (keyof LiveData)[];
  if (base === state && keys.every((key) => Object.is(state[key], patch[key]))) return;
  state = { ...base, ...patch };
  listeners.forEach((l) => l());
}

/** 앞 방송의 값을 통째로 비운다. 라이브 화면이 설 때 그리기 전에 부르고(LiveScreen), 시험 사이에도 부른다 */
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
