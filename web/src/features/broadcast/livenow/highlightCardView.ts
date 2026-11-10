// 하이라이트 카드의 표시 규칙 — 동결 계약(LiveHighlight)에서 시안 1b 카드가 필요한 것을 만든다.
//
// 렌더에서 떼어낸 이유는 playerMath와 같다: 상태 5종 × 폴백 3단은 조합이 많아 jsdom 렌더로
// 훑기보다 여기서 전수 검사하는 편이 싸다. 그리고 status 유니온이 늘면 이 표가 타입으로 깨진다 —
// 화면이 조용히 엉뚱한 상태를 그리는 대신 빌드가 멈춘다. 상태 배지(검토 대기 · 수동 마킹 …)는 카드에서 뺐다(2026-11-04 시안 B2-1).

import { parseClockLabel } from '@/features/player/playerMath';
import { CARD_THUMBNAIL_PASS_MS, pickThumbnailUrl } from '@/features/thumbnail/thumbnailUrl';
import type { CardVisual } from './useLiveDetailsMockState';
import type { LiveHighlight, LiveStream } from './useLiveMockState';

/** 시안 HighlightCard가 아는 상태: 계약의 status 5종이 여기로 접힌다. 'processing'(클립 생성 중)은 지금 내는 상태가 없다 */
export type CardViewState = 'ready' | 'manual' | 'processing';

export interface HighlightCardView {
  state: CardViewState;
  /** 되감기 창을 벗어나 곧 사라질 카드 — 흐리게 */
  dimmed: boolean;
  /** 편집·업로드 버튼줄을 그릴지: 만료된 카드엔 할 일이 없다 */
  showActions: boolean;
}

/**
 * 카드가 실제로 그릴 표기값. cardVisuals에 있으면 그대로 쓰고, 없으면 계약 필드에서 만든다.
 *
 * 폴백이 필요한 이유: 실연동 카드(card-N)는 목업 맵에 없다(POK-180·251, 맵은 지금 비어 있다). 그래도 화면은 서야 한다.
 * (전에는 화면에서 찍은 수동 마킹 카드도 여기로 왔지만 그 카드는 없어졌다, QA 2026-10-11)
 * meta 문자열을 파싱하지 않는 것은 의도다 — 자유 서술이라 형식이 바뀌면 조용히 어긋난다.
 */
export interface ResolvedCardVisual {
  duration: string | null;
  reason: string;
  posPercent: number;
  spark: readonly number[] | null;
  timeAgo: string | null;
  progress: number | null;
}

const STATE_BY_STATUS: Record<
  LiveHighlight['status'],
  { state: CardViewState; dimmed: boolean; showActions: boolean }
> = {
  scored: { state: 'ready', dimmed: false, showActions: true },
  manual: { state: 'manual', dimmed: false, showActions: true },
  clipped: { state: 'ready', dimmed: false, showActions: true },
  unprocessed: { state: 'ready', dimmed: false, showActions: true },
  expired: { state: 'ready', dimmed: true, showActions: false },
};

export function cardViewFor(highlight: LiveHighlight): HighlightCardView {
  // 「편집 중」 상태는 없다(POK-393): 카드를 찜하지 않아 누가 편집하는지 모르고, 여럿이 같은 카드를 각자 편집한다
  const base = STATE_BY_STATUS[highlight.status];
  return {
    state: base.state,
    dimmed: base.dimmed,
    showActions: base.showActions,
  };
}

/** 카드 길이 표기 — 60초 → 「1:00」 */
export function lengthLabel(seconds: number): string {
  const total = Math.max(0, Math.round(seconds));
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
}

/** 감지 사유를 모를 때의 최소 표기 — 계약이 늘 아는 것은 자동인지 수동인지뿐이다 */
function fallbackReason(source: LiveHighlight['source']): string {
  return source === 'manual' ? '수동 마킹' : '자동 감지';
}

/**
 * 타임라인 위치 0..100. 방송 경과 대비 카드 시각으로, 시안 표기값(80·70·56…)과 같은 값이 나온다.
 * 시각을 못 읽거나 방송 길이가 0이면 오른쪽 끝(방금 생긴 카드가 있을 자리)으로 둔다.
 */
