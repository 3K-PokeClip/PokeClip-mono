import type { RewindStatus, RewindStatusResult } from '@/api/rewindStatus';
import { EDGE_MARGIN_TD } from './playbackEdge';

// 재생 세션 상태기계(설계 5.3절 종결 판, POK-200 M5) — 라이브 · 되감기 · 끝남 대기 · 다시보기 사이의 전환을 정하는 순수 함수다.
// 사건 하나를 받아 다음 상태와 효과(재조회 · 안에서 시크 · 알림 · 닫기)를 돌려준다. 시각은 모두 실제 시각(epoch ms)이다.
//
// 읽는 규칙: 한 국면 안에서 위에서부터 처음 맞는 행 하나만 돈다. 어느 행에도 맞지 않는 (국면, 사건)은 무시다(국면 · 소스 그대로).
// 원칙 둘 — W3: 제어면 응답은 재생 중인 소스를 끊는 신호가 아니다. W4: 끝남 대기는 재생기 증거(ENDLIST · 끝 도달 · 라이브
// 닫힘)로만 들어간다. 🔴 ENDLIST 는 「이 목록에 조각이 더 붙지 않는다」이지 재생 완료가 아니다(RFC 8216 4.3.3.4) — atEnd 를
// 참으로 만드는 것은 끝 도달 사건뿐이다.

/** 재접속 대기 = 재접속 창 300초 + 판정 여유 60초(M6 회차 닫힘 조건과 같다). 이 동안 회차별 404 는 「아직 모름」이다 */
export const RESUME_WAIT_MS = 360_000;
/** 끝남 대기 시한(20분). 끝에 닿은 시청자는 녹화로, 아직 보는 시청자는 묻기를 멈추고 끝까지 본 뒤 녹화로 간다 */
export const ENDING_WAIT_MS = 1_200_000;
/** 창 하한 여유를 아직 모를 때(목록 전) 쓰는 값 — TD 4초 기준(설계 5.3.1절) */
const DEFAULT_EDGE_MARGIN_MS = EDGE_MARGIN_TD * 4_000;
/** LIVE 버튼의 「지금 방송 번호」 다시 묻기 상한(2초 간격) */
const X_RECHECK_LIMIT = 3;

export interface PlaybackSource {
  kind: 'live' | 'rewind' | 'vod';
  url: string;
  startAtMs: number | null;
  generation: number;
}

interface RewindRange {
  url: string;
  firstPdtMs: number;
  headPdtMs: number;
}

/** 재생 세션의 국면 — 명부 상태(broadcastStatus)와 따로 움직인다 */
export type SessionPhase =
  | { kind: 'live' }
  /** pendingNext = 지금 위치를 덮지 않는 새 url 과 그 범위(최신 폴링 값). 끝 도달 때 E 를 덮으면 그 E 에서 잇는다 */
  | { kind: 'rewind'; pendingNext: RewindRange | null }
  /** 끝남 대기. origin = 들어온 국면 · atEnd = 실제 재생이 끝에 닿았다 · recordOnEnd = 회차 종국 또는 20분 시한이 섰다 */
  | { kind: 'ending-wait'; origin: 'live' | 'rewind'; atEnd: boolean; recordOnEnd: boolean }
  | { kind: 'vod-offered'; url: string; firstPdtMs: number; headPdtMs: number; atEnd: boolean }
  | { kind: 'vod' };

/** 세션을 닫는 사유. recorded = 녹화 재생 길로 · go-live = 「라이브 보기」 · left = 화면 이탈 · 다른 방송 선택 */
export type SessionClose = 'recorded' | 'go-live' | 'left';

/** 지금 방송 번호 X 를 물은 답. id 가 빈 문자열이면 「방송 중인 것이 없다」 */
export type XAnswer = { kind: 'known'; id: string } | { kind: 'unknown' };

