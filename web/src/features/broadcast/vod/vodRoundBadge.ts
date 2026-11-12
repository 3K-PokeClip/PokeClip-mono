import type { RewindStatusResult } from '@/api/rewindStatus';
import { foldPastVodAnswer, type PastVodHold, type PastVodState } from '@/features/player/pastVod';

// 지난 방송 목록 행의 표지(POK-568 설계 2-3 · 계획 3-1). 행마다 회차 경로 답을 끝난 뒤 들어온 화면(A)과 같은
// 규칙(foldPastVodAnswer — 404 유예 · 503 직전 유지 · ready 안 내려감)으로 접고, 표지는 따로 기억한다: 상태가 모름으로
// 돌아가도(mode null 등) 표지는 직전을 둔다. React 없이 단위 시험하려고 목록 훅과 뗀 순수 모듈이다(pastVod.ts 와 같은 꼴).

/** 목록 행 표지 — 「VOD 준비 중」 · 「다시 보기 가능」 · 「다시보기가 없어요」 */
export type VodBadge = 'preparing' | 'ready' | 'none';

/** 행마다 기억하는 판정(지난 방송 목록 쿼리 데이터의 memory 에서 방송마다 vod 칸에 산다 — useVodListMockState) */
export interface RowVodMemory extends PastVodHold {
  badge: VodBadge;
  /** 마지막으로 묻기를 시도한 시각(실패해도 넘긴다: 늘 실패하는 행이 처음 보는 행처럼 매 주기 상한 밖으로 묻지 않게) */
  askedAt: number | null;
}

/** 처음 보는 행 — 아직 아무 답도 없고 표지는 「VOD 준비 중」, 물은 적 없음 */
export const FRESH_ROW_VOD: RowVodMemory = {
  state: { kind: 'checking', answered: false },
  firstUnknownMs: null,
  badge: 'preparing',
  askedAt: null,
};

/**
 * 답 하나 → 새 기억. 상태가 ready · none · preparing 이면 그 이름이 표지고, 모름(checking · unavailable)이면 직전 표지를
 * 둔다. askedAt 은 답이 무엇이든(실패 포함) nowMs 로 세운다 — 부르는 쪽은 시도마다 부른다
 * @param endedAtMs 명부 endedAt — 404 유예의 원점. 없으면 이 행의 첫 404 시각이 원점이다
 */
export function foldRowVod(
  prev: RowVodMemory,
  result: RewindStatusResult,
  nowMs: number,
  endedAtMs: number | null,
): RowVodMemory {
  const hold = foldPastVodAnswer(prev, result, nowMs, endedAtMs);
  return { ...hold, badge: badgeOf(hold.state, prev.badge), askedAt: nowMs };
}

/** 상태 → 표지. 정해진 상태는 그 이름, 모름이면 직전 표지 */
function badgeOf(state: PastVodState, prevBadge: VodBadge): VodBadge {
  switch (state.kind) {
    case 'ready':
    case 'none':
    case 'preparing':
      return state.kind;
    default:
      // checking · unavailable(· 경로를 만들 수 없는 unconfigured) — 모름이라 직전을 둔다
      return prevBadge;
  }
}

/** 다시 묻지 않는 행인가 — 표지 ready · none(보관 만료는 부르는 쪽이 따로 거른다) */
export function isRowVodSettled(memory: RowVodMemory): boolean {
  return memory.badge === 'ready' || memory.badge === 'none';
}

/**
 * 이번 주기에 물을 행. 처음 보는 행(askedAt null) · 방금 끝난 행은 상한 없이 모두, 그 밖(오래된 미결 행)은
 * askedAt 이 오래된 것부터 limit 개까지. 같은 askedAt 은 목록 순(안정 정렬)
 */
export function pickRoundAsks(
  candidates: readonly { streamId: string; justEnded: boolean; askedAt: number | null }[],
  limit: number,
): ReadonlySet<string> {
  const asks = new Set<string>();
  const oldPending: { streamId: string; askedAt: number }[] = [];
  for (const { streamId, justEnded, askedAt } of candidates) {
    if (askedAt === null || justEnded) asks.add(streamId);
    else oldPending.push({ streamId, askedAt });
  }
  oldPending
    .sort((x, y) => x.askedAt - y.askedAt)
    .slice(0, limit)
    .forEach(({ streamId }) => asks.add(streamId));
  return asks;
}