function fallbackPosPercent(timestamp: string, uptimeSeconds: number): number {
  const seconds = parseClockLabel(timestamp);
  if (seconds === null || uptimeSeconds <= 0) return 100;
  return Math.min(100, Math.max(0, Math.round((seconds / uptimeSeconds) * 100)));
}

/** 눈금 계산에 넘기는 라이브 경과의 단위: 경과를 이만큼으로 나눈 값 */
const CARD_POSITION_STEPS = 100;

/**
 * 카드 눈금에 넘기는 라이브 경과. 경과는 매초 늘어 그대로 넘기면 카드 전부가 매초 다시 그려지므로 경과의 1% 단위로 올려 묶는다.
 * 단위를 고정하면 방송 초반의 오차가 커진다: 15초 단위면 61초에 방금 생긴 카드가 끝이 아니라 80% 자리에 선다.
 * 대신 첫 100초는 단위가 1초라 매초 다시 그려진다: 그 구간은 카드가 거의 없어 정확도를 택했다.
 */
export function cardPositionUptime(uptimeSeconds: number): number {
  const step = Math.max(1, Math.ceil(uptimeSeconds / CARD_POSITION_STEPS));
  return Math.ceil(uptimeSeconds / step) * step;
}

/**
 * 그려진 내용이 같은가. 사진은 글자가 아니라 같은 사진인지 본다: 서버가 목록마다 새로 서명해 주소가 3초마다 바뀐다(POK-277).
 * 새 주소를 아주 막지는 않는다: 못 받은 사진은 새 주소가 와야 다시 시도한다(CARD_THUMBNAIL_PASS_MS)
 */
function sameHighlight(a: LiveHighlight, b: LiveHighlight, now: number): boolean {
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]) as Set<keyof LiveHighlight>;
  for (const key of keys) {
    if (key !== 'thumbnailUrl' && !Object.is(a[key], b[key])) return false;
  }
  const shown = a.thumbnailUrl ?? null;
  return pickThumbnailUrl(shown, b.thumbnailUrl ?? null, now, CARD_THUMBNAIL_PASS_MS) === shown;
}

/**
 * 새로 만든 카드 목록에서 내용이 같은 카드는 앞 객체를 쓰고, 전부 같으면 앞 배열을 그대로 돌려준다. 경과 표기 때문에 목록을 매초 다시
 * 만들어도 표기가 바뀐 카드만 다시 그린다.
 */
export function reuseHighlights(
  prev: LiveHighlight[],
  next: LiveHighlight[],
  now: number,
): LiveHighlight[] {
  const known = new Map(prev.map((highlight) => [highlight.id, highlight]));
  const merged = next.map((highlight) => {
    const old = known.get(highlight.id);
    return old !== undefined && sameHighlight(old, highlight, now) ? old : highlight;
  });
  return merged.length === prev.length && merged.every((h, i) => h === prev[i]) ? prev : merged;
}

export function cardVisualFor(
  highlight: LiveHighlight,
  stream: LiveStream,
  visuals: Record<string, CardVisual>,
): ResolvedCardVisual {
  const known = visuals[highlight.id];
  if (known) {
    return {
      duration: known.duration,
      reason: known.reason,
      posPercent: known.posPercent,
      spark: known.spark,
      timeAgo: known.timeAgo,
      progress: known.progress ?? null,
    };
  }
  // 모르는 카드 — 계약이 주는 것만으로 세운다. 길이 · 경과는 카드 목록이 실어 주면(lengthSec · ago) 쓰고, 파형 · 진행률은
  // 알 길이 없어 감춘다(자리만 비우면 되고, 지어내면 실연동 뒤 거짓 표기로 남는다).
  return {
    duration: highlight.lengthSec !== undefined ? lengthLabel(highlight.lengthSec) : null,
    reason: fallbackReason(highlight.source),
    posPercent: fallbackPosPercent(highlight.timestamp, stream.uptimeSeconds),
    spark: null,
    timeAgo: highlight.ago ?? null,
    progress: null,
  };
}
