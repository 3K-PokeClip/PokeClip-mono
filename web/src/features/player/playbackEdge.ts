import { mediaTimeAt, programDateAt, type DatedFragment } from './dvrWindow';
import { REWIND_UI_MAX_SECONDS } from './playerMath';
import { nextStreamAsk } from './liveHandoff';
import type {
  PlaybackSessionState,
  PlaybackSource,
  SeekView,
  StreamPollEvent,
} from './rewindSwitch';
import { deniesRound } from './sessionShared';

// 재생기가 로드한 목록의 가장자리 판정(설계 5.3.1 · 5.4절, POK-200 M5). 순수 함수라 jsdom 없이 시험한다.
// hls.js synchronizeToLiveEdge 는 ENDLIST 없는 목록이 갱신될 때마다 돌아, 재생점이 창 밖이면 목록 끝으로 옮긴다. 그래서 창
// 하한에 여유를 둔다. 라이브는 맨 앞 조각 셋이다: 시동은 목록 받기 · 조각 받기 · 디코더 시동이고 창은 조각 하나마다 밀리므로,
// 1 조각 여유로는 시동 한 번에 창 밖이 될 수 있다(라이브 시작 하한 · POK-200 c13). 방송 중 되감기는 첫 내용 조각 하나다 —
// 재생점이 첫 조각 앞이 되면 hls.js 보다 먼저 창 하한 지키기(rewindFloor)가 옮긴다(POK-573). 끝난 목록(ENDLIST) · 다시보기는
// 창이 밀리지 않아 여유가 없다. 여유는 TD 가 아니라 그 조각들의 실제 길이로 센다 — gohlslib 은 TD 를 지금까지 본 조각 최대값으로 올리기만
// 해(첫 조각 5.9초면 방송 끝까지 TD 6) 실제 조각(4초)보다 클 수 있다.

/** 라이브 창 하한 여유 = 목록 맨 앞 조각 이 개수의 길이 합(라이브 시작 하한 · headSpanMs) */
export const EDGE_MARGIN_SEGMENTS = 3;
/** 방송 중 되감기 창 하한 여유 = 첫 내용 조각부터 이 개수의 길이 합(rewindHeadSpanMs · 창 하한 지키기) */
export const REWIND_MARGIN_SEGMENTS = 1;
/** 끝 정체 판정의 「목록이 이 값 × TD 넘게 자라지 않음」 — 목록 갱신 주기라 TD 로 센다 */
const END_STALL_TD = 3;
/** 끝 정체 판정의 「끝 1초 안」 */
const END_SLACK_MS = 1_000;

/** 로드한 목록의 실제 시각 범위 */
export interface LoadedList {
  /** 첫 조각의 시각(epoch ms) */
  firstMs: number;
  /** 마지막 조각의 끝(epoch ms) — E */
  lastEndMs: number;
  /**
   * 마지막 조각의 시작(epoch ms). 만드는 쪽 loadedList 는 늘 채운다 — 선택인 까닭은 다른 소비자 리터럴을 건드리지 않으려는
   * 것이다
   */
  lastStartMs?: number;
  targetDurationS: number;
  /** 맨 앞 조각 EDGE_MARGIN_SEGMENTS 개(모자라면 있는 만큼)의 EXTINF 합(ms) — 라이브 창 하한 여유 */
  headSpanMs: number;
  /**
   * 첫 내용 조각(맨 앞 EXT-X-GAP 을 뺀 첫 조각)의 시각(epoch ms) — 되감기 · 다시보기 하한의 기준. 모두 GAP 이면 firstMs.
   * 만드는 쪽 loadedList 는 늘 채운다 — 선택인 까닭은 lastStartMs 와 같다. 없으면 firstMs 로 읽는다
   */
  contentFirstMs?: number;
  /**
   * 첫 내용 조각부터 REWIND_MARGIN_SEGMENTS 개(모자라면 있는 만큼)의 EXTINF 합(ms) — 방송 중 되감기 창 하한 여유. 모두 GAP
   * 이면 그 개수 × TD(rewindFloor 와 같은 셈). 만드는 쪽 loadedList 는 늘 채운다 — 없으면 headSpanMs 로 읽는다
   */
  rewindHeadSpanMs?: number;
}

/** 조각 길이 합(ms) */
function spanMs(fragments: readonly DatedFragment[]): number {
  return fragments.reduce((sum, frag) => sum + frag.duration * 1000, 0);
}