/** 끝남 대기에서 이어짐을 증명하지 못한 새 url 을 봤을 때 내미는 버튼(버튼 규칙) */
type Offer = { kind: 'go-live' } | { kind: 'new-broadcast'; goTo: string };

export interface PlaybackSessionState {
  /** 열 때의 방송 번호(= 계약9 streamId = 회차 ID) — 회차별 경로의 키 */
  roundId: string;
  /** 방송|영상 키 — recorded 닫힘 기록의 키 */
  accessKey: string;
  /** 라이브 목록 주소 — LIVE 로 돌아갈 때의 소스 */
  liveUrl: string;
  phase: SessionPhase;
  source: PlaybackSource;
  /** 마지막으로 본 자리(P) — 리마운트 잇기 · 계승 판정 · 이음 지점 */
  lastAtMs: number | null;
  /** 세션이 본 가장 이른 자리 — VOD 일치 대조 */
  watchedFromMs: number | null;
  /** 녹화 조회의 끝 시각 대체. 이 세션 재생기가 로드한 목록의 조각 끝 최대 · 지금 소스 · 이어짐 url 의 폴링 headPdt */
  observedEndMs: number | null;
  /** 이 세션 회차의 되감기 url — ① 로드한 소스 ② 이어짐으로 받은 url ③ live 국면의 스트림 경로 url. 이어짐 판정에서 거른다 */
  roundRewindUrls: ReadonlySet<string>;
  /**
   * 끝남 대기를 처음 연 시각 — 재접속 대기 · 20분 시한의 기준. 지우는 때는 둘이다: 이어짐 증거로 자동 복귀할 때 · 끝남 대기에서
   * LIVE 로 돌아가 `playing` 이 났을 때(R57)
   */
  endingSince: number | null;
  /** 스트림 경로의 마지막 되감기 진입 재료(mode:live ∧ ready). ready 깜박임에도 지우지 않는다(W3 · R5) */
  entry: RewindRange | null;
  /** 지금 소스 목록의 마지막 조각 끝(E) */
  listEndMs: number | null;
  /** 창 하한 여유(3 × TD) */
  edgeMarginMs: number;
  /** 끝남 대기의 버튼 규칙 결과 */
  offer: Offer | null;
  /** LIVE 를 눌러 X 를 다시 묻는 중(「확인 중」) */
  liveChecking: boolean;
  /** rewind · 끝남 대기 · vod-offered 에서 LIVE 로 들어온 live 다 — 폴링마다 X 를 본다 */
  boundLive: boolean;
  /** 세션에 묶인 live 에서 본 다른 방송 번호 — 띠를 두고 세션 데이터 반영을 멈춘다(R58) */
  foreignLive: string | null;
  /** 명부 ended 를 받았다 — 덮개의 「녹화 보기」를 기본 강조로 둔다 */
  rosterEnded: boolean;
  closed: SessionClose | null;
}

export type SessionButton = 'record' | 'vod' | 'go-live' | 'new-broadcast';

/** 재생기가 로드한 목록의 시크 범위. lowerMs 는 창 하한(첫 조각 + 여유 — vod 는 여유 0), endMs 는 끝(라이브는 라이브 끝) */
export interface SeekView {
  lowerMs: number;
  endMs: number;
}

export type SessionEvent =
  | { type: 'seek'; targetMs: number; view: SeekView }
  /** attempt = 이번이 몇 번째 물음 뒤인가(0 = 누름) */
  | { type: 'live-button'; x: XAnswer; attempt: number }
  | { type: 'playing' }
  /** x = 이번 폴링과 함께 물은 지금 방송 번호(세션에 묶인 live · 끝남 대기 버튼 규칙). 없으면 모름 */
  | { type: 'stream-poll'; result: RewindStatusResult; x?: XAnswer }
  | { type: 'round-poll'; result: RewindStatusResult }
  | { type: 'endlist' }
  | { type: 'end-reached' }
  | { type: 'live-closed' }
  | { type: 'live-400' }
  | { type: 'roster-ended' }
  /** 시한 검사(20분) */
  | { type: 'tick' }
  | { type: 'button'; which: SessionButton }
  | {
      type: 'list-loaded';
      url: string;
      firstMs: number;
      lastEndMs: number;
      targetDurationS: number;
    }
  | { type: 'position'; atMs: number }
  | { type: 'remount' }
  | { type: 'leave' };

