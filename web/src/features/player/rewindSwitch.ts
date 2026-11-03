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
  seenPosition,
  withHandoffRules,
  withNextDoor,
  withPositionEpoch,
  type HandoffFields,
} from './liveHandoff';
import { onEnding } from './endingWait';
import type { LiveProbeResult } from './liveWindowProbe';
import { EDGE_MARGIN_SEGMENTS } from './playbackEdge';
import {
  SESSION_NOTICES,
  close,
  isThisRound,
  liveFromAway,
  openVodHere,
  readyLive,
  replace,
  restartAtMs,
  resumeAtMs,
  seekInList,
  seekLive,
  stateOnly,
  vodStartAt,
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

/**
 * 되감기 조각 404 뒤 보던 목록(같은 url)을 다시 받아 새로 세운 재생기가 아직 화면을 붙이지 못했다(POK-528
 * 계획 3-1). stage = 첫 목록 전(list) · 첫 목록은 받았고 화면을 기다림(paint). noticed = 이 다시 받기에서
 * 「하한으로 옮겼다」 를 이미 알렸다 — 리마운트를 끼어도 남고 화면이 붙으면 다시 받기와 함께 사라진다
 */
export interface RelistState {
  stage: 'list' | 'paint';
  noticed: boolean;
}

/** 재생 세션의 국면 — 명부 상태(broadcastStatus)와 따로 움직인다 */
export type SessionPhase =
  | { kind: 'live' }
  /**
   * pendingNext = 지금 위치를 덮지 않는 새 url 과 그 범위(최신 폴링 값). 끝 도달 때 E 를 덮으면 그 E 에서 잇는다 ·
   * relist = 404 뒤 보던 목록을 다시 받은 재생기가 화면을 기다리는 중(RelistState). 없으면 평소 되감기이고, 국면을
   * 새로 세우면 버려진다. 같은 재생기의 pendingNext 만 바꾸는 폴링은 국면을 펼쳐 relist 를 남긴다
   */
  | { kind: 'rewind'; pendingNext: RewindRange | null; relist?: RelistState }
  /**
   * 끝남 대기. origin = 들어온 국면 · atEnd = 실제 재생이 끝에 닿았다 · recordOnEnd = 회차 종국 또는 20분 시한이 섰다 ·
   * blocked = 되감기 조각 404 로 이 목록을 끝까지 재생할 수 없다(POK-528 — 회차별 조회의 vod · 종국 · 20분과 사람 단추로만
   * 풀린다) · finalCheck = 녹화로 정한 뒤 404 를 받아 회차별 조회를 마지막으로 한 번 하는 중(POK-528 W6 — 답이 vod 면 같은
   * 자리 다시보기, 그 밖이면 녹화). 둘 다 없으면 거짓이다
   */
  | {
      kind: 'ending-wait';
      origin: 'live' | 'rewind';
      atEnd: boolean;
      recordOnEnd: boolean;
      blocked?: boolean;
      finalCheck?: boolean;
    }
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
  /**
   * 마지막으로 본 자리(P) — 이어 볼 자리 · 계승 판정 · 이음 지점의 재료. 소스를 바꿔도 그대로라 첫 위치
   * 전에는 앞 소스의 자리다 — 지금 소스의 것인지는 positionGeneration 으로 가른다(sessionShared 의 confirmedAtMs)
   */
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
      /**
       * 되감기 재생기의 첫 목록에서 시작을 창 하한으로 잘랐다(playbackEdge.startCut — POK-528). 첫 목록이
       * 아니거나 시작 요청이 없거나 시각 표시가 없으면 칸이 없다
       */
      startFloorCut?: boolean;
    }
  /**
   * playing = 재생 중인가 · runMs = 지금 위치를 담은 played 범위의 길이(연속 시작 = atMs − runMs) · epoch = 재생기가 이 자리를
   * 잰 때의 위치 시대(positionEpoch)
   */
  | { type: 'position'; atMs: number; playing: boolean; runMs: number; epoch: number }
  | { type: 'remount' }
  /** strictStart 재생기의 첫 목록 재검사가 시작 잘림을 봤다 */
  | { type: 'live-start-truncated' }
  /** 라이브 창 탐침의 결과 — seq 는 probe-live 효과의 순번 */
  | { type: 'live-probe'; seq: number; result: LiveProbeResult }
  /** 되감기 재생기가 /dvr 미디어 조각 404 를 받아 받기를 멈췄다. generation = 그 재생기의 소스 세대(POK-528) */
  | { type: 'dvr-segment-gone'; generation: number }
  | { type: 'leave' };

