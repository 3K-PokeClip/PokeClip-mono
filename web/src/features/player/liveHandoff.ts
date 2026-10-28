import type { RewindStatusResult } from '@/api/rewindStatus';
import type { LiveProbeResult } from './liveWindowProbe';
import type {
  PlaybackSessionState,
  SessionEvent,
  StreamAsk,
  Transition,
  XAnswer,
} from './rewindSwitch';
import { replace } from './sessionShared';

// 되감기 → 라이브 꼬리 넘김의 칸과 행 몸통(POK-200 c13 · 설계 r9 2절 · 4절). rewindSwitch 는 여기 함수를 한 줄로 부르고,
// 여기는 rewindSwitch 의 타입만 읽는다.

/** 넘길 때의 안내(문구 확정은 설계 16절 2 · PR 머지 조건) */
export const HANDOFF_NOTICE = '라이브로 이어서 볼게요';
/** 무잘림 게이트가 닫혔을 때의 안내 — url 하나에 한 번(문구 확정은 설계 16절 2 · PR 머지 조건) */
export const HANDOFF_SKIP_NOTICE = '라이브로 가려면 LIVE 를 눌러 주세요';
/** 띠 = 목록 끝 E 앞 이 값 × TD_되감기 — 이 안의 재생 위치만 넘김을 청한다 */
export const HANDOFF_BAND_TD = 1;
/** 확인 시한의 여유 = 이 값 × TD_되감기(넘긴 시각 + (E − P) + 여유) */
export const HANDOFF_CONFIRM_SLACK_TD = 2;
/** 연속 시작이 P 보다 늦어도 되는 폭 = hls.js 시작 맞춤 폭 max(maxBufferHole 0.1, maxFragLookUpTolerance 0.25) 기본값 */
export const HANDOFF_RUN_TOL_MS = 250;

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

/** 띠 안인가 — 마지막 위치가 지금 목록 끝 E 앞 1 × TD_되감기 안이다 */
export function inHandoffBand(s: PlaybackSessionState): boolean {
  const { list, lastAtMs } = s;
  return list !== null && lastAtMs !== null && lastAtMs >= list.endMs - HANDOFF_BAND_TD * list.tdMs;
}

/** 넘김 조건 가운데 국면과 무관한 몫 — 봉인 전 목록 · 재생 중 · 띠 안 · 문 열림 · 막히지 않은 url */
function tailOpen(s: PlaybackSessionState, now: number): boolean {
  if (s.list === null || s.list.sealed || !s.playing || !inHandoffBand(s)) return false;
  return doorOpen(s, now) && s.handoffBlock?.url !== s.source.url;
}

/** R6 · R11 의 넘김 조건 — rewind ∧ 다음 url 대기(pendingNext) 없음 ∧ 공통 몫 */
export function canHandOffFromRewind(s: PlaybackSessionState, now: number): boolean {
  return s.phase.kind === 'rewind' && s.phase.pendingNext === null && tailOpen(s, now);
}

/** E1 의 넘김 조건 — 되감기로 끝에 닿은 끝남 대기(녹화로 가기로 정하기 전) ∧ 공통 몫. 이번 응답 대신 문을 본다 */
export function canHandOffFromEnding(s: PlaybackSessionState, now: number): boolean {
  const { phase } = s;
  if (phase.kind !== 'ending-wait' || s.source.kind !== 'rewind') return false;
  return phase.origin === 'rewind' && phase.atEnd && !phase.recordOnEnd && tailOpen(s, now);
}

/** C_tail — 국면이 rewind 면 R6 의, 끝남 대기면 E1 의 넘김 조건 */
function canHandOff(s: PlaybackSessionState, now: number): boolean {
  return canHandOffFromRewind(s, now) || canHandOffFromEnding(s, now);
}

/**
 * R6 · R11 · E1 — 넘김 조건이 참이고 나가 있는 탐침이 없으면 라이브 창 탐침을 청한다. 넘김은 탐침 결과(P1)가 한다. 청하지 않으면
 * null 이다(R6′ · R7 · R8 은 상태 그대로)
 */
export function requestProbe(s: PlaybackSessionState, now: number): Transition | null {
  if (s.list === null || s.handoffProbe !== null || !canHandOff(s, now)) return null;
  const seq = s.probeSeq + 1;
  const handoffProbe = { seq, fromUrl: s.source.url, endMs: s.list.endMs };
  return {
    state: { ...s, probeSeq: seq, handoffProbe },
    effects: [{ kind: 'probe-live', seq, url: s.liveUrl }],
  };
}

