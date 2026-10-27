import type { RewindStatusResult } from '@/api/rewindStatus';
import type {
  PlaybackSessionState,
  SessionEvent,
  StreamAsk,
  Transition,
  XAnswer,
} from './rewindSwitch';

// 되감기 → 라이브 꼬리 넘김의 칸과 행 몸통(POK-200 c13 · 설계 r9 2절 · 4절). rewindSwitch 는 여기 함수를 한 줄로 부르고,
// 여기는 rewindSwitch 의 타입만 읽는다.

/** 문이 열려 있는 폭 = REWIND_POLL_MS + REWIND_RETRY_MS — 폴링 한 번이 재시도로 늦어도 닫히지 않는다(T14 가 대조한다) */
export const HANDOFF_DOOR_FRESH_MS = 12_000;

type StreamPollEvent = Extract<SessionEvent, { type: 'stream-poll' }>;

/** 지금 소스 목록 — endMs = 마지막 조각 끝(E) · tdMs = 목표 길이 · sealed = ENDLIST 를 봤다 */
export interface SourceList {
  endMs: number;
  tdMs: number;
  sealed: boolean;
}

/** 넘김 근거 — live ∧ ready 응답 하나. atMs = 세운(고친) 응답의 수신 시각 */
export interface HandoffDoor {
  url: string;
  atMs: number;
}

/** 나가 있는 탐침 하나. 근거는 문이 들고 결과 때 다시 본다 */
export interface HandoffProbe {
  seq: number;
  fromUrl: string;
  endMs: number;
}

/** endMs = 넘긴 순간의 E · atMs = 넘긴 순간의 P · seenAtMs = 연속 검사를 통과한 마지막 위치(아직 없으면 null) */
export interface Handoff {
  fromUrl: string;
  endMs: number;
  atMs: number;
  deadlineMs: number;
  seenAtMs: number | null;
}

/** 같은 url 로 다시 넘기지 않는 막음. reason 은 관측 · 시험 전용이고 판정은 읽지 않는다 */
export interface HandoffBlock {
  url: string;
  endMs: number;
  reason: 'reverted' | 'truncated' | 'unknown';
}

/** 재생 세션에 더하는 넘김 칸 아홉 */
export interface HandoffFields {
  /** 마지막 위치 사건의 재생 여부 */
  playing: boolean;
  list: SourceList | null;
  handoffDoor: HandoffDoor | null;
  handoff: Handoff | null;
  handoffBlock: HandoffBlock | null;
  /** 반영한 마지막 근거 응답(ok · none)의 물음 순번 */
  streamAsk: number;
  handoffProbe: HandoffProbe | null;
  probeSeq: number;
  /** 잘림 안내를 이미 띄운 라이브 url */
  liveHintUrl: string | null;
}

/** 세션을 열 때의 넘김 칸 */
export function openHandoffFields(): HandoffFields {
  return {
    playing: false,
    list: null,
    handoffDoor: null,
    handoff: null,
    handoffBlock: null,
    streamAsk: 0,
    handoffProbe: null,
    probeSeq: 0,
    liveHintUrl: null,
  };
}

/**
 * A0 · A0′ · A0″ — 반영할 물음 순번. 이미 반영한 순번 이하면 null(버림)이고, 근거 응답(ok · none)만 순번을 올린다
 */
export function nextStreamAsk(
  s: PlaybackSessionState,
  ask: StreamAsk,
  result: RewindStatusResult,
): number | null {
  if (ask.seq <= s.streamAsk) return null;
  return result.kind === 'unavailable' ? s.streamAsk : ask.seq;
}

/** 문이 열렸나 — 지금 소스 url 의 문이고, 받은 시각부터 12초 안이다 */
export function doorOpen(s: PlaybackSessionState, now: number): boolean {
  const door = s.handoffDoor;
  return door !== null && door.url === s.source.url && now - door.atMs <= HANDOFF_DOOR_FRESH_MS;
}

/**
 * D 표 — 이번 응답 뒤의 문. 지금 url 의 live ∧ ready 가 근거다. x 가 보던 방송이면 받은 시각으로 세우고(D1) · 모르면 그대로(D3) ·
 * 잠시 불가도 그대로(D5) · 그 밖의 응답(ending · vod · ended · ready:false · 다른 url · 404 · 다른 방송)은 지운다(D4)
 */
