// 하이라이트 카드의 표시 규칙 — 동결 계약(LiveHighlight)에서 시안 1b 카드가 필요한 것을 만든다.
//
// 렌더에서 떼어낸 이유는 playerMath와 같다: 상태 6종 × 폴백 3단은 조합이 많아 jsdom 렌더로
// 훑기보다 여기서 전수 검사하는 편이 싸다. 그리고 status 유니온이 늘면 이 표가 타입으로 깨진다 —
// 화면이 조용히 빈 배지를 그리는 대신 빌드가 멈춘다.

import { parseClockLabel } from '@/features/player/playerMath';
import type { BadgeTone } from '@/ui';
import type { CardVisual } from './useLiveDetailsMockState';
import type { LiveHighlight, LiveStream } from './useLiveMockState';

/** 시안 HighlightCard가 아는 상태 — 계약의 status 6종이 여기로 접힌다 */
export type CardViewState = 'ready' | 'manual' | 'processing';

export interface HighlightCardView {
  state: CardViewState;
  badgeTone: BadgeTone;
  badgeLabel: string;
  /** 되감기 창을 벗어나 곧 사라질 카드 — 흐리게 */
  dimmed: boolean;
  /** 편집·업로드 버튼줄을 그릴지 — 남이 편집 중이거나 만료된 카드엔 할 일이 없다 */
  showActions: boolean;
}

/**
 * 카드가 실제로 그릴 표기값. cardVisuals에 있으면 그대로 쓰고, 없으면 계약 필드에서 만든다.
 *
 * 폴백이 필요한 이유는 둘이다: ① 수동 마킹으로 방금 생긴 카드는 목업 맵에 없다
 * ② POK-180이 실 SSE 카드를 실으면 id가 전부 낯설어진다. 어느 쪽이든 화면은 서야 한다.
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
  { state: CardViewState; tone: BadgeTone; label: string; dimmed: boolean; showActions: boolean }
> = {
  scored: { state: 'ready', tone: 'point', label: '검토 대기', dimmed: false, showActions: true },
  manual: { state: 'manual', tone: 'neutral', label: '수동 마킹', dimmed: false, showActions: true },
  editing: {
    state: 'processing',
    tone: 'accent',
    label: '편집 중',
    dimmed: false,
    showActions: false,
  },
  clipped: { state: 'ready', tone: 'success', label: '클립 완료', dimmed: false, showActions: true },
  unprocessed: {
    state: 'ready',
    tone: 'neutral',
    label: '미처리',
    dimmed: false,
    showActions: true,
  },
  expired: { state: 'ready', tone: 'danger', label: '만료', dimmed: true, showActions: false },
};

export function cardViewFor(highlight: LiveHighlight): HighlightCardView {
  const base = STATE_BY_STATUS[highlight.status];
  return {
    state: base.state,
    badgeTone: base.tone,
    // 누가 잡고 있는지가 편집 중 카드의 핵심 정보다 — 이름이 없으면 상태만 말한다
    badgeLabel:
      highlight.status === 'editing' && highlight.editorName
        ? `${base.label} · ${highlight.editorName}`
        : base.label,
    dimmed: base.dimmed,
    showActions: base.showActions,
  };
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
  // 모르는 카드 — 계약이 주는 것만으로 세운다. 길이·파형·경과는 알 길이 없어 감춘다
  // (자리만 비우면 되고, 지어내면 실연동 뒤 거짓 표기로 남는다).
  return {
    duration: null,
    reason: fallbackReason(highlight.source),
    posPercent: fallbackPosPercent(highlight.timestamp, stream.uptimeSeconds),
    spark: null,
    timeAgo: null,
    progress: null,
  };
}
