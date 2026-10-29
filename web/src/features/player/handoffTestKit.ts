import type { RewindStatusResult } from '@/api/rewindStatus';
import type { PlaybackSessionState, SessionEvent, StreamPollEvent, XAnswer } from './rewindSwitch';

// 세션 표 시험의 사건 도우미(POK-200 c13). 위치 사건은 재생 여부 · 연속 길이를,
// 스트림 폴링 사건은 물음 순번을 싣는다. 제품 모듈은 타입만 읽는다 — 제품 파일을
// 되돌린 트리(음성 대조)에서도 이 파일이 로드되게.

type PositionEvent = Extract<SessionEvent, { type: 'position' }>;
type LiveProbeEvent = Extract<SessionEvent, { type: 'live-probe' }>;

/**
 * 라이브 창 28초(4초 × 7) 가운데 하한(첫 조각 + 3 × TD) 뒤의 폭 — 탐침 결과의
 * 라이브 끝 = 하한 + 이 값
 */
const LIVE_END_AFTER_FLOOR_MS = 16_000;

let lastPosition: PositionEvent | null = null;
let streamSeq = 0;

/** 시험마다 처음부터 센다(beforeEach) — 앞 시험의 위치 · 순번이 넘어오지 않게 */
export function resetEventKit(): void {
  startRun();
  streamSeq = 0;
}

/**
 * 새 재생 구간을 연다 — 다음 pos 의 runMs 는 0 부터 센다. 한 시험 안에서 세션을
 * 새로 열 때마다 부른다(앞 세션의 위치에서 이어 세면 연속 검사가 엉뚱한 길이를
 * 읽는다). 넘김(handedOff)도 새 재생기라 스스로 부른다
 */
export function startRun(): void {
  lastPosition = null;
}

/**
 * 아직 찍지 않은 위치 시대 — 먹일 때 stamped 가 그때 세션의 시대를 찍는다(지금
 * 재생기가 잰 위치). 찍지 않고 먹이면 어느 시대와도 달라 버려진다
 */
export const UNSTAMPED = -1;

/** 시대를 찍지 않은 위치 사건에 지금 세션의 위치 시대를 찍는다 — 그 밖의 사건은 그대로 */
export function stamped(state: PlaybackSessionState, event: SessionEvent): SessionEvent {
  if (event.type !== 'position' || event.epoch !== UNSTAMPED) return event;
  return { ...event, epoch: state.positionEpoch };
}

/**
 * 위치 사건. 기본은 재생 중이고, runMs 는 앞 위치에서 이어 센다 — 앞으로 간 만큼
 * 늘고, 뒤로 가거나 처음이면 0 이다
 */
export function pos(
  atMs: number,
  patch: { runMs?: number; playing?: boolean; epoch?: number } = {},
): PositionEvent {
  const prev = lastPosition;
  const onward = prev !== null && atMs >= prev.atMs;
  const carried = onward ? prev.runMs + (atMs - prev.atMs) : 0;
  const event: PositionEvent = {
    type: 'position',
    atMs,
    playing: patch.playing ?? true,
    runMs: patch.runMs ?? carried,
    epoch: patch.epoch ?? UNSTAMPED,
  };
  lastPosition = event;
  return event;
}

/**
 * 스트림 폴링 사건. 순번은 부를 때마다 1씩 오른다. receivedAtMs 는 응답을 받은
 * 시각이다 — 신선도를 보지 않는 시험은 0 그대로 둔다
 */
export function streamPoll(
  result: RewindStatusResult,
  x?: XAnswer,
  receivedAtMs = 0,
): StreamPollEvent {
  streamSeq += 1;
  const ask = { seq: streamSeq, receivedAtMs };
  return x === undefined
    ? { type: 'stream-poll', result, ask }
    : { type: 'stream-poll', result, x, ask };
}

/**
 * 나가 있는 탐침의 결과 사건 — 라이브 창 하한이 floorMs 다. 기본은
 * P − 2000(무잘림)이다. 나가 있는 탐침이 없으면 마지막 순번을 싣는다(넘김 행이
 * 없는 트리에서는 아무 행도 받지 않는다)
 */
export function settleProbe(
  session: PlaybackSessionState,
  floorMs = (session.lastAtMs ?? 0) - 2_000,
): LiveProbeEvent {
  const seq = session.handoffProbe?.seq ?? session.probeSeq;
  const endMs = floorMs + LIVE_END_AFTER_FLOOR_MS;
  return { type: 'live-probe', seq, result: { kind: 'ok', floorMs, endMs } };
}

/**
 * 꼬리 넘김(P1) 직후의 세션 — 넘긴 순간의 P(lastAtMs) · E(list.endMs) 로 확인을
 * 시작하고, 시한은 now + (E − P) + 2 × TD 다. 넘김 행(c13h)이 들기 전에 확인 행을
 * 시험하려고 결과 모양만 만든다
 */
export function handedOff(session: PlaybackSessionState, now: number): PlaybackSessionState {
  // 꼬리 넘김은 목록을 받고 위치가 선 되감기에서만 난다 — 부르는 시험이 list · lastAtMs 를 갖춰 둔다
  const list = session.list!;
  const atMs = session.lastAtMs!;
  const generation = session.source.generation + 1;
  const deadlineMs = now + (list.endMs - atMs) + 2 * list.tdMs;
  const url = session.liveUrl;
  const fromUrl = session.source.url;
  startRun();
  return {
    ...session,
    phase: { kind: 'live' },
    source: { kind: 'live', url, startAtMs: atMs, generation, strictStart: true },
    boundLive: true,
    foreignLive: null,
    liveChecking: false,
    offer: null,
    handoff: { fromUrl, endMs: list.endMs, atMs, deadlineMs, seenAtMs: null },
    handoffDoor: null,
    handoffProbe: null,
  };
}