export function loadedList(
  fragments: readonly DatedFragment[],
  targetDurationS: number,
): LoadedList | null {
  const first = fragments[0];
  const last = fragments[fragments.length - 1];
  if (first === undefined || last === undefined) return null;
  if (first.programDateTime === null || last.programDateTime === null) return null;
  const content = liveContent(fragments);
  const contentFirst = content[0];
  return {
    firstMs: first.programDateTime,
    lastEndMs: last.programDateTime + last.duration * 1000,
    lastStartMs: last.programDateTime,
    targetDurationS,
    headSpanMs: spanMs(fragments.slice(0, EDGE_MARGIN_SEGMENTS)),
    contentFirstMs: contentFirst?.programDateTime ?? first.programDateTime,
    rewindHeadSpanMs:
      contentFirst === undefined
        ? REWIND_MARGIN_SEGMENTS * targetDurationS * 1000
        : spanMs(content.slice(0, REWIND_MARGIN_SEGMENTS)),
  };
}

/**
 * 창 하한 여유(ms). 라이브는 맨 앞 조각 셋 · 방송 중(live — hls.js details.live) 되감기는 첫 내용 조각 하나다. 끝난 되감기
 * 목록(ENDLIST)과 vod 는 창이 밀리지 않아 0 이다
 */
export function edgeMarginMs(
  list: LoadedList,
  kind: PlaybackSource['kind'],
  live: boolean,
): number {
  if (kind === 'live') return list.headSpanMs;
  if (kind === 'vod' || !live) return 0;
  return list.rewindHeadSpanMs ?? list.headSpanMs;
}

/**
 * 창 하한(epoch ms) = 기준 조각 시작 + 여유, 목록 끝 넘어로는 두지 않는다. 기준은 라이브가 첫 조각, 되감기 · 다시보기가
 * 첫 내용 조각이다. 끝에 걸리는 것은 방송 중 되감기 목록이 모두 GAP 이고 합이 TD 보다 짧을 때뿐이다(여유가 1 × TD) — 하한을
 * 끝 뒤에 두면 세션이 하한으로 옮길 때 목록 밖으로 시크한다. startCut · 창 하한 지키기(rewindFloor)도 같은 자리에서 자른다
 */
export function windowLowerMs(
  list: LoadedList,
  kind: PlaybackSource['kind'],
  live: boolean,
): number {
  const baseMs = kind === 'live' ? list.firstMs : (list.contentFirstMs ?? list.firstMs);
  return Math.min(list.lastEndMs, baseMs + edgeMarginMs(list, kind, live));
}

/** 첫 목록의 시작 — position = startLoad 할 영상 초 · floorCut = 창 하한으로 잘랐다 */
export interface StartCut {
  position: number;
  floorCut: boolean;
}

/**
 * 첫 목록에서 startLoad 할 영상 초와 그것이 창 하한으로 자른 자리인가(POK-528 — 세션의 하한 알림 판정 재료).
 * 실제 시각 startAtMs 를 창 하한 이상 · 목록 끝 이하로 자른다(5.4절 시작 위치). 영상 시각으로 견주므로 PDT 틈 ·
 * 겹침에서도 재생기가 실제로 자른 것과 같다. 하한과 같으면 잘리지 않았다. 시각 표시가 없으면 null — 부르는 쪽이
 * 기본 시작으로 둔다. 첫 목록은 아직 해체한 조각이 없어 판 2 조각도 시작 · 길이가 hls.js 첫 값과 같다
 * (datedFragments 의 해체 전 갈래)
 */
export function startCut(
  fragments: readonly DatedFragment[],
  startAtMs: number,
  kind: PlaybackSource['kind'],
  targetDurationS: number,
  live: boolean,
): StartCut | null {
  const list = loadedList(fragments, targetDurationS);
  const first = fragments[0];
  const last = fragments[fragments.length - 1];
  if (list === null || first === undefined || last === undefined) return null;
  const target = mediaTimeAt(fragments, startAtMs);
  if (target === null) return null;
  const base = kind === 'live' ? first : (liveContent(fragments)[0] ?? first);
  const end = last.start + last.duration;
  // windowLowerMs 와 같은 셈이다 — 목록 끝 넘어로는 두지 않는다
  const lower = Math.min(end, base.start + edgeMarginMs(list, kind, live) / 1000);
  return { position: Math.min(end, Math.max(lower, target)), floorCut: target < lower };
}

/** 첫 목록에서 startLoad 할 영상 초(startCut 의 자리). 시각 표시가 없으면 null */
export function startLoadPosition(
  fragments: readonly DatedFragment[],
  startAtMs: number,
  kind: PlaybackSource['kind'],
  targetDurationS: number,
  live: boolean,
): number | null {
  return startCut(fragments, startAtMs, kind, targetDurationS, live)?.position ?? null;
}

