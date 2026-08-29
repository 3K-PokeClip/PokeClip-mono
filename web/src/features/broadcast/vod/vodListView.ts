// 지난 방송 목록의 표시 규칙 — 계약(VodBroadcast)에서 시안 1f 행이 필요한 것을 만든다.
//
// 렌더에서 떼어낸 이유는 highlightCardView와 같다: D-day 경계와 기간 필터는 날짜 조합이
// 많아 jsdom 렌더로 훑기보다 여기서 전수 검사하는 편이 싸다. status 유니온이 늘면 아래
// 표가 타입으로 깨진다 — 화면이 조용히 빈 행을 그리는 대신 빌드가 멈춘다.

import { formatUptime } from '@/features/player/playerMath';
import type {
  VodBroadcast,
  VodCustomRange,
  VodPeriodFilter,
  VodRowVisual,
} from './useVodListMockState';

const DAY_MS = 24 * 60 * 60 * 1000;

/** 만료 임박 경계 — 홈 ExpiringVod의 「D-3 이하 붉은 배지」(useHomeMockState)와 같은 값이다 */
export const VOD_EXPIRY_URGENT_DAYS = 3;

const DATE_FORMAT = new Intl.DateTimeFormat('ko-KR', { month: 'long', day: 'numeric' });

export type VodDday =
  | { kind: 'active'; label: string; urgent: boolean }
  /** 기한이 지난 방송도 목록에 남는다 — 계약이 vodExpiresAt을 싣는 이유다 */
  | { kind: 'expired' }
  | { kind: 'unknown' };

/**
 * 보관 만료까지 남은 날 → 「D-54」. ceil이라 오늘 안에 끝나는 것도 D-1로 읽힌다 —
 * 남은 시간이 조금이라도 있으면 「오늘까지」이지 「끝났다」가 아니다.
 */
export function ddayFor(vodExpiresAt: string | null, now: Date): VodDday {
  if (!vodExpiresAt) return { kind: 'unknown' };
  const remaining = new Date(vodExpiresAt).getTime() - now.getTime();
  if (!Number.isFinite(remaining)) return { kind: 'unknown' };
  if (remaining <= 0) return { kind: 'expired' };
  const days = Math.ceil(remaining / DAY_MS);
  return { kind: 'active', label: `D-${days}`, urgent: days <= VOD_EXPIRY_URGENT_DAYS };
}

/**
 * 행의 방송일 → 「7월 26일」. 끝난 방송이므로 endedAt이 기준이고, 종료 알림이 아직 없으면
 * startedAt으로 물러선다. 둘 다 비면 null이다 — 계약이 startedAt에 null을 허용하고
 * (ADR-016 종료 선도착) 「감추거나 지어내지 않는다」가 그 칸의 규칙이라, 화면이
 * 「방송일 미상」을 그린다.
 */
export function dateLabel(item: VodBroadcast): string | null {
  const source = item.endedAt ?? item.startedAt;
  if (!source) return null;
  const time = new Date(source).getTime();
  if (!Number.isFinite(time)) return null;
  return DATE_FORMAT.format(time);
}

/** 썸네일 위 길이 표기 — 「4:12:08」. 준비 중이라 아직 모르면 null */
export function durationLabel(durationSec: number | null): string | null {
  if (durationSec === null || !Number.isFinite(durationSec)) return null;
  return formatUptime(durationSec);
}

/** 시안 1f 행이 아는 상태 — 계약의 status와 목업 진행률이 여기로 접힌다 */
export type VodRowView =
  /** 방금 종료 — VOD가 아직 없어 열 수 없다 */
  | { kind: 'preparing' }
  /** 풀 VOD 저장 중 — 행 안에서 진행률을 보여준다 (B5 미구현이라 목업 전용 상태다) */
  | { kind: 'downloading'; progress: number }
  | { kind: 'ready' };

const VIEW_BY_STATUS: Record<VodBroadcast['status'], VodRowView['kind']> = {
  // live는 이 화면에 오지 않는다(excludeLive) — 와도 열 수 있는 것이 없으니 준비 중으로 접는다
  live: 'preparing',
  ended: 'preparing',
  vod_ready: 'ready',
};

export function rowViewFor(item: VodBroadcast, visual: VodRowVisual | undefined): VodRowView {
  if (VIEW_BY_STATUS[item.status] === 'preparing') return { kind: 'preparing' };
  const progress = visual?.downloadProgress;
  if (progress !== undefined) return { kind: 'downloading', progress };
  return { kind: 'ready' };
}

/**
 * 「지난 방송」이므로 방송 중인 것은 뺀다. 목록 문은 `state=past`로 부르지만 그것은 서버에
 * 거는 조건이고, 화면이 무엇을 그리는지는 화면이 정한다 — 목업이 live를 실어도 여기서 걸린다.
 */
export function excludeLive(items: readonly VodBroadcast[]): VodBroadcast[] {
  return items.filter((item) => item.status !== 'live');
}

/** 기간 필터가 보는 시각 — 끝난 시각이 기준이고, 없으면 시작 시각으로 물러선다 */
function periodTimeOf(item: VodBroadcast): number | null {
  const source = item.endedAt ?? item.startedAt;
  if (!source) return null;
  const time = new Date(source).getTime();
  return Number.isFinite(time) ? time : null;
}

/** 'YYYY-MM-DD' → 그 날의 시작(00:00) / 끝(23:59:59.999) 로컬 시각. 못 읽으면 null */
function dayBoundary(day: string, edge: 'start' | 'end'): number | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day.trim());
  if (!match) return null;
  const [, year, month, date] = match;
  const time =
    edge === 'start'
      ? new Date(Number(year), Number(month) - 1, Number(date), 0, 0, 0, 0).getTime()
      : new Date(Number(year), Number(month) - 1, Number(date), 23, 59, 59, 999).getTime();
  return Number.isFinite(time) ? time : null;
}

/**
 * 기간 칩 필터. 시각을 모르는 방송(startedAt·endedAt 둘 다 null)은 어느 기간에도 못 넣으므로
 * 「전체」에서만 보인다 — 지어낸 날짜로 아무 칸에나 넣는 것보다 낫다.
 *
 * 「기간 지정」은 한쪽만 채운 상태를 제약 없음으로 본다. 반쯤 입력하는 동안 목록이 텅 비면
 * 고장으로 읽히기 때문이다. from이 to보다 뒤면 결과가 0이고, 화면이 그 사실을 문장으로 말한다.
 */
export function filterByPeriod(
  items: readonly VodBroadcast[],
  filter: VodPeriodFilter,
  customRange: VodCustomRange,
  now: Date,
): VodBroadcast[] {
  if (filter === 'all') return [...items];

  if (filter === 'custom') {
    const from = customRange.from ? dayBoundary(customRange.from, 'start') : null;
    const to = customRange.to ? dayBoundary(customRange.to, 'end') : null;
    if (from === null && to === null) return [...items];
    return items.filter((item) => {
      const time = periodTimeOf(item);
      if (time === null) return false;
      if (from !== null && time < from) return false;
      if (to !== null && time > to) return false;
      return true;
    });
  }

  const days = filter === '7d' ? 7 : 30;
  const since = now.getTime() - days * DAY_MS;
  return items.filter((item) => {
    const time = periodTimeOf(item);
    // 경계 포함 — 「7일」은 지금부터 168시간 전까지이고 그 시각 자체도 안에 든다
    return time !== null && time >= since;
  });
}
