import type { RewindStatusResult } from '@/api/rewindStatus';
import {
  endConfirm,
  loadedList,
  movedDoor,
  onHandoffConfirming,
  onLiveProbe,
  openHandoffFields,
  releaseBlock,
  requestProbe,
  withHandoffRules,
  withNextDoor,
  type HandoffFields,
} from './liveHandoff';
import { onEnding } from './endingWait';
import type { LiveProbeResult } from './liveWindowProbe';
import { EDGE_MARGIN_SEGMENTS } from './playbackEdge';
import {
  SESSION_NOTICES,
  close,
  liveFromAway,
  readyLive,
  replace,
  seekInList,
  seekLive,
  stateOnly,
  within,
  withUrl,
  type RewindRange,
} from './sessionShared';

export { ENDING_WAIT_MS, RESUME_WAIT_MS, SESSION_NOTICES } from './sessionShared';

// 재생 세션 상태기계(설계 5.3절 종결 판, POK-200 M5) — 라이브 · 되감기 · 끝남 대기 · 다시보기 사이의 전환을 정하는 순수 함수다.
// 사건 하나를 받아 다음 상태와 효과(재조회 · 안에서 시크 · 알림 · 닫기)를 돌려준다. 시각은 모두 실제 시각(epoch ms)이다.
//
// 읽는 규칙: 한 국면 안에서 위에서부터 처음 맞는 행 하나만 돈다. 어느 행에도 맞지 않는 (국면, 사건)은 무시다(국면 · 소스 그대로).
// 원칙 둘 — W3: 제어면 응답은 재생 중인 소스를 끊는 신호가 아니다. W4: 끝남 대기는 재생기 증거(ENDLIST · 끝 도달 · 라이브
// 닫힘)로만 들어간다. 🔴 ENDLIST 는 「이 목록에 조각이 더 붙지 않는다」이지 재생 완료가 아니다(RFC 8216 4.3.3.4) — atEnd 를
// 참으로 만드는 것은 끝 도달 사건뿐이다.

/** 창 하한 여유를 아직 모를 때(목록 전) 쓰는 값 — 4초 조각 기준(설계 5.3.1절) */
const DEFAULT_EDGE_MARGIN_MS = EDGE_MARGIN_SEGMENTS * 4_000;

export interface PlaybackSource {
  kind: 'live' | 'rewind' | 'vod';
  url: string;
  startAtMs: number | null;
  generation: number;
  /**
   * 시작이 잘리면(startIsTruncated) 틀지 말라는 계약(POK-200 c13). 꼬리 넘김(P1)과
   * 확인 중 다시 세움(H3 · H4)만 세우고 그 밖의 소스는 false 다
   */
  strictStart: boolean;
}