/** P1 — P 에서 라이브로 넘긴다. 시작이 잘리면 틀지 않는 소스이고, 확인 시한은 (E − P) + 2 × TD 뒤다 */
function handOffToLive(
  s: PlaybackSessionState,
  list: SourceList,
  atMs: number,
  now: number,
): Transition {
  const generation = s.source.generation + 1;
  const deadlineMs = now + (list.endMs - atMs) + HANDOFF_CONFIRM_SLACK_TD * list.tdMs;
  return {
    state: {
      ...s,
      phase: { kind: 'live' },
      source: { kind: 'live', url: s.liveUrl, startAtMs: atMs, generation, strictStart: true },
      boundLive: true,
      foreignLive: null,
      liveChecking: false,
      offer: null,
      handoff: { fromUrl: s.source.url, endMs: list.endMs, atMs, deadlineMs, seenAtMs: null },
      handoffDoor: null,
      handoffProbe: null,
    },
    effects: [{ kind: 'notice', text: HANDOFF_NOTICE }, { kind: 'refresh' }],
  };
}

/**
 * 탐침 결과(P0–P4) — 결과가 온 그 순간의 문 · 국면 · 위치로 넘김 조건을 다시 본다. 순번이 나가 있는 탐침과 다르면 버리고(P0),
 * 조건이 거짓이면 탐침만 비운다(P4). 하한이 P 이하면 넘기고(P1), P 뒤면 잘림이라 막고 안내하며(P2), 잴 수 없으면 막는다(P3)
 */
export function onLiveProbe(
  s: PlaybackSessionState,
  seq: number,
  result: LiveProbeResult,
  now: number,
): Transition {
  if (seq !== s.handoffProbe?.seq) return { state: s, effects: [] };
  const cleared = { ...s, handoffProbe: null };
  const { list, lastAtMs: atMs } = cleared;
  if (list === null || atMs === null || !canHandOff(cleared, now)) {
    return { state: cleared, effects: [] };
  }
  const url = s.source.url;
  if (result.kind === 'unavailable') {
    const handoffBlock = { url, endMs: list.endMs, reason: 'unknown' as const };
    return { state: { ...cleared, handoffBlock }, effects: [] };
  }
  if (result.floorMs <= atMs) return handOffToLive(cleared, list, atMs, now);
  const dMs = result.endMs - list.endMs;
  console.info('[player] handoff_skipped_truncated', { dMs, truncatedMs: result.floorMs - atMs });
  const handoffBlock = { url, endMs: list.endMs, reason: 'truncated' as const };
  return hintLiveOnce({ ...cleared, handoffBlock }, url);
}

/** 확인이 깨졌을 때 이어 볼 자리 = 넘긴 P 와 연속 검사를 통과한 마지막 위치 가운데 늦은 쪽 */
export function handoffResumeMs(handoff: Handoff): number {
  return Math.max(handoff.atMs, handoff.seenAtMs ?? handoff.atMs);
}

/** 넘기기 전 되감기로 돌아가는 source 와 막음 — H0 · H1a · H2 · H2t 공통. handoff 는 국면이 live 를 떠나 A6(H5)가 비운다 */
function revertedState(
  s: PlaybackSessionState,
  handoff: Handoff,
  reason: HandoffBlock['reason'],
): PlaybackSessionState {
  const url = handoff.fromUrl;
  const startAtMs = handoffResumeMs(handoff);
  const generation = s.source.generation + 1;
  const source = { kind: 'rewind' as const, url, startAtMs, generation, strictStart: false };
  const handoffBlock = { url, endMs: handoff.endMs, reason };
  return { ...s, source, handoffBlock, handoffDoor: null, boundLive: false, foreignLive: null };
}

/** H0 · H1a · H2t — 라이브는 살아 있다. 마지막 검증 위치에서 rewind 로 돌아간다 */
export function revertToRewind(
  s: PlaybackSessionState,
  handoff: Handoff,
  reason: 'start' | 'jump' | 'deadline' | 'truncated',
): Transition {
  console.info('[player] handoff_confirm_failed', reason);
  const block = reason === 'truncated' ? 'truncated' : 'reverted';
  const state = revertedState(s, handoff, block);
  return { state: { ...state, phase: { kind: 'rewind', pendingNext: null } }, effects: [] };
}