export type SessionEffect =
  /** 켜진 경로를 곧바로 다시 묻는다. retryWhileUrl = 스트림 경로 답이 이 url 이면 2초 × 3(R16) */
  | { kind: 'refresh'; retryWhileUrl?: string }
  | { kind: 'seek-within'; atMs: number }
  | { kind: 'seek-live-edge' }
  | { kind: 'notice'; text: string }
  /** 앞서 낸 안내 text 가 아직 떠 있으면 거둔다 — 넘김을 되돌린 뒤 넘김 안내가 남지 않게 */
  | { kind: 'withdraw-notice'; text: string }
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
  | { kind: 'keep-paused-on-swap' }
  /** 지금 프레임에서 멈춘다 — 사람의 멈춤으로 적지 않는다(POK-528 · 막힘) */
  | { kind: 'hold-frame' }
  /**
   * 재생기가 들고 있는 사람 멈춤 자리 기록을 버린다 — 404 뒤 목록을 다시 받은 새 재생기가
   * 옛 목록 축의 멈춘 자리로 다시 옮겨 달라고 하지 않게(POK-528 목록 다시 받기)
   */
  | { kind: 'drop-pause-mark' };

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
      // 녹화로 정했으면 끝 도달만 기다린다 — 마지막 확인 중이면 회차별 조회 하나만 켠다(POK-528 W6)
      if (!session.phase.recordOnEnd) return { stream: true, round: true };
      return { stream: false, round: session.phase.finalCheck === true };
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

/**
 * 막힘 — 목록을 다시 받은 되감기가 화면을 붙이기 전에 또 404 를 받으면 막힌 끝남 대기로 간다
 * (POK-528 계획 3-2 · 3-3 (5)). 끝남 대기 진입과 같고, 막힘을 세우고 지금 프레임에서 멈춘다
 */
