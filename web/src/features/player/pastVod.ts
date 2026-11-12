import type { RewindStatus, RewindStatusResult } from '@/api/rewindStatus';
import { RESUME_WAIT_MS, type RewindRange } from './sessionShared';

// 끝난 뒤 들어온 화면(A — 세션을 연 적 없이 명부가 ended 인 화면)의 다시보기 판정(POK-546
// 설계 2-1 · 4-5). 회차 경로 답 → 다시보기 상태, 그리고 카드 누름을 어느 길로 보낼지를 정하는
// 순수 함수다. React 없이 단위 시험하려고 훅과 뗀다(endingWait.ts 와 같은 꼴). 웹은 회차
// 경로 답만 믿는다 — 목록에 오르는지는 서버가 정한다.

/**
 * 다시보기 상태. 회차 경로 답이 쌓여 정한다 — 같은 답이라도 직전 상태에 따라 결과가 다르다
 * (nextPastVodState)
 */
export type PastVodState =
  /** 물을 수 없음 — 제어면 주소가 없거나 경로를 만들 수 없다 */
  | { kind: 'unconfigured' }
  /** 아직 모름. answered 거짓 = 아직 아무 답도 없음, 참 = mode null 또는 유예 안 404 를 받음 */
  | { kind: 'checking'; answered: boolean }
  /** 곧 선다 — 회차 답이 ending · 미준비 vod · live */
  | { kind: 'preparing' }
  /** vod ∧ ready — 그 목록을 튼다 */
  | { kind: 'ready'; vod: RewindRange }
  /** 서지 않는다 — 회차 답 ended, 또는 유예가 지난 404 */
  | { kind: 'none' }
  /** 잠시 불가 — 아직 아무 답도 없을 때 받은 503 · 그 밖 응답 · 망 · 형식 오류 · 시한 */
  | { kind: 'unavailable' };

/** 404 유예를 잴 시계 — 답을 받은 때와 유예의 원점(nextPastVodState 가 404 에서만 본다) */
export interface PastVodClock {
  /** 답을 받은 시각(epoch ms) */
  nowMs: number;
  /** 404 유예의 원점 — 명부 endedAt, 없으면 이 화면이 처음 404 를 받은 시각 */
  unknownSinceMs: number;
}

/**
 * VOD 재생기의 국면(설계 2-3 — 계획 4절 A 로 타입만 여기에 둔다. useVodPlayback 이 낸다)
 * - loading: 재생기가 섰고 첫 목록 전
 * - listed: 첫 목록을 받음 · 화면에 붙은 위치는 아직
 * - showing: 화면에 붙은 위치를 한 번 이상 냄
 * - unsupported: hls.js 없음 — 기본 HLS 로 틀면 조각 목록 · PDT 축이 없고, 기본 HLS 도 없으면 틀 수 없다(둘 다)
 * - failed: 위치를 한 번도 못 낸 채 멈춤(loading · listed 중 재생 오류)
 */
export type VodPhase = 'loading' | 'listed' | 'showing' | 'unsupported' | 'failed';

/** 카드를 누를 수 없는 까닭 — 안내 문구를 고르는 열쇠 */
export type PastBlockReason =
  | 'none'
  | 'preparing'
  | 'unavailable'
  | 'unconfigured'
  | 'access-failed'
  | 'expired'
  | 'unsupported'
  | 'failed';

/** 부모(LiveDashboard)가 카드 누름을 어느 길로 보낼지 — 갈래 넷 */
export type PastCardMode =
  /** 라이브 · B(세션을 연 화면) · 명부 상태 모름 — 지금 길 그대로 */
  | { kind: 'notPast' }
  /** A · 다시보기가 곧 선다 — 누른 자리를 받아 둔다 */
  | { kind: 'waiting' }
  /** A · 재생기가 목록을 받음 — PDT 로 옮긴다 */
  | { kind: 'playable' }
  /** A · 끝내 서지 않음(또는 멈춤) — 까닭을 알린다 */
  | { kind: 'blocked'; reason: PastBlockReason };

/** 카드 갈래를 가르는 입력(pastCardMode) — 화면 · 다시보기 상태 · 출입증 · 재생기 국면을 한데 모은다 */
export interface PastCardInput {
  /**
   * 끝난 뒤 들어온 화면(A)인가 — 세션을 연 적 없이 명부가 ended 다(useLiveSession 이 정한다). 라이브 · B(세션을 연 화면) ·
   * 명부 상태 모름은 거짓이다
   */
  enteredAfterEnd: boolean;
  /** 410 보관 만료(accessExpiredFor === accessKey) */
  expired: boolean;
  vod: PastVodState;
  /** 출입증 — accessReady · accessFailedFor === accessKey */
  access: 'pending' | 'ready' | 'failed';
  /** 재생기가 없으면 null */
  phase: VodPhase | null;
}