/** 스트림 폴링 응답 하나의 물음 순번과 수신 시각 — 받은 즉시 훅이 찍는다(늦은 응답 버림의 정본) */
export interface StreamAsk {
  seq: number;
  receivedAtMs: number;
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
export type Offer = { kind: 'go-live' } | { kind: 'new-broadcast'; goTo: string };

export interface PlaybackSessionState extends HandoffFields {
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
  /**
   * 최신 스트림 폴링이 되감기 진입을 열었나(mode:live ∧ ready). 진입 재료(entry)는 ready 깜박임에도 남기지만(W3 · R5), 창 밖 시크
   * 허용 · 시크바 넓힘 같은 되감기 노출은 이 값으로만 한다 — 「ready:false 동안 되감기 UI 를 노출하지 않는다」(계약3 7-2)
   */
  rewindOpen: boolean;
  /** 창 하한 여유(목록 맨 앞 조각 셋의 길이) */
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

export type StreamPollEvent = Extract<SessionEvent, { type: 'stream-poll' }>;

export type SessionEvent =
  | { type: 'seek'; targetMs: number; view: SeekView }
  /** attempt = 이번이 몇 번째 물음 뒤인가(0 = 누름) */
  | { type: 'live-button'; x: XAnswer; attempt: number }
  | { type: 'playing' }
  /** x = 이번 폴링과 함께 물은 지금 방송 번호(세션에 묶인 live · 끝남 대기 버튼 규칙). 없으면 모름 */
  | { type: 'stream-poll'; result: RewindStatusResult; x?: XAnswer; ask: StreamAsk }
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
      /** 맨 앞 조각 셋의 길이 합(ms) — 창 하한 여유(LoadedList.headSpanMs) */
      headSpanMs: number;
    }
  /** playing = 재생 중인가 · runMs = 지금 위치를 담은 played 범위의 길이(연속 시작 = atMs − runMs) */
  | { type: 'position'; atMs: number; playing: boolean; runMs: number }
  | { type: 'remount' }
  /** strictStart 재생기의 첫 목록 재검사가 시작 잘림을 봤다 */
  | { type: 'live-start-truncated' }
  /** 라이브 창 탐침의 결과 — seq 는 probe-live 효과의 순번 */
  | { type: 'live-probe'; seq: number; result: LiveProbeResult }
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
  | { kind: 'close'; reason: SessionClose; goTo?: string }
  /**
   * 라이브 목록 url 의 창 하한을 잰다(liveWindowProbe) — 결과는 같은 seq 의
   * live-probe 사건으로 돌아온다
   */
  | { kind: 'probe-live'; seq: number; url: string }
  /**
   * 넘김 확인이 깨져 되감기로 되돌렸다(H0 · H1a · H2 · H2t) — 사람이 멈춰 있었으면
   * 새 재생기를 멈춘 채 세운다. 멈췄는지는 재생기가 사람의 pause · play 로 안다
   */
  | { kind: 'keep-paused-on-swap' };

export interface Transition {
  state: PlaybackSessionState;
  effects: SessionEffect[];
}

export function openSession(init: {
  roundId: string;
  accessKey: string;
  liveUrl: string;
}): PlaybackSessionState {
  return {
    ...init,
    phase: { kind: 'live' },
    source: { kind: 'live', url: init.liveUrl, startAtMs: null, generation: 0, strictStart: false },
    lastAtMs: null,
    watchedFromMs: null,
    observedEndMs: null,
    roundRewindUrls: new Set(),
    endingSince: null,
    entry: null,
    rewindOpen: false,
    edgeMarginMs: DEFAULT_EDGE_MARGIN_MS,
    offer: null,
    liveChecking: false,
    boundLive: false,
    foreignLive: null,
    rosterEnded: false,
    closed: null,
    ...openHandoffFields(),
  };
}

/** 시크바 왼쪽 끝(되감기 범위 첫 시각). 최신 폴링이 되감기를 열었을 때만 — 닫혀 있으면 라이브 창만 보인다(계약3 7-2) */
export function rewindAxisLowerMs(session: PlaybackSessionState): number | null {
  return session.rewindOpen && session.entry !== null ? session.entry.firstPdtMs : null;
}

/** 켤 폴링 경로(설계 5.1절 표) */
export function pollTargets(session: PlaybackSessionState): { stream: boolean; round: boolean } {
  if (session.closed !== null) return { stream: false, round: false };
  switch (session.phase.kind) {
    case 'live':
    case 'rewind':
      return { stream: true, round: false };
    case 'ending-wait':
      return session.phase.recordOnEnd
        ? { stream: false, round: false }
        : { stream: true, round: true };
    default:
      return { stream: false, round: false };
  }
}

// ── 작은 도구 ──

function maxOrSelf(a: number | null, b: number): number {
  return a === null ? b : Math.max(a, b);
}

function enterEnding(session: PlaybackSessionState, atEnd: boolean, now: number): Transition {
  const origin = session.phase.kind === 'rewind' ? 'rewind' : 'live';
  const retry = session.source.kind === 'rewind' ? { retryWhileUrl: session.source.url } : {};
  return {
    state: {
      ...session,
      phase: { kind: 'ending-wait', origin, atEnd, recordOnEnd: false },
      endingSince: session.endingSince ?? now,
      offer: null,
    },
    effects: [{ kind: 'refresh', ...retry }],
  };
}

// ── 국면별 표 ──

function onLive(session: PlaybackSessionState, event: SessionEvent, now: number): Transition {
  const confirming = onHandoffConfirming(session, event, now);
  if (confirming !== null) return confirming;
  switch (event.type) {
    case 'seek':
      return endConfirm(seekLive(session, event.targetMs, event.view));
    case 'live-button': {
      const edge: SessionEffect = { kind: 'seek-live-edge' };
      return endConfirm({ state: session, effects: [edge] });
    }
    case 'playing':
      return session.endingSince === null
        ? stateOnly(session)
        : stateOnly({ ...session, endingSince: null });
    case 'stream-poll':
      return onLiveStreamPoll(session, event.result, event.x);
    case 'endlist':
      return enterEnding(session, false, now);
    case 'end-reached':
    case 'live-closed':
      return enterEnding(session, true, now);
    case 'live-400':
      return stateOnly({ ...session, source: replace(session, 'live', session.liveUrl, null) });
    case 'live-start-truncated': {
      // H7 — 확인 밖의 잘림(늦은 재생기)은 같은 라이브를 현행 clamp 시작으로 다시 세운다
      const generation = session.source.generation + 1;
      return stateOnly({
        ...session,
        source: { ...session.source, strictStart: false, generation },
      });
    }
    case 'roster-ended':
      return { state: session, effects: [{ kind: 'refresh' }] };
    case 'button':
      return event.which === 'new-broadcast' && session.foreignLive !== null
        ? close(session, 'left', session.foreignLive)
        : stateOnly(session);
    default:
      return stateOnly(session);
  }
}

function onLiveStreamPoll(
  session: PlaybackSessionState,
  result: RewindStatusResult,
  currentX: XAnswer | undefined,
): Transition {
  if (session.foreignLive !== null) return stateOnly(session);
  if (session.boundLive) {
    // 세션에 묶인 live 는 지금 방송 번호를 모르면 이번 폴링을 건너뛴다 — 같은 키의 새 방송 B 를 A 세션 재료로 받지 않게(R58)
    if (currentX?.kind !== 'known') return stateOnly(session);
    if (currentX.id !== '' && currentX.id !== session.roundId) {
      return stateOnly({ ...session, foreignLive: currentX.id });
    }
  }
  // 잠시 불가는 직전 상태를 둔다. 그 밖의 답은 되감기 노출을 정한다(진입 재료는 남긴다)
  if (result.kind === 'unavailable') return stateOnly(session);
  const live = readyLive(result);
  if (live === null) {
    return session.rewindOpen ? stateOnly({ ...session, rewindOpen: false }) : stateOnly(session);
  }
  return stateOnly({
    ...session,
    entry: { url: live.url, firstPdtMs: live.firstPdtMs, headPdtMs: live.headPdtMs },
    roundRewindUrls: withUrl(session.roundRewindUrls, live.url),
    rewindOpen: true,
  });
}

function onRewind(
  session: PlaybackSessionState,
  phase: Extract<SessionPhase, { kind: 'rewind' }>,
  event: SessionEvent,
  now: number,
): Transition {
  switch (event.type) {
    case 'seek':
      return seekInList(session, event.targetMs, event.view);
    case 'live-button':
      return liveFromAway(session, event.x, event.attempt);
    case 'stream-poll':
      return onRewindStreamPoll(session, event, now);
    case 'endlist':
      return phase.pendingNext !== null
        ? { state: session, effects: [{ kind: 'refresh', retryWhileUrl: session.source.url }] }
        : enterEnding(session, false, now);
    case 'end-reached': {
      const next = phase.pendingNext;
      const endMs = session.list?.endMs ?? null;
      if (next !== null && within(endMs, next.firstPdtMs, next.headPdtMs)) {
        return stateOnly({
          ...session,
          phase: { kind: 'rewind', pendingNext: null },
          source: replace(session, 'rewind', next.url, endMs),
          roundRewindUrls: withUrl(session.roundRewindUrls, next.url),
        });
      }
      return enterEnding(session, true, now);
    }
    case 'roster-ended':
      return { state: session, effects: [{ kind: 'refresh' }] };
    default:
      return stateOnly(session);
  }
}

/**
 * 문은 D 표를 먼저 탄다(R11 · R12 · R13 · R10 은 그 결과) — R9 만 응답 앞의
 * 문을 새 url 로 넘긴다. R11 은 갱신한 뒤 넘김 조건이면 탐침을 청한다
 */
function onRewindStreamPoll(
  before: PlaybackSessionState,
  event: StreamPollEvent,
  now: number,
): Transition {
  const session = withNextDoor(before, event);
  const live = readyLive(event.result);
  if (live === null) return stateOnly(session);
  const range = { url: live.url, firstPdtMs: live.firstPdtMs, headPdtMs: live.headPdtMs };
  if (live.url === session.source.url) {
    const seen = {
      ...session,
      entry: range,
      rewindOpen: true,
      observedEndMs: maxOrSelf(session.observedEndMs, live.headPdtMs),
    };
    return requestProbe(seen, now) ?? stateOnly(seen);
  }
  if (within(session.lastAtMs, live.firstPdtMs, live.headPdtMs)) {
    return stateOnly({
      ...session,
      entry: range,
      rewindOpen: true,
      phase: { kind: 'rewind', pendingNext: null },
      source: replace(session, 'rewind', live.url, session.lastAtMs),
      roundRewindUrls: withUrl(session.roundRewindUrls, live.url),
      handoffDoor: movedDoor(before, live.url, event.x, event.ask),
    });
  }
  return stateOnly({ ...session, phase: { kind: 'rewind', pendingNext: range } });
}

type OfferedPhase = Extract<SessionPhase, { kind: 'vod-offered' }>;

function onVodOffered(
  session: PlaybackSessionState,
  phase: OfferedPhase,
  event: SessionEvent,
  origin: 'live' | 'rewind',
): Transition {
  switch (event.type) {
    case 'button':
      if (event.which === 'record') return close(session, 'recorded');
      return event.which === 'vod' ? openVod(session, phase) : stateOnly(session);
    case 'end-reached':
      return phase.atEnd
        ? stateOnly(session)
        : stateOnly({ ...session, phase: { ...phase, atEnd: true } });
    case 'seek':
      if (phase.atEnd) {
        if (origin === 'live') return stateOnly(session);
        const moved = seekInList(session, event.targetMs, event.view);
        return {
          state: { ...moved.state, phase: { ...phase, atEnd: false } },
          effects: moved.effects,
        };
      }
      return origin === 'live'
        ? seekLive(session, event.targetMs, event.view)
        : seekInList(session, event.targetMs, event.view);
    case 'live-button':
      if (phase.atEnd) return stateOnly(session);
      // live 기원은 live 표의 LIVE 행 — 라이브 끝으로 시크 · 국면 그대로(종결 정정 live 표 · VOD 제안을 잃지 않는다)
      if (origin === 'live') return { state: session, effects: [{ kind: 'seek-live-edge' }] };
      return liveFromAway(session, event.x, event.attempt);
    default:
      return stateOnly(session);
  }
}

/** 「다시보기 보기」 — 끝에 닿았으면 처음부터, 아니면 지금 자리에서(하한이 이긴다 — 짧은 VOD 는 첫 조각 · R47) */
function openVod(session: PlaybackSessionState, phase: OfferedPhase): Transition {
  if (phase.atEnd || session.lastAtMs === null) {
    return stateOnly({
      ...session,
      phase: { kind: 'vod' },
      source: replace(session, 'vod', phase.url, null),
    });
  }
  const start = Math.max(
    phase.firstPdtMs,
    Math.min(session.lastAtMs, phase.headPdtMs - session.edgeMarginMs),
  );
  const effects: SessionEffect[] =
    start === session.lastAtMs ? [] : [{ kind: 'notice', text: SESSION_NOTICES.vodStart }];
  return {
    state: {
      ...session,
      phase: { kind: 'vod' },
      source: replace(session, 'vod', phase.url, start),
    },
    effects,
  };
}

function onVod(session: PlaybackSessionState, event: SessionEvent): Transition {
  return event.type === 'seek'
    ? seekInList(session, event.targetMs, event.view)
    : stateOnly(session);
}

// ── 아무 국면 ──

function onAnyPhase(
  session: PlaybackSessionState,
  event: SessionEvent,
  now: number,
): Transition | null {
  switch (event.type) {
    case 'list-loaded': {
      const own = event.url === session.source.url;
      const frozen = session.foreignLive !== null;
      return stateOnly({
        ...session,
        roundRewindUrls:
          own && session.source.kind === 'rewind'
            ? withUrl(session.roundRewindUrls, event.url)
            : session.roundRewindUrls,
        observedEndMs: frozen
          ? session.observedEndMs
          : maxOrSelf(session.observedEndMs, event.lastEndMs),
        list: own ? loadedList(session, event) : session.list,
        handoffBlock: releaseBlock(session, event.url, event.lastEndMs),
        edgeMarginMs: event.headSpanMs > 0 ? event.headSpanMs : session.edgeMarginMs,
      });
    }
    case 'position': {
      const moved = {
        ...session,
        lastAtMs: event.atMs,
        playing: event.playing,
        watchedFromMs:
          session.watchedFromMs === null ? event.atMs : Math.min(session.watchedFromMs, event.atMs),
      };
      // A1 — 확인 중인 live 는 H 행, rewind 는 R6(넘김 조건이면 탐침)
      const rewinding = moved.phase.kind === 'rewind';
      const probed = rewinding ? requestProbe(moved, now) : null;
      const confirming = onHandoffConfirming(moved, event, now);
      return confirming ?? probed ?? stateOnly(moved);
    }
    case 'live-probe':
      return onLiveProbe(session, event.seq, event.result, now);
    case 'remount': {
      const confirming = onHandoffConfirming(session, event, now);
      if (confirming !== null) return confirming;
      const startAtMs = session.source.kind === 'live' ? null : session.lastAtMs;
      return stateOnly({
        ...session,
        source: { ...session.source, startAtMs, generation: session.source.generation + 1 },
      });
    }
    case 'leave':
      return close(session, 'left');
    default:
      return null;
  }
}

/** 사건 하나를 먹인다. 닫힌 세션은 무엇도 바꾸지 않는다. 넘김 칸의 전처리 · 후처리(A0 · A3 · A6)가 국면 표를 감싼다 */
export function transition(
  session: PlaybackSessionState,
  event: SessionEvent,
  now: number,
): Transition {
  if (session.closed !== null) return stateOnly(session);
  return withHandoffRules(session, event, (ready) => phaseTransition(ready, event, now));
}

function phaseTransition(
  session: PlaybackSessionState,
  event: SessionEvent,
  now: number,
): Transition {
  const any = onAnyPhase(session, event, now);
  if (any !== null) return any;
  const phase = session.phase;
  switch (phase.kind) {
    case 'live':
      return onLive(session, event, now);
    case 'rewind':
      return onRewind(session, phase, event, now);
    case 'ending-wait':
      return onEnding(session, phase, event, now);
    case 'vod-offered':
      return onVodOffered(
        session,
        phase,
        event,
        session.source.kind === 'live' ? 'live' : 'rewind',
      );
    case 'vod':
      return onVod(session, event);
  }
}
