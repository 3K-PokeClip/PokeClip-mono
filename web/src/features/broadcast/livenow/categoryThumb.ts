import type { BroadcastTimeline } from '@/api/clipEditor';
import { timeBaseOf, type LiveData } from './liveDataStore';

// 정보 바 왼쪽 그림은 「그 시점」 카테고리 사진이다(POK-467, 치지직 · SOOP 같은 방식). 방송 중 카테고리를 바꾸면 그림도 바뀌고,
// 라이브를 되감거나 지난 방송을 옮기면 그 자리의 카테고리다. 사진을 못 얻으면 스트리머 프로필(POK-457)이 그대로 선다.

/** 카테고리가 같은 한 구간. 창구 응답과 갈라 둔 화면 자료형이다: 창구 모양이 바뀌어도 categorySpansOf만 고친다 */
export interface CategorySpan {
  platform: string;
  /** 구간 시작(epoch ms, 포함) */
  fromMs: number;
  /** 구간 끝(epoch ms, 제외). 지금도 이 값이면 null */
  untilMs: number | null;
  /** 카테고리 이름. 카테고리가 없으면 null */
  name: string | null;
  /** 카테고리 사진 주소. 못 얻었으면 null */
  imageUrl: string | null;
}

/** 시점별 기록 창구(POK-462)의 답을 구간 목록으로 옮긴다. from 순이고, 시각을 못 읽는 구간은 버린다 */
export function categorySpansOf(timeline: BroadcastTimeline | null | undefined): CategorySpan[] {
  if (!timeline) return [];
  return timeline.segments
    .flatMap((s) => {
      const fromMs = Date.parse(s.from);
      const untilMs = s.until === null ? null : Date.parse(s.until);
      if (Number.isNaN(fromMs) || (untilMs !== null && Number.isNaN(untilMs))) return [];
      return [
        { platform: s.platform, fromMs, untilMs, name: s.category, imageUrl: s.categoryImageUrl },
      ];
    })
    .sort((a, b) => a.fromMs - b.fromMs);
}

/**
 * 그 시각의 구간: 그 플랫폼에서 from ≤ t < until인 구간이다(until이 없으면 지금도). 시각을 모르면(null) 그 플랫폼의 마지막 구간이다:
 * 라이브 실시간이면 지금, 지난 방송이면 방송 끝이다. 첫 구간 앞 · 구간 사이 · 마지막 구간 뒤는 모른다(null, 창구 계약). 동시 방송이면
 * 대표 플랫폼(정보 바의 제목 · 카테고리와 같은 플랫폼)만 본다. 대표 플랫폼을 모르면 가리지 않는다. 목록은 from 순이어야 한다
 */
export function categoryAt(
  spans: CategorySpan[],
  atMs: number | null,
  platform: string | null,
): CategorySpan | null {
  const mine = platform === null ? spans : spans.filter((s) => s.platform === platform);
  if (atMs === null) return mine[mine.length - 1] ?? null;
  let found: CategorySpan | null = null;
  for (const s of mine) {
    if (s.fromMs > atMs) break;
    if (s.untilMs === null || atMs < s.untilMs) found = s;
  }
  return found;
}

/**
 * 화면이 보고 있는 실제 시각(epoch ms). 채팅이 따르는 자리와 같다(useChatPanelMockState의 replayCursor): 라이브를 되감았으면 되감은
 * 자리, 지난 방송은 기준점 + 재생 위치다. 라이브 실시간, 지난 방송인데 재생 위치를 모름, 상태를 모름이면 null(마지막 구간)이다
 */
export function focusAtMs(
  live: Pick<
    LiveData,
    'status' | 'playheadMs' | 'liveRewind' | 'timeBaseMs' | 'originMs' | 'startedAt'
  >,
): number | null {
  if (live.status === 'live') return live.liveRewind?.atMs ?? null;
  if (live.status !== 'ended') return null;
  const base = timeBaseOf(live);
  return live.playheadMs !== null && base !== null ? base + live.playheadMs : null;
}
