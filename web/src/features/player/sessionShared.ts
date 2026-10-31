import type { RewindStatus, RewindStatusResult } from '@/api/rewindStatus';
import type {
  PlaybackSessionState,
  PlaybackSource,
  SeekView,
  SessionClose,
  SessionEffect,
  Transition,
  XAnswer,
} from './rewindSwitch';

// 재생 세션 상태기계(rewindSwitch.ts)와 끝남 대기 표(endingWait.ts)가 나눠 쓰는 값과
// 도구(POK-200 c13). 두 표가 서로의 값을 읽지 않도록 여기에 둔다 — 여기는
// rewindSwitch 의 타입만 읽는다.

/** 재접속 대기 = 재접속 창 300초 + 판정 여유 60초(M6 회차 닫힘 조건과 같다). 이 동안 회차별 404 는 「아직 모름」이다 */
export const RESUME_WAIT_MS = 360_000;
/** 끝남 대기 시한(20분). 끝에 닿은 시청자는 녹화로, 아직 보는 시청자는 묻기를 멈추고 끝까지 본 뒤 녹화로 간다 */
export const ENDING_WAIT_MS = 1_200_000;
/** LIVE 버튼의 「지금 방송 번호」 다시 묻기 상한(2초 간격) */
const X_RECHECK_LIMIT = 3;

/** 되감기 url 과 그 범위(PDT 실제 시각) */
export interface RewindRange {
  url: string;
  firstPdtMs: number;
  headPdtMs: number;
}

export const SESSION_NOTICES = {
  oldest: '가장 오래된 지점으로 옮겼어요',
  gap: '방송이 끊긴 구간이라 이어지는 자리로 옮겼어요',
  vodStart: '다시보기는 이 시각부터 볼 수 있어요',
} as const;

// ── 작은 도구 ──

/** 효과 없는 전이 — 상태를 그대로 두거나 바꾼 상태만 돌려준다 */
export function stateOnly(state: PlaybackSessionState): Transition {
  return { state, effects: [] };
}

export function withUrl(set: ReadonlySet<string>, url: string): ReadonlySet<string> {
  return set.has(url) ? set : new Set([...set, url]);
}

export function readyLive(result: RewindStatusResult): (RewindStatus & RewindRange) | null {
  if (result.kind !== 'ok') return null;
  const { status } = result;
  if (status.mode !== 'live' || !status.ready) return null;
  if (status.url === null || status.firstPdtMs === null || status.headPdtMs === null) return null;
  return { ...status, url: status.url, firstPdtMs: status.firstPdtMs, headPdtMs: status.headPdtMs };
}

export function within(atMs: number | null, first: number, head: number): atMs is number {
  return atMs !== null && first <= atMs && atMs <= head;
}

/**
 * 지금 방송 번호 X 가 이 세션의 회차인가 — 되감기의 새 url 은 이때만 잇는다(같은 방송이
 * 끊겼다 돌아온 경우 · kty 결정). 모름 · 빈 값 · 답 없음은 확인되지 않은 것이다
 */
export function isThisRound(session: PlaybackSessionState, x: XAnswer | undefined): boolean {
  return x?.kind === 'known' && x.id === session.roundId;
}

/**
 * 지금 방송 번호 X 가 이 세션의 회차가 아니라고 답했나 — 다른 방송 · 빈 값(방송 없음). 모름 · 답 없음은 아니다.
 * 스트림 응답이 잠시 불가여도 넘김 근거를 지우는 부정 근거다
 */
export function deniesRound(session: PlaybackSessionState, x: XAnswer | undefined): boolean {
  return x?.kind === 'known' && x.id !== session.roundId;
}

export function replace(
  session: PlaybackSessionState,
  kind: PlaybackSource['kind'],
  url: string,
  startAtMs: number | null,
): PlaybackSource {
  return { kind, url, startAtMs, generation: session.source.generation + 1, strictStart: false };
}

export function close(
  session: PlaybackSessionState,
  reason: SessionClose,
  goTo?: string,
): Transition {
  const effect: SessionEffect =
    goTo === undefined ? { kind: 'close', reason } : { kind: 'close', reason, goTo };
  return { state: { ...session, closed: reason }, effects: [effect] };
}

// ── 시크 ──

export function seekLive(
  session: PlaybackSessionState,
  targetMs: number,
  view: SeekView,
): Transition {
  if (targetMs >= view.lowerMs) {
    return {
      state: session,
      effects: [{ kind: 'seek-within', atMs: Math.min(targetMs, view.endMs) }],
    };
  }
  const entry = session.rewindOpen ? session.entry : null;
  if (entry === null) {
    return {
      state: session,
      effects: [
        { kind: 'seek-within', atMs: view.lowerMs },
        { kind: 'notice', text: SESSION_NOTICES.oldest },
      ],
    };
  }
  let start = targetMs;
  const effects: SessionEffect[] = [];
  if (targetMs < entry.firstPdtMs) {
    start = entry.firstPdtMs;
    effects.push({ kind: 'notice', text: SESSION_NOTICES.oldest });
  } else if (targetMs > entry.headPdtMs) {
    start = entry.headPdtMs;
    effects.push({ kind: 'notice', text: SESSION_NOTICES.gap });
  }
  return {
    state: {
      ...session,
      phase: { kind: 'rewind', pendingNext: null },
      source: replace(session, 'rewind', entry.url, start),
      boundLive: false,
      foreignLive: null,
      liveChecking: false,
    },
    effects,
  };
}

/** 로드한 목록 안 시크(되감기 · vod) — 하한 앞은 하한으로 알림, 끝 뒤는 끝으로 */
export function seekInList(
  session: PlaybackSessionState,
  targetMs: number,
  view: SeekView,
): Transition {
  if (targetMs < view.lowerMs) {
    return {
      state: session,
      effects: [
        { kind: 'seek-within', atMs: view.lowerMs },
        { kind: 'notice', text: SESSION_NOTICES.oldest },
      ],
    };
  }
  return {
    state: session,
    effects: [{ kind: 'seek-within', atMs: Math.min(targetMs, view.endMs) }],
  };
}

// ── LIVE 버튼 ──

/** rewind · 끝남 대기(atEnd:false) · vod-offered(atEnd:false) 의 LIVE — X 로 가른다(R58) */
export function liveFromAway(
  session: PlaybackSessionState,
  currentX: XAnswer,
  attempt: number,
): Transition {
  if (currentX.kind === 'known' && currentX.id !== '' && currentX.id !== session.roundId) {
    return close(session, 'left', currentX.id);
  }
  if (currentX.kind === 'unknown' && attempt < X_RECHECK_LIMIT) {
    return {
      state: { ...session, liveChecking: true },
      effects: [{ kind: 'recheck-x', attempt: attempt + 1 }],
    };
  }
  return {
    state: {
      ...session,
      phase: { kind: 'live' },
      source: replace(session, 'live', session.liveUrl, null),
      liveChecking: false,
      boundLive: true,
      offer: null,
    },
    effects: [],
  };
}