/**
 * 라이브 시작 하한(epoch ms, POK-200 c13 · S10) — 라이브 목록에서 시작해도 창 밖으로 밀리지 않는 가장 이른 시각이다. 창
 * 하한(windowLowerMs)과 같은 식을 맨 앞 GAP 을 뺀 조각에 건다(liveContent). 탐침과 첫 목록 재검사가 이 하한 하나를
 * 쓴다. 시각 표시가 없거나 모두 GAP 이면 null
 */
export function liveStartFloorMs(
  fragments: readonly DatedFragment[],
  targetDurationS: number,
): number | null {
  const list = loadedList(liveContent(fragments), targetDurationS);
  return list === null ? null : windowLowerMs(list, 'live', true);
}

/**
 * 맨 앞 EXT-X-GAP 조각을 뺀 조각 — 재송출 직후 MediaMTX 라이브 목록은 앞이 GAP 으로 차 있어(M5b 2026-10-30 · 약
 * 36초), 그것을 세면 하한이 내용 앞에 선다. 가운데 GAP 은 창이 밀리는 조각이라 그대로 둔다
 */
function liveContent(fragments: readonly DatedFragment[]): readonly DatedFragment[] {
  const first = fragments.findIndex((frag) => !frag.gap);
  if (first < 0) return [];
  return first === 0 ? fragments : fragments.slice(first);
}

/**
 * 시작 위치 startAtMs 가 창 하한보다 앞이라 startLoadPosition 이 하한으로 자를 것인가 — 못 본 녹화가 잘린다. 하한과 같으면
 * 잘리지 않는다. live 는 라이브 시작 하한(liveStartFloorMs — 맨 앞 GAP 을 뺀다)으로 잰다. 시각 표시가 없거나 모두 GAP
 * 이면 판정할 수 없어 false 다(startLoadPosition 도 null 이거나 기본 시작으로 둔다)
 */
export function startIsTruncated(
  fragments: readonly DatedFragment[],
  startAtMs: number,
  kind: PlaybackSource['kind'],
  targetDurationS: number,
): boolean {
  if (kind === 'live') {
    const floor = liveStartFloorMs(fragments, targetDurationS);
    return floor !== null && startAtMs < floor;
  }
  // 비라이브 갈래는 호출자가 없다 — 방송 중 하한으로 잰다
  const list = loadedList(fragments, targetDurationS);
  return list !== null && startAtMs < windowLowerMs(list, kind, true);
}

/**
 * 목록 끝 정체(D9) — 재생 위치가 E 1초 안 ∧ waiting ∧ 목록이 3 × TD 넘게 자라지 않음. `ended` 이벤트와 함께 「끝 도달」이다.
 * ENDLIST 없는 목록의 회차 경계를 이것이 맡는다
 */
export function endStalled(input: {
  list: LoadedList;
  atMs: number;
  waiting: boolean;
  /** 목록이 마지막으로 자란 뒤 흐른 ms */
  sinceGrowMs: number;
}): boolean {
  const { list, atMs, waiting, sinceGrowMs } = input;
  return (
    waiting &&
    atMs >= list.lastEndMs - END_SLACK_MS &&
    sinceGrowMs > END_STALL_TD * list.targetDurationS * 1000
  );
}

export interface SessionAxisInput {
  kind: PlaybackSource['kind'];
  /** 목록이 아직 자란다(hls.js details.live — ENDLIST 가 없다). 방송 중 되감기의 창 하한 여유를 가른다 */
  live: boolean;
  /**
   * 방송이 끝났다는 근거가 있다(recordingEndedOf). 되감기의 끝을 벽시계가 아니라 로드한 목록 끝으로 잰다. 없으면 거짓(방송
   * 중)이다. ENDLIST(live)로 가르지 않는다 — 방송 중 재접속을 기다리는 앞 목록에도 ENDLIST 가 붙고, 404 로 막힌 목록은
   * ENDLIST 없이 멈춘다
   */
  recordingEnded?: boolean;
  fragments: readonly DatedFragment[];
  targetDurationS: number;
  /** 재생 위치(영상 초) */
  currentTime: number;
  /** 라이브 재생기가 서는 라이브 끝(영상 초). 라이브 재생기만 쓴다 — 모르면 null */
  liveEdgeTime: number | null;
  nowMs: number;
  /** 라이브 재생기에서 마지막으로 잰 「지금 − 라이브 끝」 */
  lagMs: number;
  /** 라이브 재생기의 되감기 범위 첫 시각 — 시크바 왼쪽 끝을 라이브 창 밖까지 넓힌다. 모르면 로드한 목록 첫 시각 */
  axisLowerMs: number | null;
}