export type SessionEffect =
  /** 켜진 경로를 곧바로 다시 묻는다. retryWhileUrl = 스트림 경로 답이 이 url 이면 2초 × 3(R16) */
  | { kind: 'refresh'; retryWhileUrl?: string }
  | { kind: 'seek-within'; atMs: number }
  | { kind: 'seek-live-edge' }
  | { kind: 'notice'; text: string }
  /** LIVE 의 X 를 2초 뒤 다시 묻는다 */
  | { kind: 'recheck-x'; attempt: number }
  /** goTo = 「새 방송 보기」의 방송 번호 */
  | { kind: 'close'; reason: SessionClose; goTo?: string };

export interface Transition {
  state: PlaybackSessionState;
  effects: SessionEffect[];
}

export const SESSION_NOTICES = {
  oldest: '가장 오래된 지점으로 옮겼어요',
  gap: '방송이 끊긴 구간이라 이어지는 자리로 옮겼어요',
  vodStart: '다시보기는 이 시각부터 볼 수 있어요',
} as const;

export function openSession(init: {
  roundId: string;
  accessKey: string;
  liveUrl: string;
}): PlaybackSessionState {
  return {
    ...init,
    phase: { kind: 'live' },
    source: { kind: 'live', url: init.liveUrl, startAtMs: null, generation: 0 },
    lastAtMs: null,
    watchedFromMs: null,
    observedEndMs: null,
    roundRewindUrls: new Set(),
    endingSince: null,
    entry: null,
    listEndMs: null,
    edgeMarginMs: DEFAULT_EDGE_MARGIN_MS,
    offer: null,
    liveChecking: false,
    boundLive: false,
    foreignLive: null,
    rosterEnded: false,
    closed: null,
  };
}

/** 켤 폴링 경로(설계 5.1절 표) */
export function pollTargets(s: PlaybackSessionState): { stream: boolean; round: boolean } {
  if (s.closed !== null) return { stream: false, round: false };
  switch (s.phase.kind) {
    case 'live':
    case 'rewind':
      return { stream: true, round: false };
    case 'ending-wait':
      return s.phase.recordOnEnd ? { stream: false, round: false } : { stream: true, round: true };
    default:
      return { stream: false, round: false };
  }
}

// ── 작은 도구 ──

const none = (state: PlaybackSessionState): Transition => ({ state, effects: [] });

function withUrl(set: ReadonlySet<string>, url: string): ReadonlySet<string> {
  return set.has(url) ? set : new Set([...set, url]);
}

function maxOrSelf(a: number | null, b: number): number {
  return a === null ? b : Math.max(a, b);
}

function readyLive(result: RewindStatusResult): (RewindStatus & RewindRange) | null {
  if (result.kind !== 'ok') return null;
  const { status } = result;
  if (status.mode !== 'live' || !status.ready) return null;
  if (status.url === null || status.firstPdtMs === null || status.headPdtMs === null) return null;
  return { ...status, url: status.url, firstPdtMs: status.firstPdtMs, headPdtMs: status.headPdtMs };
}

function within(t: number | null, first: number, head: number): t is number {
  return t !== null && first <= t && t <= head;
}

function replace(
  s: PlaybackSessionState,
  kind: PlaybackSource['kind'],
  url: string,
  startAtMs: number | null,
): PlaybackSource {
  return { kind, url, startAtMs, generation: s.source.generation + 1 };
}

function close(s: PlaybackSessionState, reason: SessionClose, goTo?: string): Transition {
  const effect: SessionEffect =
    goTo === undefined ? { kind: 'close', reason } : { kind: 'close', reason, goTo };
  return { state: { ...s, closed: reason }, effects: [effect] };
}