/** H2 — 라이브가 끝났다는 증거(라이브 404 · ENDLIST · 끝 도달). 되감기 기원 끝남 대기로 간다 */
export function revertEndedHandoff(
  s: PlaybackSessionState,
  handoff: Handoff,
  now: number,
): Transition {
  const state = revertedState(s, handoff, 'reverted');
  return {
    state: {
      ...state,
      phase: { kind: 'ending-wait', origin: 'rewind', atEnd: false, recordOnEnd: false },
      endingSince: s.endingSince ?? now,
      offer: null,
    },
    effects: [{ kind: 'refresh', retryWhileUrl: handoff.fromUrl }],
  };
}

/** 잘림 안내(P2 · H2t) — 그 url 로 아직 안 했으면 LIVE 안내를 한 번 하고 적어 둔다 */
function hintLiveOnce(state: PlaybackSessionState, url: string): Transition {
  if (state.liveHintUrl === url) return { state, effects: [] };
  return {
    state: { ...state, liveHintUrl: url },
    effects: [{ kind: 'notice', text: HANDOFF_SKIP_NOTICE }],
  };
}

/** H2t — 라이브 첫 목록이 잘렸다. 되돌리고, 그 url 로 아직 안 했으면 LIVE 안내를 한 번 한다 */
function revertTruncated(s: PlaybackSessionState, handoff: Handoff): Transition {
  const reverted = revertToRewind(s, handoff, 'truncated');
  // url 은 싣지 않는다 — 경로에 스트림 키 · 세션 값이 든다(P2 로그와 같은 규칙)
  console.info('[player] handoff_skipped_truncated', { stage: 'start' });
  return hintLiveOnce(reverted.state, handoff.fromUrl);
}

/** H1a · H1 · H1c — 위치 하나로 연속 검사를 한다(A1 을 반영한 상태로) */
function confirmPosition(
  s: PlaybackSessionState,
  handoff: Handoff,
  event: Extract<SessionEvent, { type: 'position' }>,
): Transition {
  if (event.atMs - event.runMs > handoff.atMs + HANDOFF_RUN_TOL_MS) {
    return revertToRewind(s, handoff, handoffResumeMs(handoff) > handoff.atMs ? 'jump' : 'start');
  }
  if (event.atMs >= handoff.endMs) return { state: { ...s, handoff: null }, effects: [] };
  return { state: { ...s, handoff: { ...handoff, seenAtMs: event.atMs } }, effects: [] };
}

/**
 * live 국면 ∧ handoff ≠ null 일 때의 확인 행(H0 · H1a · H1 · H1c · H2 · H2t · H3 · H4). 해당 없으면 null 이고 현행 live 행이
 * 받는다(H1′ playing · H6 시크 · LIVE 포함)
 */
export function onHandoffConfirming(
  s: PlaybackSessionState,
  event: SessionEvent,
  now: number,
): Transition | null {
  const handoff = s.handoff;
  if (s.phase.kind !== 'live' || handoff === null) return null;
  if (now >= handoff.deadlineMs) return revertToRewind(s, handoff, 'deadline');
  switch (event.type) {
    case 'position':
      return confirmPosition(s, handoff, event);
    case 'live-closed':
    case 'endlist':
    case 'end-reached':
      return revertEndedHandoff(s, handoff, now);
    case 'live-start-truncated':
      return revertTruncated(s, handoff);
    case 'remount':
    case 'live-400': {
      // H3 · H4 — 새 재생기도 첫 목록을 다시 본다. 연속 검사는 이어 볼 자리부터
      // 새로 센다(시한은 그대로). H4(목록 400)는 세션의 라이브 주소로 새 소스를 세운다
      const resume = handoffResumeMs(handoff);
      const generation = s.source.generation + 1;
      const source =
        event.type === 'live-400'
          ? { ...replace(s, 'live', s.liveUrl, resume), strictStart: true }
          : { ...s.source, startAtMs: resume, strictStart: true, generation };
      const next = { ...handoff, atMs: resume, seenAtMs: null };
      return { state: { ...s, source, handoff: next }, effects: [] };
    }
    default:
      return null;
  }
}

/** H6 — 사람의 시크 · LIVE 는 확인을 그만둔다. live 에 머문 전이만 비운다(떠난 전이는 A6 의 H5 가 비운다) */
export function endConfirm(t: Transition): Transition {
  const { state } = t;
  if (state.phase.kind !== 'live' || state.handoff === null) return t;
  return { ...t, state: { ...state, handoff: null } };
}

/** A2 — 막은 url 의 목록이 막을 때의 끝보다 자랐으면 막음을 푼다 */
export function releaseBlock(
  s: PlaybackSessionState,
  url: string,
  lastEndMs: number,
): HandoffBlock | null {
  const block = s.handoffBlock;
  return block !== null && block.url === url && lastEndMs > block.endMs ? null : block;
}