export interface SessionAxis {
  list: LoadedList;
  /** 재생 위치의 실제 시각. 시각 표시가 없으면 null */
  atMs: number | null;
  /**
   * 시크바 오른쪽 끝의 실제 시각 — 라이브 끝(방송 중 되감기는 「지금 − 라이브 지연」, 방송이 끝난 쪽 국면의 되감기와 vod 는
   * 목록 끝)
   */
  edgeMs: number;
  /**
   * 시크바 왼쪽 끝의 실제 시각 — 맨 왼쪽(Home) · 왼쪽 끝을 넘는 이동이 요청하는 자리이자 카드의 범위 밖 문턱. 되감기 ·
   * 다시보기는 로드한 목록의 첫 내용 조각, 라이브는 되감기 범위 첫 시각까지 넓힌다(POK-573)
   */
  barLowerMs: number;
  /** 세션에 넘기는 시크 범위 — 창 하한과 로드한 목록의 끝 */
  view: SeekView;
  windowSeconds: number;
  behindSeconds: number;
  /** behindSeconds 의 반올림 전 값(ms, 같은 상한) — 보이는 시차(steadySeconds)와 끝 판정(isAtEdge)의 입력이다(POK-583) */
  behindMs: number;
}

/**
 * 세션이 받은 스트림 답에서 접은 사실(usePlaybackSession 이 든다). 상태기계는 되감기 국면에서 라이브가 아닌 답을 버리므로
 * 따로 든다. streamLive = 최신 답이 이 회차를 부정하지 않는 라이브다(준비 여부 · url 무관 — 발행 전 재접속 · X 모름 포함) ·
 * streamEnded = 이 회차가 다시보기 · 끝남으로 답한 적이 있다(세션 동안 붙잡는다 — 다른 방송의 라이브 답으로 풀리지 않는다) ·
 * roundDenied = X 를 아는 최신 라이브 답이 이 회차를 부정했다(다른 방송 · 방송 없음). X 모름 라이브 답은 앞 값을 둔다 —
 * 부정도 이 회차 확인도 아니다. 붙잡지 않는다 — 방송 목록이 잠시 비거나 X 가 잘못 알려질 수 있어, X 가 이 회차라는 라이브
 * 답이 오면 풀린다
 */
export interface StreamFacts {
  streamLive: boolean;
  streamEnded: boolean;
  roundDenied: boolean;
}

/** 세션을 연 직후 — 방송 중에 연 세션이라 첫 답 전에는 이어짐으로 본다 */
export const OPEN_STREAM_FACTS: StreamFacts = {
  streamLive: true,
  streamEnded: false,
  roundDenied: false,
};

/**
 * 스트림 답 하나를 접은 다음 사실. 상태기계가 버리는 답은 접지 않는다(같은 객체) — 이미 반영한 물음 순번 이하인 늦은 답
 * (nextStreamAsk 가 null) · 받지 못한 답(unavailable · none). session 은 이 답을 먹이기 직전의 상태다. x 는 그 답과 함께
 * 물은 지금 방송 번호다 — 다른 방송 · 방송 없음이면 라이브가 아니고 이 회차가 끝났다는 확인이다. 다른 방송이면 그
 * 다시보기 · 끝남은 이 회차의 것이 아니다. X 모름은 부정도 이 회차 확인도 아니라 부정 여부를 바꾸지 않는다
 */
export function streamFactsAfter(
  prev: StreamFacts,
  session: PlaybackSessionState,
  event: StreamPollEvent,
): StreamFacts {
  const { result, x } = event;
  if (nextStreamAsk(session, event) === null || result.kind !== 'ok') return prev;
  const { mode } = result.status;
  const denied = deniesRound(session, x);
  const otherRound = x?.kind === 'known' && x.id !== '' && x.id !== session.roundId;
  const thisRoundEnded = (mode === 'vod' || mode === 'ended') && !otherRound;
  return {
    streamLive: mode === 'live' && !denied,
    streamEnded: prev.streamEnded || thisRoundEnded,
    // X 를 아는 라이브 답만 부정 여부를 정한다 — X 모름 라이브 답 · 정리 중 · 다시보기 · 끝남 답은 앞 값을 둔다
    roundDenied: mode === 'live' && x?.kind === 'known' ? denied : prev.roundDenied,
  };
}