function enterEnding(s: PlaybackSessionState, atEnd: boolean, now: number): Transition {
  const origin = s.phase.kind === 'rewind' ? 'rewind' : 'live';
  const retry = s.source.kind === 'rewind' ? { retryWhileUrl: s.source.url } : {};
  return {
    state: {
      ...s,
      phase: { kind: 'ending-wait', origin, atEnd, recordOnEnd: false },
      endingSince: s.endingSince ?? now,
      offer: null,
    },
    effects: [{ kind: 'refresh', ...retry }],
  };
}

// ── 시크 ──

function seekLive(s: PlaybackSessionState, t: number, view: SeekView): Transition {
  if (t >= view.lowerMs)
    return { state: s, effects: [{ kind: 'seek-within', atMs: Math.min(t, view.endMs) }] };
  const entry = s.entry;
  if (entry === null)
    return {
      state: s,
      effects: [
        { kind: 'seek-within', atMs: view.lowerMs },
        { kind: 'notice', text: SESSION_NOTICES.oldest },
      ],
    };
  let start = t;
  const effects: SessionEffect[] = [];
  if (t < entry.firstPdtMs) {
    start = entry.firstPdtMs;
    effects.push({ kind: 'notice', text: SESSION_NOTICES.oldest });
  } else if (t > entry.headPdtMs) {
    start = entry.headPdtMs;
    effects.push({ kind: 'notice', text: SESSION_NOTICES.gap });
  }
  return {
    state: {
      ...s,
      phase: { kind: 'rewind', pendingNext: null },
      source: replace(s, 'rewind', entry.url, start),
      boundLive: false,
      foreignLive: null,
      liveChecking: false,
    },
    effects,
  };
}

/** 로드한 목록 안 시크(되감기 · vod) — 하한 앞은 하한으로 알림, 끝 뒤는 끝으로 */
function seekInList(s: PlaybackSessionState, t: number, view: SeekView): Transition {
  if (t < view.lowerMs)
    return {
      state: s,
      effects: [
        { kind: 'seek-within', atMs: view.lowerMs },
        { kind: 'notice', text: SESSION_NOTICES.oldest },
      ],
    };
  return { state: s, effects: [{ kind: 'seek-within', atMs: Math.min(t, view.endMs) }] };
}

// ── LIVE 버튼 ──

/** rewind · 끝남 대기(atEnd:false) · vod-offered(atEnd:false) 의 LIVE — X 로 가른다(R58) */
function liveFromAway(s: PlaybackSessionState, x: XAnswer, attempt: number): Transition {
  if (x.kind === 'known' && x.id !== '' && x.id !== s.roundId) return close(s, 'left', x.id);
  if (x.kind === 'unknown' && attempt < X_RECHECK_LIMIT)
    return {
      state: { ...s, liveChecking: true },
      effects: [{ kind: 'recheck-x', attempt: attempt + 1 }],
    };
  return {
    state: {
      ...s,
      phase: { kind: 'live' },
      source: replace(s, 'live', s.liveUrl, null),
      liveChecking: false,
      boundLive: true,
      offer: null,
    },
    effects: [],
  };
}

// ── 국면별 표 ──

function onLive(s: PlaybackSessionState, e: SessionEvent, now: number): Transition {
  switch (e.type) {
    case 'seek':
      return seekLive(s, e.targetMs, e.view);
    case 'live-button':
      return { state: s, effects: [{ kind: 'seek-live-edge' }] };
    case 'playing':
      return s.endingSince === null ? none(s) : none({ ...s, endingSince: null });
    case 'stream-poll':
      return onLiveStreamPoll(s, e.result, e.x);
    case 'endlist':
      return enterEnding(s, false, now);
    case 'end-reached':
    case 'live-closed':
      return enterEnding(s, true, now);
    case 'live-400':
      return none({ ...s, source: replace(s, 'live', s.liveUrl, null) });
    case 'roster-ended':
      return { state: s, effects: [{ kind: 'refresh' }] };
    case 'button':
      return e.which === 'new-broadcast' && s.foreignLive !== null
        ? close(s, 'left', s.foreignLive)
        : none(s);
    default:
      return none(s);
  }
}