export function nextDoor(s: PlaybackSessionState, event: StreamPollEvent): HandoffDoor | null {
  const { result, x, ask } = event;
  if (result.kind === 'unavailable') return s.handoffDoor;
  const status = result.kind === 'ok' ? result.status : null;
  if (status?.mode !== 'live' || !status.ready || status.url !== s.source.url) return null;
  if (x === undefined || x.kind === 'unknown') return s.handoffDoor;
  return x.id === s.roundId ? { url: s.source.url, atMs: ask.receivedAtMs } : null;
}

/** D 표를 상태에 적는다 — 문이 그대로면 같은 객체 */
export function withNextDoor(
  s: PlaybackSessionState,
  event: StreamPollEvent,
): PlaybackSessionState {
  const handoffDoor = nextDoor(s, event);
  return handoffDoor === s.handoffDoor ? s : { ...s, handoffDoor };
}

/**
 * R9 · 끝남 대기 이어짐 — 새 url 로 갈아 끼울 때의 문. x 가 보던 방송이면 새 url 의 문을 세우고, 다른 방송이면 비우고, 모르면
 * s 의 문 그대로다(R9 는 응답 앞의 문을 넘긴다)
 */
export function movedDoor(
  s: PlaybackSessionState,
  url: string,
  x: XAnswer | undefined,
  ask: StreamAsk,
): HandoffDoor | null {
  if (x === undefined || x.kind === 'unknown') return s.handoffDoor;
  return x.id === s.roundId ? { url, atMs: ask.receivedAtMs } : null;
}

/** 지금 url 의 목록 로드가 고친 list. 같은 url 의 봉인은 내리지 않는다(복구 startLoad 뒤 다시 와도) */
export function loadedList(
  s: PlaybackSessionState,
  event: Extract<SessionEvent, { type: 'list-loaded' }>,
): SourceList {
  return {
    endMs: event.lastEndMs,
    tdMs: event.targetDurationS * 1000,
    sealed: s.list?.sealed ?? false,
  };
}

/** A3 — ENDLIST 면 list 가 있을 때 봉인한다 */
export function sealList(s: PlaybackSessionState): PlaybackSessionState {
  return s.list === null ? s : { ...s, list: { ...s.list, sealed: true } };
}

/**
 * A6 — 모든 전이의 후처리. url 이 바뀌면 list 를, live 가 아니면 handoff 를, url 이 바뀌거나 rewind · 끝남 대기가 아니면
 * 탐침을 비운다. 문은 url 대조로 닫히므로 두고, 비울 것이 없으면 같은 객체를 돌려준다
 */
export function settleHandoffFields(
  prev: PlaybackSessionState,
  next: PlaybackSessionState,
): PlaybackSessionState {
  const urlMoved = next.source.url !== prev.source.url;
  const kind = next.phase.kind;
  const list = urlMoved ? null : next.list;
  const handoff = kind === 'live' ? next.handoff : null;
  const probing = !urlMoved && (kind === 'rewind' || kind === 'ending-wait');
  const handoffProbe = probing ? next.handoffProbe : null;
  if (list === next.list && handoff === next.handoff && handoffProbe === next.handoffProbe) {
    return next;
  }
  return { ...next, list, handoff, handoffProbe };
}

/** A0 · A3 전처리와 A6 후처리로 국면 표(step)를 감싼다. 버린 물음은 상태 그대로 · 효과 0 이다 */
export function withHandoffRules(
  s: PlaybackSessionState,
  event: SessionEvent,
  step: (ready: PlaybackSessionState) => Transition,
): Transition {
  let ready = s;
  if (event.type === 'stream-poll') {
    const streamAsk = nextStreamAsk(s, event.ask, event.result);
    if (streamAsk === null) return { state: s, effects: [] };
    if (streamAsk !== s.streamAsk) ready = { ...s, streamAsk };
  }
  if (event.type === 'endlist') ready = sealList(s);
  const next = step(ready);
  const state = settleHandoffFields(s, next.state);
  return state === next.state ? next : { state, effects: next.effects };
}