/**
 * 방송이 끝났다는 근거가 있는가 — 되감기 축의 끝을 로드한 목록 끝으로 재고 끝 문구를 「녹화 끝」 으로 하는 기준이다. 국면이
 * 아니라 근거로 가른다(끝남 대기는 방송 중 재접속에도 들고, 되감기는 방송이 끝나도 pendingNext 가 남으면 그대로다).
 * 1. 라이브 국면은 늘 아니다 — 라이브 축 · 문구를 그대로 둔다
 * 2. 끝났다는 확인이면 그렇다 — streamEnded(이 회차 다시보기 · 끝남) · roundDenied(X 를 아는 최신 라이브 답이 이 회차를
 *    부정 — 그 뒤 X 모름 라이브 답에도 남는다) · 회차 다시보기 제안 · 회차 종국(닫기로 정함). 다른 방송 보기 제안은 따로
 *    보지 않는다 — 그 제안을 낳은 다른 방송 X 의 답이 이미 roundDenied 를 세운다
 * 3. 확인이 없는 되감기는 방송 중이다(pendingNext · 다시 받기 포함) — 아니다
 * 4. 끝남 대기는 최신 스트림 답이 라이브가 아니면 그렇다(!streamLive). 정상 종료는 ENDLIST 뒤 다음 답(정리 중)에서 바뀐다.
 *    그 답 전 · X 모름 · 발행 전(ready:false) 라이브 답이면 벽시계다(X 모름은 앞서 부정이 섰으면 2 로 목록 끝). 받지
 *    못한 답은 앞 값을 둔다(정리 중 뒤면 목록 끝).
 *    재접속 틈은 가르지 못한다: 앞 세션이 끝나고 재접속 라이브 답이 오기 전까지 서버는 정상 종료와 똑같이 정리 중이라고
 *    답하므로, 그동안은 목록 끝 · 「녹화 끝」 이고 재접속 라이브 답이 오면 벽시계로 돌아간다. 정리 중을 벽시계로 두면 정상
 *    종료에서 시차가 녹화 길이보다 커지는 결함이 되살아난다. 틈 동안에는 목록 끝 너머에 볼 내용이 없고, 그 틈은 재접속 창
 *    (300초) 안이다
 */
export function recordingEndedOf(session: PlaybackSessionState, facts: StreamFacts): boolean {
  const { phase } = session;
  if (phase.kind === 'live') return false;
  const confirmed =
    facts.streamEnded ||
    facts.roundDenied ||
    phase.kind === 'vod-offered' ||
    (phase.kind === 'ending-wait' && phase.closeOnEnd);
  if (confirmed) return true;
  if (phase.kind !== 'ending-wait') return false;
  return !facts.streamLive;
}

function clampSeconds(ms: number): number {
  return Math.min(REWIND_UI_MAX_SECONDS, Math.max(0, Math.round(ms / 1000)));
}

/**
 * 세 재생기(라이브 · 되감기 · 다시보기)가 같은 시크바를 쓰도록 실제 시각 축으로 센다(설계 5.2절). 시차는 라이브 끝에서 몇 초
 * 뒤인가이고 폭은 시크바 왼쪽 끝부터 라이브 끝까지다(표시 상한 1시간). 라이브 끝을 모르면 null
 */
export function sessionAxis(input: SessionAxisInput): SessionAxis | null {
  const { kind, fragments } = input;
  const list = loadedList(fragments, input.targetDurationS);
  if (list === null) return null;
  let edgeMs: number;
  if (kind === 'live') {
    const edge = input.liveEdgeTime === null ? null : programDateAt(fragments, input.liveEdgeTime);
    if (edge === null) return null;
    edgeMs = edge;
  } else {
    // 방송이 끝난 되감기는 목록이 더 자라지 않는다 — 벽시계로 세면 시차가 재생해도 줄지 않고 녹화 길이보다 커진다
    const clock = kind === 'rewind' && input.recordingEnded !== true;
    edgeMs = clock ? input.nowMs - input.lagMs : list.lastEndMs;
  }
  const atMs = programDateAt(fragments, input.currentTime);
  const barLowerMs =
    kind === 'live'
      ? Math.min(input.axisLowerMs ?? list.firstMs, list.firstMs)
      : (list.contentFirstMs ?? list.firstMs);
  return {
    list,
    atMs,
    edgeMs,
    barLowerMs,
    view: {
      lowerMs: windowLowerMs(list, kind, input.live),
      endMs: kind === 'live' ? edgeMs : list.lastEndMs,
    },
    windowSeconds: clampSeconds(edgeMs - barLowerMs),
    behindSeconds: atMs === null ? 0 : clampSeconds(edgeMs - atMs),
    behindMs:
      atMs === null ? 0 : Math.min(REWIND_UI_MAX_SECONDS * 1000, Math.max(0, edgeMs - atMs)),
  };
}