function onLiveStreamPoll(
  s: PlaybackSessionState,
  result: RewindStatusResult,
  x: XAnswer | undefined,
): Transition {
  if (s.foreignLive !== null) return none(s);
  if (s.boundLive && x?.kind === 'known' && x.id !== '' && x.id !== s.roundId)
    return none({ ...s, foreignLive: x.id });
  const live = readyLive(result);
  if (live === null) return none(s);
  return none({
    ...s,
    entry: { url: live.url, firstPdtMs: live.firstPdtMs, headPdtMs: live.headPdtMs },
    roundRewindUrls: withUrl(s.roundRewindUrls, live.url),
  });
}

function onRewind(
  s: PlaybackSessionState,
  phase: Extract<SessionPhase, { kind: 'rewind' }>,
  e: SessionEvent,
  now: number,
): Transition {
  switch (e.type) {
    case 'seek':
      return seekInList(s, e.targetMs, e.view);
    case 'live-button':
      return liveFromAway(s, e.x, e.attempt);
    case 'stream-poll':
      return onRewindStreamPoll(s, e.result);
    case 'endlist':
      return phase.pendingNext !== null
        ? { state: s, effects: [{ kind: 'refresh', retryWhileUrl: s.source.url }] }
        : enterEnding(s, false, now);
    case 'end-reached': {
      const next = phase.pendingNext;
      if (next !== null && within(s.listEndMs, next.firstPdtMs, next.headPdtMs))
        return none({
          ...s,
          phase: { kind: 'rewind', pendingNext: null },
          source: replace(s, 'rewind', next.url, s.listEndMs),
          roundRewindUrls: withUrl(s.roundRewindUrls, next.url),
        });
      return enterEnding(s, true, now);
    }
    case 'roster-ended':
      return { state: s, effects: [{ kind: 'refresh' }] };
    default:
      return none(s);
  }
}

function onRewindStreamPoll(s: PlaybackSessionState, result: RewindStatusResult): Transition {
  const live = readyLive(result);
  if (live === null) return none(s);
  const range = { url: live.url, firstPdtMs: live.firstPdtMs, headPdtMs: live.headPdtMs };
  if (live.url === s.source.url)
    return none({ ...s, entry: range, observedEndMs: maxOrSelf(s.observedEndMs, live.headPdtMs) });
  if (within(s.lastAtMs, live.firstPdtMs, live.headPdtMs))
    return none({
      ...s,
      entry: range,
      phase: { kind: 'rewind', pendingNext: null },
      source: replace(s, 'rewind', live.url, s.lastAtMs),
      roundRewindUrls: withUrl(s.roundRewindUrls, live.url),
    });
  return none({ ...s, phase: { kind: 'rewind', pendingNext: range } });
}

type EndingPhase = Extract<SessionPhase, { kind: 'ending-wait' }>;

function onEnding(
  s: PlaybackSessionState,
  phase: EndingPhase,
  e: SessionEvent,
  now: number,
): Transition {
  switch (e.type) {
    case 'stream-poll':
      return phase.recordOnEnd ? none(s) : onEndingStreamPoll(s, phase, e.result, e.x);
    case 'round-poll':
      return phase.recordOnEnd ? none(s) : onEndingRoundPoll(s, phase, e.result, now);
    case 'end-reached':
    case 'live-closed':
      if (phase.atEnd) return none(s);
      if (phase.recordOnEnd) return close(s, 'recorded');
      return none({ ...s, phase: { ...phase, atEnd: true } });
    case 'tick':
      if (s.endingSince === null || now - s.endingSince < ENDING_WAIT_MS) return none(s);
      return roundFinal(s, phase);
    case 'roster-ended':
      return none({ ...s, rosterEnded: true });
    case 'button':
      return onEndingButton(s, e.which);
    case 'seek':
      return onEndingSeek(s, phase, e.targetMs, e.view);
    case 'live-button':
      return phase.atEnd ? none(s) : liveFromAway(s, e.x, e.attempt);
    default:
      return none(s);
  }
}