function enterBlocked(session: PlaybackSessionState, atEnd: boolean, now: number): Transition {
  const ending = enterEnding(session, atEnd, now);
  const phase: SessionPhase = {
    kind: 'ending-wait',
    origin: 'rewind',
    atEnd,
    recordOnEnd: false,
    blocked: true,
  };
  return {
    state: { ...ending.state, phase },
    effects: [...ending.effects, { kind: 'hold-frame' }],
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
      return onRewindStreamPoll(session, phase, event, now);
    case 'dvr-segment-gone':
      return onRewindSegmentGone(session, phase, now);
    case 'endlist':
      // 다시 받은 목록의 ENDLIST 도 같다 — pendingNext 가 있으면 relist 를 남긴 채 refresh(POK-528 계획 3-3 (3))
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

type RewindPhase = Extract<SessionPhase, { kind: 'rewind' }>;

/**
 * 문은 D 표를 먼저 탄다(R11 · R12 · R13 · R10 은 그 결과) — R9 만 새 url 의 문을
 * 세운다. R11 은 갱신한 뒤 넘김 조건이면 탐침을 청한다. R9 · R10 은 X 가 이 회차일
 * 때만이다 — 설계 r9 R9 행은 X 모름 · 다른 방송에도 갈아 끼우고 문만 달리했지만,
 * kty 결정(K1 확장)으로 갈아 끼우지도 보류하지도 않는다. 새 url 의 범위는 이어 볼
 * 자리(resumeAtMs)로 본다 — 첫 위치 전 · 시크 중의 lastAtMs 는 앞 소스 · 시크 전
 * 자리다(POK-528 Q1). 갈아 끼우면 국면을 새로 세워 relist 를 버리고(새 재생기 —
 * 그 url 의 다시 받기는 따로 하나), 보류(R10)는 국면을 펼쳐 relist 를 남긴다(R2).
 * 갈아 끼운 재생기의 하한 잘림을 알리지 않는 것은 기존 R9 교체와 같은 한계다(J5)
 */
function onRewindStreamPoll(
  before: PlaybackSessionState,
  phase: RewindPhase,
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
  // 이 회차에서 이미 본 url 은 다시 잇지 않는다 — 끝남 대기 이음(endingWait)과 같은 칸 · 같은 규칙(P46)
  if (session.roundRewindUrls.has(live.url)) return stateOnly(session);
  // 새 url 은 같은 회차일 때만 갈아 끼우거나 보류한다 — 다른 회차 · X 모름은 그대로 둔다(문은 D 표가 비웠다)
  if (!isThisRound(session, event.x)) return stateOnly(session);
  const resume = resumeAtMs(session);
  if (within(resume, live.firstPdtMs, live.headPdtMs)) {
    return stateOnly({
      ...session,
      entry: range,
      rewindOpen: true,
      phase: { kind: 'rewind', pendingNext: null },
      source: replace(session, 'rewind', live.url, resume),
      roundRewindUrls: withUrl(session.roundRewindUrls, live.url),
      handoffDoor: movedDoor(live.url, event.ask),
    });
  }
  return stateOnly({ ...session, phase: { ...phase, pendingNext: range } });
}

/**
 * 되감기 조각 404(POK-528 계획 3-2) — 다시 받는 중이 아니면 보던 목록(같은 url)을 다시 받는다.
 * 다시 받은 재생기가 화면을 붙이기 전에 또 404 면 막힌 끝남 대기다(되풀이 상한 — 3-3 (5))
 */
function onRewindSegmentGone(
  session: PlaybackSessionState,
  phase: RewindPhase,
  now: number,
): Transition {
  if (phase.relist !== undefined) return enterBlocked(session, false, now);
  return relistSameList(session, phase);
}

/**
 * 목록 다시 받기 — 같은 url 을 세대 + 1 로 다시 세운다. 재생기가 새 목록 창 하한 아래를 자르고,
 * 시작은 이어 볼 자리(모르면 목록 하한에서)다. pendingNext · entry 는 url 이 같아 그대로 둔다.
 * 멈춤 자리 기록은 옛 목록 축이라 버리게 한다(첫 목록 판정이 한 번 더 버린다 — P37)
 */
function relistSameList(session: PlaybackSessionState, phase: RewindPhase): Transition {
  return {
    state: {
      ...session,
      phase: { ...phase, relist: { stage: 'list', noticed: false } },
      source: replace(session, 'rewind', session.source.url, restartAtMs(session)),
    },
    effects: [{ kind: 'drop-pause-mark' }],
  };
}

/**
 * 다시 받은 첫 목록의 판정(POK-528 계획 3-3 (2)) — 지금 소스 url 의 목록이 첫 목록 전(list)에 오면
 * 한 번 본다. 재생기가 실제로 창 하한으로 잘랐고(startFloorCut) 이 다시 받기에서 아직 알리지 않았으면
 * 알린다(같은 relist 안 재알림 억제). 같은 호출 스택에서 새 재생기의 play 사건보다 먼저 멈춤 자리 기록을
 * 한 번 더 버리게 한다 — 404 뒤 옛 재생기 정리 전의 사람 멈춤이 옛 축의 자리를 다시 적었을 수 있다(P37).
 * 판정할 목록이 아니면 null
 */
function relistNotice(
  session: PlaybackSessionState,
  event: Extract<SessionEvent, { type: 'list-loaded' }>,
): Transition | null {
  const { phase } = session;
  if (phase.kind !== 'rewind' || phase.relist?.stage !== 'list') return null;
  if (event.url !== session.source.url) return null;
  const cut = event.startFloorCut === true;
  const relist: RelistState = { stage: 'paint', noticed: phase.relist.noticed || cut };
  const effects: SessionEffect[] = [{ kind: 'drop-pause-mark' }];
  if (cut && !phase.relist.noticed) effects.push({ kind: 'notice', text: SESSION_NOTICES.oldest });
  return { state: { ...session, phase: { ...phase, relist } }, effects };
}

/**
 * 화면 붙음 — 지금 시대의 위치 사건은 다시 받은 재생기가 그 자리에 화면을 붙였다는 뜻이다(재생기는
 * 재생 위치에 영상이 있을 때만 위치를 낸다 — useHlsPlayback 의 HAVE_CURRENT_DATA 가드). 다시 받기를
 * 끝낸다 — 그 뒤의 404 는 새 사건이라 다시 받고 다시 판정한다
 */
function settleRelist(phase: SessionPhase): SessionPhase {
  if (phase.kind !== 'rewind' || phase.relist === undefined) return phase;
  const { relist: _painted, ...rest } = phase;
  return rest;
}

/**
 * 리마운트로 선 새 재생기는 첫 목록을 다시 판정한다 — 단계만 첫 목록 전(list)으로 되돌리고
 * noticed 는 남긴다(같은 relist 안 재알림 억제 · 새 잘림은 알림 — P31 · P47)
 */
function remountRelist(phase: SessionPhase): SessionPhase {
  if (phase.kind !== 'rewind' || phase.relist?.stage !== 'paint') return phase;
  return { ...phase, relist: { ...phase.relist, stage: 'list' } };
}

/**
 * 리마운트의 시작 — 되감기는 이어 볼 자리에서 다시 선다(첫 위치 전의 lastAtMs 는 앞 소스 자리이거나
 * 비었다 — POK-528 R3 · 모르면 목록 하한에서). 다시보기도 이어 볼 자리다 — 첫 위치 전이면 청한 자리 ·
 * 시크 목표, 모르면 처음부터(null — c7 리뷰 V7-1). 라이브는 라이브 끝(지금 동작)
 */
function remountStartAtMs(session: PlaybackSessionState): number | null {
  switch (session.source.kind) {
    case 'rewind':
      return restartAtMs(session);
    case 'vod':
      return resumeAtMs(session);
    case 'live':
      return null;
  }
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
    case 'dvr-segment-gone':
      // W4 — 다시보기가 이미 있으니 같은 자리 다시보기로 곧바로 옮긴다
      return openVodHere(session, phase);
    case 'live-button':
      if (phase.atEnd) return stateOnly(session);
      // live 기원은 live 표의 LIVE 행 — 라이브 끝으로 시크 · 국면 그대로(종결 정정 live 표 · VOD 제안을 잃지 않는다)
      if (origin === 'live') return { state: session, effects: [{ kind: 'seek-live-edge' }] };
      return liveFromAway(session, event.x, event.attempt);
    default:
      return stateOnly(session);
  }
}

/**
 * 「다시보기 보기」 — 끝에 닿았거나 자리를 모르면 처음부터, 아니면 이어 볼 자리에서(하한이 이긴다 —
 * 짧은 VOD 는 첫 조각 · R47)
 */
function openVod(session: PlaybackSessionState, phase: OfferedPhase): Transition {
  const atMs = resumeAtMs(session);
  if (phase.atEnd || atMs === null) {
    return stateOnly({
      ...session,
      phase: { kind: 'vod' },
      source: replace(session, 'vod', phase.url, null),
    });
  }
  const start = vodStartAt(session, atMs, phase);
  const effects: SessionEffect[] =
    start === atMs ? [] : [{ kind: 'notice', text: SESSION_NOTICES.vodStart }];
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
      const seen: PlaybackSessionState = {
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
      };
      return relistNotice(seen, event) ?? stateOnly(seen);
    }
    case 'position': {
      // 다른 시대(재생기를 바꾸거나 옮기기 전)에 잰 위치다 — 커밋된 위치가 효과로 늦게 올라왔다. 세션을 다시 열면 시대가
      // 0 부터 다시 서므로, 닫힌 앞 세션의 값은 이 대조로 가르지 못한다
      if (event.epoch !== session.positionEpoch) return stateOnly(session);
      const moved = { ...seenPosition(session, event), phase: settleRelist(session.phase) };
      // A1 — 확인 중인 live 는 H 행, rewind 는 R6(넘김 조건이면 탐침)
      const rewinding = moved.phase.kind === 'rewind';
      const probed = rewinding ? requestProbe(moved, now) : null;
      const confirming = onHandoffConfirming(moved, event, now);
      return confirming ?? probed ?? stateOnly(moved);
    }
    case 'live-probe':
      return onLiveProbe(session, event.seq, event.result, now);
    case 'dvr-segment-gone':
      // 지금 세대의 되감기 재생기가 낸 404 만 국면 표로 넘긴다 — 교체를 커밋한 뒤 정리 전인 옛 재생기의 늦은 404 는
      // 버린다(위치 시대 대조와 같은 꼴)
      if (event.generation !== session.source.generation || session.source.kind !== 'rewind') {
        return stateOnly(session);
      }
      return null;
    case 'remount': {
      const confirming = onHandoffConfirming(session, event, now);
      if (confirming !== null) return confirming;
      return stateOnly({
        ...session,
        phase: remountRelist(session.phase),
        source: {
          ...session.source,
          startAtMs: remountStartAtMs(session),
          generation: session.source.generation + 1,
        },
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
  const next = withHandoffRules(session, event, (ready) => phaseTransition(ready, event, now));
  return withPositionEpoch(session, next);
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