/**
 * 답 하나를 먹인다(계약3 7-2 · 7-3). 결과는 답과 직전 상태에 따라 다르다.
 * - ready 는 어떤 답에도 내려가지 않는다(재생 중 목록을 끊지 않는다).
 * - 200 은 국면이 정한다: ended → none · mode null → checking(답 받음) · vod ∧ ready → ready ·
 *   ending · 미준비 vod · live → preparing.
 * - 404 는 원점에서 유예(RESUME_WAIT_MS — 같은 계약 값) 안이면 아직 모름이라 직전을 둔다(아직
 *   아무 답도 없었으면 checking(답 받음)). 유예가 지났으면 none.
 * - 503 · 망 · 형식 오류 · 시한은 직전을 둔다. 아직 아무 답도 없을 때만 unavailable.
 */
export function nextPastVodState(
  prev: PastVodState,
  result: RewindStatusResult,
  clock: PastVodClock,
): PastVodState {
  if (prev.kind === 'ready') return prev;
  switch (result.kind) {
    case 'ok':
      return fromRoundStatus(result.status, prev);
    case 'none':
      if (clock.nowMs - clock.unknownSinceMs >= RESUME_WAIT_MS) return { kind: 'none' };
      return isUnanswered(prev) ? { kind: 'checking', answered: true } : prev;
    case 'unavailable':
      return isUnanswered(prev) ? { kind: 'unavailable' } : prev;
    default:
      return prev;
  }
}

/** 아직 아무 답도 받지 않은 상태인가 */
function isUnanswered(state: PastVodState): boolean {
  return state.kind === 'checking' && !state.answered;
}

/** 200 회차 답 → 상태. 범위가 빠진 ready 는 형식 오류처럼 다룬다(파서가 막지만 형으로 한 번 더) */
function fromRoundStatus(status: RewindStatus, prev: PastVodState): PastVodState {
  const { mode, ready, url, firstPdtMs, headPdtMs } = status;
  if (mode === null) return { kind: 'checking', answered: true };
  if (mode === 'ended') return { kind: 'none' };
  if (mode !== 'vod' || !ready) return { kind: 'preparing' };
  if (url === null || firstPdtMs === null || headPdtMs === null) {
    return isUnanswered(prev) ? { kind: 'unavailable' } : prev;
  }
  return { kind: 'ready', vod: { url, firstPdtMs, headPdtMs } };
}

/** 다시 물을 상태인가 — checking · preparing · unavailable 은 참, unconfigured · ready · none 은 거짓 */
export function pastVodNeedsPoll(state: PastVodState): boolean {
  return state.kind === 'checking' || state.kind === 'preparing' || state.kind === 'unavailable';
}

/** 한 회차 경로로 쌓은 판정 — 상태와 첫 404 시각(유예 원점이 따로 없을 때의 404 유예 원점) */
export interface PastVodHold {
  state: PastVodState;
  /** 이 경로로 처음 404 를 받은 시각. 처음 받은 그대로 두고 덮지 않는다 */
  firstUnknownMs: number | null;
}

/**
 * 답 하나를 묶음에 접는다(A 화면 usePastVod · 지난 방송 목록이 같은 규칙을 쓴다). 404 유예의 원점은 originMs(명부
 * endedAt 등), 없으면 이 경로의 첫 404 시각, 그것도 없으면 지금이다. 404 를 받으면 첫 404 시각을 적는다
 * @param originMs 404 유예의 원점. 없으면 null
 */
export function foldPastVodAnswer(
  prev: PastVodHold,
  result: RewindStatusResult,
  nowMs: number,
  originMs: number | null,
): PastVodHold {
  const firstUnknownMs =
    result.kind === 'none' ? (prev.firstUnknownMs ?? nowMs) : prev.firstUnknownMs;
  const clock = { nowMs, unknownSinceMs: originMs ?? firstUnknownMs ?? nowMs };
  return { state: nextPastVodState(prev.state, result, clock), firstUnknownMs };
}

/**
 * 카드 갈래(설계 2-1 판정표 10줄 — 위에서부터 처음 맞는 줄).
 * 끝나기 전에 들어온 화면은 notPast, 보관 만료는 blocked(expired)다. 그 밖은 다시보기 상태가
 * 먼저 가르고(ready 가 아니면 checking 만 waiting · 나머지는 같은 이름의 blocked), ready 면
 * 출입증 → 재생기 국면 순으로 가른다
 */
export function pastCardMode(input: PastCardInput): PastCardMode {
  const { enteredAfterEnd, expired, vod, access, phase } = input;
  if (!enteredAfterEnd) return { kind: 'notPast' };
  if (expired) return { kind: 'blocked', reason: 'expired' };
  if (vod.kind === 'checking') return { kind: 'waiting' };
  if (vod.kind !== 'ready') return { kind: 'blocked', reason: vod.kind };
  if (access === 'failed') return { kind: 'blocked', reason: 'access-failed' };
  if (access === 'pending') return { kind: 'waiting' };
  return cardModeOfPhase(phase);
}

/** vod ready ∧ 출입증 ready 일 때 재생기 국면 → 갈래(판정표 7–10줄) */
function cardModeOfPhase(phase: VodPhase | null): PastCardMode {
  switch (phase) {
    case 'listed':
    case 'showing':
      return { kind: 'playable' };
    case 'unsupported':
      return { kind: 'blocked', reason: 'unsupported' };
    case 'failed':
      return { kind: 'blocked', reason: 'failed' };
    default:
      // null(재생기 없음) · loading — 목록 전이라 받아 둔다
      return { kind: 'waiting' };
  }
}