/** 이음 지점 — rewind 기원이면 atEnd ? E : P, live 기원이면 lastAtMs */
function joinPoint(s: PlaybackSessionState, phase: EndingPhase): number | null {
  if (phase.origin === 'rewind') return phase.atEnd ? s.listEndMs : s.lastAtMs;
  return s.lastAtMs;
}

function onEndingStreamPoll(
  s: PlaybackSessionState,
  phase: EndingPhase,
  result: RewindStatusResult,
  x: XAnswer | undefined,
): Transition {
  const live = readyLive(result);
  if (live === null || s.roundRewindUrls.has(live.url)) return none(s);
  const join = joinPoint(s, phase);
  if (within(join, live.firstPdtMs, live.headPdtMs)) {
    const resumed = {
      ...s,
      roundRewindUrls: withUrl(s.roundRewindUrls, live.url),
      endingSince: null,
      offer: null,
      rosterEnded: false,
      entry: { url: live.url, firstPdtMs: live.firstPdtMs, headPdtMs: live.headPdtMs },
    };
    if (phase.origin === 'rewind')
      return none({
        ...resumed,
        phase: { kind: 'rewind', pendingNext: null },
        source: replace(s, 'rewind', live.url, join),
      });
    return none({
      ...resumed,
      phase: { kind: 'live' },
      source: replace(s, 'live', s.liveUrl, null),
    });
  }
  let offer: Offer | null = null;
  if (x?.kind === 'known' && x.id === s.roundId) offer = { kind: 'go-live' };
  else if (x?.kind === 'known' && x.id !== '') offer = { kind: 'new-broadcast', goTo: x.id };
  return none({ ...s, offer });
}

function onEndingRoundPoll(
  s: PlaybackSessionState,
  phase: EndingPhase,
  result: RewindStatusResult,
  now: number,
): Transition {
  if (result.kind === 'none') {
    const waited = s.endingSince !== null && now - s.endingSince >= RESUME_WAIT_MS;
    return waited ? roundFinal(s, phase) : none(s);
  }
  if (result.kind !== 'ok') return none(s);
  const { status } = result;
  if (status.mode === 'ended') return roundFinal(s, phase);
  if (status.mode !== 'vod' || status.url === null) return none(s);
  if (status.firstPdtMs === null || status.headPdtMs === null) return none(s);
  // 세션이 본 구간 [watchedFromMs, observedEndMs] 과 겹쳐야 그 회차의 문서다(다른 회차 문서 방어 · R46)
  const seenFrom = s.watchedFromMs;
  const seenEnd = s.observedEndMs;
  if (seenFrom === null || seenEnd === null) return none(s);
  if (status.firstPdtMs > seenEnd || status.headPdtMs < seenFrom) return none(s);
  return none({
    ...s,
    phase: {
      kind: 'vod-offered',
      url: status.url,
      firstPdtMs: status.firstPdtMs,
      headPdtMs: status.headPdtMs,
      atEnd: phase.atEnd,
    },
    offer: null,
  });
}

/** 회차 종국(회차별 ended · 재접속 대기 뒤 404 · 20분 시한) — 끝에 닿았으면 녹화로, 아니면 끝까지 본 뒤 녹화로 */
function roundFinal(s: PlaybackSessionState, phase: EndingPhase): Transition {
  if (phase.atEnd) return close(s, 'recorded');
  return none({ ...s, phase: { ...phase, recordOnEnd: true } });
}

