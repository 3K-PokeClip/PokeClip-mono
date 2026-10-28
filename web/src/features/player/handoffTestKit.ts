import type { RewindStatusResult } from '@/api/rewindStatus';
import type { SessionEvent, XAnswer } from './rewindSwitch';

// 세션 표 시험의 사건 도우미(POK-200 c13). 위치 사건은 재생 여부 · 연속 길이를, 스트림 폴링 사건은 물음 순번을 싣는다.
// 제품 모듈은 타입만 읽는다 — 제품 파일을 되돌린 트리(음성 대조)에서도 이 파일이 로드되게.

type PositionEvent = Extract<SessionEvent, { type: 'position' }>;
type StreamPollEvent = Extract<SessionEvent, { type: 'stream-poll' }>;

let lastPosition: PositionEvent | null = null;
let streamSeq = 0;

/** 시험마다 처음부터 센다(beforeEach) — 앞 시험의 위치 · 순번이 넘어오지 않게 */
export function resetEventKit(): void {
  lastPosition = null;
  streamSeq = 0;
}

/**
 * 위치 사건. 기본은 재생 중이고, runMs 는 앞 위치에서 이어 센다 — 앞으로 간 만큼 늘고, 뒤로 가거나 처음이면 0 이다
 */
export function pos(
  atMs: number,
  patch: { runMs?: number; playing?: boolean } = {},
): PositionEvent {
  const prev = lastPosition;
  const carried = prev !== null && atMs >= prev.atMs ? prev.runMs + (atMs - prev.atMs) : 0;
  const event: PositionEvent = {
    type: 'position',
    atMs,
    playing: patch.playing ?? true,
    runMs: patch.runMs ?? carried,
  };
  lastPosition = event;
  return event;
}

/**
 * 스트림 폴링 사건. 순번은 부를 때마다 1씩 오른다. receivedAtMs 는 응답을 받은 시각이다 — 신선도를 보지 않는 시험은 0 그대로
 * 둔다
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