function onEndingButton(s: PlaybackSessionState, which: SessionButton): Transition {
  if (which === 'record') return close(s, 'recorded');
  if (which === 'go-live' && s.offer?.kind === 'go-live') return close(s, 'go-live');
  if (which === 'new-broadcast' && s.offer?.kind === 'new-broadcast')
    return close(s, 'left', s.offer.goTo);
  return none(s);
}

function onEndingSeek(
  s: PlaybackSessionState,
  phase: EndingPhase,
  t: number,
  view: SeekView,
): Transition {
  if (phase.origin === 'live') {
    if (phase.atEnd) return none(s);
    return seekLive(s, t, view);
  }
  const moved = seekInList(s, t, view);
  if (!phase.atEnd) return moved;
  return { state: { ...moved.state, phase: { ...phase, atEnd: false } }, effects: moved.effects };
}

type OfferedPhase = Extract<SessionPhase, { kind: 'vod-offered' }>;

function onVodOffered(
  s: PlaybackSessionState,
  phase: OfferedPhase,
  e: SessionEvent,
  origin: 'live' | 'rewind',
): Transition {
  switch (e.type) {
    case 'button':
      if (e.which === 'record') return close(s, 'recorded');
      return e.which === 'vod' ? openVod(s, phase) : none(s);
    case 'end-reached':
      return phase.atEnd ? none(s) : none({ ...s, phase: { ...phase, atEnd: true } });
    case 'seek':
      if (phase.atEnd) {
        if (origin === 'live') return none(s);
        const moved = seekInList(s, e.targetMs, e.view);
        return {
          state: { ...moved.state, phase: { ...phase, atEnd: false } },
          effects: moved.effects,
        };
      }
      return origin === 'live'
        ? seekLive(s, e.targetMs, e.view)
        : seekInList(s, e.targetMs, e.view);
    case 'live-button':
      return phase.atEnd ? none(s) : liveFromAway(s, e.x, e.attempt);
    default:
      return none(s);
  }
}

/** 「다시보기 보기」 — 끝에 닿았으면 처음부터, 아니면 지금 자리에서(하한이 이긴다 — 짧은 VOD 는 첫 조각 · R47) */
function openVod(s: PlaybackSessionState, phase: OfferedPhase): Transition {
  if (phase.atEnd || s.lastAtMs === null)
    return none({ ...s, phase: { kind: 'vod' }, source: replace(s, 'vod', phase.url, null) });
  const start = Math.max(phase.firstPdtMs, Math.min(s.lastAtMs, phase.headPdtMs - s.edgeMarginMs));
  const effects: SessionEffect[] =
    start === s.lastAtMs ? [] : [{ kind: 'notice', text: SESSION_NOTICES.vodStart }];
  return {
    state: { ...s, phase: { kind: 'vod' }, source: replace(s, 'vod', phase.url, start) },
    effects,
  };
}

function onVod(s: PlaybackSessionState, e: SessionEvent): Transition {
  return e.type === 'seek' ? seekInList(s, e.targetMs, e.view) : none(s);
}

// ── 아무 국면 ──

function onAnyPhase(s: PlaybackSessionState, e: SessionEvent): Transition | null {
  switch (e.type) {
    case 'list-loaded': {
      const own = e.url === s.source.url;
      const frozen = s.foreignLive !== null;
      return none({
        ...s,
        roundRewindUrls:
          own && s.source.kind === 'rewind' ? withUrl(s.roundRewindUrls, e.url) : s.roundRewindUrls,
        observedEndMs: frozen ? s.observedEndMs : maxOrSelf(s.observedEndMs, e.lastEndMs),
        listEndMs: own ? e.lastEndMs : s.listEndMs,
        edgeMarginMs:
          e.targetDurationS > 0 ? EDGE_MARGIN_TD * e.targetDurationS * 1000 : s.edgeMarginMs,
      });
    }
    case 'position':
      return none({
        ...s,
        lastAtMs: e.atMs,
        watchedFromMs: s.watchedFromMs === null ? e.atMs : Math.min(s.watchedFromMs, e.atMs),
      });
    case 'remount': {
      const startAtMs = s.source.kind === 'live' ? null : s.lastAtMs;
      return none({
        ...s,
        source: { ...s.source, startAtMs, generation: s.source.generation + 1 },
      });
    }
    case 'leave':
      return close(s, 'left');
    default:
      return null;
  }
}

/** 사건 하나를 먹인다. 닫힌 세션은 무엇도 바꾸지 않는다 */
export function transition(s: PlaybackSessionState, e: SessionEvent, now: number): Transition {
  if (s.closed !== null) return none(s);
  const any = onAnyPhase(s, e);
  if (any !== null) return any;
  const phase = s.phase;
  switch (phase.kind) {
    case 'live':
      return onLive(s, e, now);
    case 'rewind':
      return onRewind(s, phase, e, now);
    case 'ending-wait':
      return onEnding(s, phase, e, now);
    case 'vod-offered':
      return onVodOffered(s, phase, e, s.source.kind === 'live' ? 'live' : 'rewind');
    case 'vod':
      return onVod(s, e);
  }
}

// ── 화면이 그릴 것 ──

export interface SessionView {
  /** 마지막 프레임 위 덮개. checking = 재접속 대기 동안 「방송이 이어지는지 확인하고 있어요」 · preparing = 그 뒤 「다시보기 준비 중」 · vod-ready = 「다시보기 보기」 */
  cover: 'checking' | 'preparing' | 'vod-ready' | null;
  /** 덮개(끝에 닿음) 또는 띠(아직 보는 중)에 둘 버튼 */
  actions: SessionButton[];
  /** 「새 방송이 시작됐어요 — 보러 가기」 띠(세션에 묶인 live 가 다른 방송을 봄) */
  banner: { goTo: string } | null;
  /** 받을 목록이 없어 시크바를 숨긴다(live 기원 덮개) */
  seekHidden: boolean;
  /** LIVE 버튼을 숨긴다(끝난 방송의 VOD) */
  liveHidden: boolean;
  /** LIVE 를 눌러 지금 방송을 확인하는 중 */
  liveChecking: boolean;
  /** 「녹화 보기」를 기본 강조로 둔다(명부 ended) */
  recordEmphasis: boolean;
  /** 「다시보기 준비됨」 — 아직 보는 시청자에게 띠로 */
  vodReady: boolean;
}

function offerActions(s: PlaybackSessionState): SessionButton[] {
  if (s.offer?.kind === 'go-live') return ['record', 'go-live'];
  if (s.offer?.kind === 'new-broadcast') return ['record', 'new-broadcast'];
  return ['record'];
}

export function sessionView(s: PlaybackSessionState, now: number): SessionView {
  const base: SessionView = {
    cover: null,
    actions: [],
    banner: s.foreignLive === null ? null : { goTo: s.foreignLive },
    seekHidden: false,
    liveHidden: false,
    liveChecking: s.liveChecking,
    recordEmphasis: false,
    vodReady: false,
  };
  const phase = s.phase;
  if (phase.kind === 'ending-wait') {
    const waiting = s.endingSince !== null && now - s.endingSince < RESUME_WAIT_MS;
    return {
      ...base,
      cover: phase.atEnd ? (waiting ? 'checking' : 'preparing') : null,
      actions: offerActions(s),
      seekHidden: phase.atEnd && phase.origin === 'live',
      recordEmphasis: s.rosterEnded,
    };
  }
  if (phase.kind === 'vod-offered')
    return {
      ...base,
      cover: phase.atEnd ? 'vod-ready' : null,
      actions: ['record', 'vod'],
      seekHidden: phase.atEnd && s.source.kind === 'live',
      vodReady: !phase.atEnd,
    };
  if (phase.kind === 'vod') return { ...base, liveHidden: true };
  return base;
}
