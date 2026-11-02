// 시안 1d 클립 편집기 타임라인의 수치·계산.
// 컴포넌트에서 분리한 순수 함수 — 구간 핸들 드래그 환산과 5초~3분 경계는
// jsdom 렌더 테스트로 검증할 수 없어(레이아웃이 없다) 여기서 단위 테스트한다
// (playerMath.ts와 같은 이유).

import { formatUptime, seekFractionFromPointer } from '@/features/player/playerMath';

/** 클립 길이 하한 — ADR-009 "클립 길이 5초~3분" */
export const MIN_RANGE_SECONDS = 5;

/** 클립 길이 상한 — 유튜브 Shorts 한도에 정렬 (ADR-009) */
export const MAX_RANGE_SECONDS = 180;

/** 상한을 타임코드로 — 게이지처럼 시각을 나열하는 자리(`0:12.4 / 최대 3:00`) */
export const MAX_RANGE_LABEL = '3:00';

/**
 * 상한을 말로 — 안내 문장이 쓴다. 「5초 미만·3:00 초과」처럼 한 문장 안에서
 * 말과 타임코드가 섞이면 읽는 사람이 두 번 셈해야 한다.
 * 두 표기 모두 MAX_RANGE_SECONDS 하나에서 나온다.
 */
export const MAX_RANGE_TEXT = `${MAX_RANGE_SECONDS / 60}분`;

/** 100% 배율에서 타임라인에 보이는 폭(구간이 더 길면 구간 × 1.15). 잡을 수 있는 범위보다 넓으면 범위까지만(시안 눈금자) */
export const BASE_VIEW_SECONDS = 75;

/**
 * 줌 단계: `100%`가 기본값이다(zoomSpan). 25% · 50%는 그 4배 · 2배로 넓히되 잡을 수 있는 범위까지만이라, 카드 창처럼 범위가
 * 좁으면 100%와 같다(화면이 축소 단추를 끈다). 200% · 400%는 100%에 보이는 폭의 절반 · 4분의 1로 재생 위치 둘레를 키운다.
 */
export const ZOOM_LEVELS = [25, 50, 100, 200, 400] as const;

/** 타임라인 높이 드래그 범위 — 시안 기본값과 접기 직전 최소 높이 */
export const MIN_TIMELINE_HEIGHT = 120;
export const MAX_TIMELINE_HEIGHT = 460;
export const DEFAULT_TIMELINE_HEIGHT = 190;

export type RangeRejectionReason = 'tooShort' | 'tooLong';

export interface ClipRange {
  startSeconds: number;
  endSeconds: number;
}

export interface RangeEdgeResult {
  /** 적용할 구간. 거부되면 요청 전 구간이 그대로 돌아온다 */
  range: ClipRange;
  /** 거부 사유 — null이면 적용됐다 */
  rejection: RangeRejectionReason | null;
}

export interface TimelineView {
  startSeconds: number;
  endSeconds: number;
}

/**
 * 밀리초로 반올림 — 길이 비교의 부동소수 잡음을 없앤다.
 * 이 반올림이 없으면 12.4 - 0.0 같은 계산이 5.000000000000001을 만들어
 * "정확히 5초"가 경계를 통과할지가 입력 경로마다 달라진다.
 */
function roundMs(seconds: number): number {
  return Math.round(seconds * 1000) / 1000;
}

function pad2(n: number): string {
  return String(n).padStart(2, '0');
}

/** 구간 길이(초) — 표시·검사가 같은 값을 보게 한 곳에서 잰다 */
export function rangeLengthSeconds(range: ClipRange): number {
  return roundMs(range.endSeconds - range.startSeconds);
}

/**
 * 구간 핸들 한쪽을 옮긴 결과. 5초~3분을 벗어나면 **클램프가 아니라 거부**다 —
 * 핸들이 3:00에서 더 늘어나지 않고 멈추는 시안 동작이 이 거부에서 나온다.
 * 하한을 클램프로 처리하면 4초를 끌었을 때 5초로 슬쩍 붙어버려,
 * "왜 안 줄어들지"를 사용자가 알 수 없게 된다.
 *
 * 원본 밖으로 나가는 것은 길이 위반이 아니라서 조용히 자른다(clamp).
 */
export function resolveRangeEdge(
  edge: 'start' | 'end',
  requestedSeconds: number,
  range: ClipRange,
  durationSeconds: number,
): RangeEdgeResult {
  const requested = roundMs(Math.min(durationSeconds, Math.max(0, requestedSeconds)));
  const next: ClipRange =
    edge === 'start'
      ? { startSeconds: requested, endSeconds: range.endSeconds }
      : { startSeconds: range.startSeconds, endSeconds: requested };

  const length = rangeLengthSeconds(next);
  if (length < MIN_RANGE_SECONDS) return { range, rejection: 'tooShort' };
  if (length > MAX_RANGE_SECONDS) return { range, rejection: 'tooLong' };
  return { range: next, rejection: null };
}

/**
 * 길이를 십분의 일 초 정수로 — 표기 셋(구간·게이지·트랜스포트)이 모두 이 값에서 나온다.
 * 내림이다: 12.49초를 12.5로 올리면 표기가 실제보다 길어 보인다.
 */
function lengthTenths(seconds: number): number {
  return Math.max(0, Math.floor(roundMs(seconds) * 10));
}

/** 구간 길이 표기 — `0:12.4` (상한이 3분이라 시간 자리는 없다) */
export function formatDurationTenths(seconds: number): string {
  const tenths = lengthTenths(seconds);
  const whole = Math.floor(tenths / 10);
  return `${Math.floor(whole / 60)}:${pad2(whole % 60)}.${tenths % 10}`;
}

/** 트랜스포트의 구간 길이 표기 — `12.4초`. 게이지와 같은 값에서 나와야 어긋나지 않는다 */
export function formatLengthLabel(seconds: number): string {
  const tenths = lengthTenths(seconds);
  return `${Math.floor(tenths / 10)}.${tenths % 10}초`;
}

/**
 * 절대 시각 표기 — `1:22:08.4` (시안 시작·끝 타임코드 박스).
 *
 * 십분의 일 초 정수 하나로 반올림한 뒤 모든 자리를 거기서 파생시킨다.
 * 초와 소수를 따로 반올림하면 재생 tick이 만든 4934.999…에서 초는 14로 남고
 * 소수만 10으로 올라가 `1:22:14.10` 같은 없는 시각이 찍힌다.
 */
export function formatTimecodeTenths(seconds: number): string {
  const tenths = Math.max(0, Math.round(seconds * 10));
  const whole = Math.floor(tenths / 10);
  const h = Math.floor(whole / 3600);
  const m = Math.floor((whole % 3600) / 60);
  return `${h}:${pad2(m)}:${pad2(whole % 60)}.${tenths % 10}`;
}

/** 길이 게이지 표기 — `0:12.4 / 최대 3:00` */
export function formatRangeGauge(lengthSeconds: number): string {
  return `${formatDurationTenths(lengthSeconds)} / 최대 ${MAX_RANGE_LABEL}`;
}

/** 구간 길이가 상한에서 차지하는 비율 0..1 — 게이지 바 폭 */
export function rangeGaugeFraction(lengthSeconds: number): number {
  return Math.min(1, Math.max(0, lengthSeconds / MAX_RANGE_SECONDS));
}

/**
 * 보이는 창 안에서의 위치 0..1. 창 폭이 0이면(줌 계산 전) 0 —
 * 0으로 나눈 NaN이 CSS left로 나가면 요소가 통째로 사라진다(progressFraction과 같은 방어).
 */
export function secondsToFraction(seconds: number, view: TimelineView): number {
  return Math.min(1, Math.max(0, secondsToViewOffset(seconds, view)));
}

/**
 * 보이는 창 기준 위치를 자르지 않고: 창 앞이면 0보다 작고 뒤면 1보다 크다. 키운 창보다 넓은 구간 상자에 쓴다 —
 * 0..1로 자르면 상자가 창 전체가 되어 손잡이가 창 가장자리에 남고, 끌면 경계가 창 끝 시각으로 튄다(PR #341 codex).
 * 창 밖으로 나간 부분은 덮개가 가로로 잘라 감춘다.
 */
export function secondsToViewOffset(seconds: number, view: TimelineView): number {
  const span = view.endSeconds - view.startSeconds;
  if (!(span > 0)) return 0;
  return (seconds - view.startSeconds) / span;
}

/** 보이는 창 안의 위치 0..1 → 초 */
export function fractionToSeconds(fraction: number, view: TimelineView): number {
  const span = view.endSeconds - view.startSeconds;
  const f = Math.min(1, Math.max(0, fraction));
  return roundMs(view.startSeconds + f * span);
}

/**
 * 포인터 좌표 → 초. 폭이 0이면(레이아웃 없음) 계산 불가라 null —
 * 환산 자체는 playerMath.seekFractionFromPointer를 그대로 쓴다(드래그 클램프 포함).
 */
export function secondsFromPointer(
  rect: { left: number; width: number },
  clientX: number,
  view: TimelineView,
): number | null {
  const fraction = seekFractionFromPointer(rect, clientX);
  if (fraction === null) return null;
  return fractionToSeconds(fraction, view);
}

/**
 * 배율의 창 폭(POK-389). 100% = 기본 75초와 구간 × 1.15 중 큰 폭을 잡을 수 있는 범위로 자른 것(손잡이를 범위 끝까지 끌 수 있다).
 * 25% · 50%는 그 4배 · 2배를 범위로 자르고, 200% · 400%는 100% 폭을 나눈다.
 * 🔴 한때 200%를 「기본 75초 ÷ 2」로 잡아 범위가 좁으면(카드 창 19초) 200%까지 다 범위 폭으로 잘려 확대가 안 됐고, 그걸 고치려
 * 100%를 「구간에 맞춤」으로 바꿨더니 이번엔 100%에서 손잡이를 구간의 7.5%밖에 못 끌었다(로컬 리뷰 3라운드). 확대는
 * 「지금 보이는 100% 폭」을 나눠야 둘 다 된다.
 */
export function zoomSpan(
  range: ClipRange,
  zoom: number,
  minSeconds: number,
  maxSeconds: number,
): number {
  const available = Math.max(0, maxSeconds - minSeconds);
  const fit = Math.min(
    available,
    Math.max(BASE_VIEW_SECONDS, (range.endSeconds - range.startSeconds) * 1.15),
  );
  if (zoom >= 100) return (fit * 100) / zoom;
  return Math.min(available, (fit * 100) / zoom);
}

/**
 * 고를 수 있는 가장 낮은 배율. 잡을 수 있는 범위에 막혀 한 단계 줄여도 폭이 그대로면 그 아래는 고를 수 없다:
 * 표시만 바뀌고 화면은 100%와 같다(PR #341 codex)
 */
export function lowestUsefulZoom(range: ClipRange, minSeconds: number, maxSeconds: number): number {
  let index = ZOOM_LEVELS.indexOf(100);
  while (
    index > 0 &&
    zoomSpan(range, ZOOM_LEVELS[index - 1]!, minSeconds, maxSeconds) >
      zoomSpan(range, ZOOM_LEVELS[index]!, minSeconds, maxSeconds) + 1e-6
  )
    index -= 1;
  return ZOOM_LEVELS[index]!;
}

/**
 * 확대 배율에서 보이는 창(POK-389). 폭은 zoomSpan.
 *
 * - 100% 이하: 구간 가운데에 둔다. 구간 손잡이가 늘 보인다.
 * - 100% 초과: 구간보다 좁아질 수 있어(캡컷처럼 진짜 확대) 재생 위치를 따라간다. 앞 창 안에 재생 위치가 있으면 그대로 두고
 *   (누를 때마다 출렁이지 않게), 앞으로 벗어나면 그 자리가 창의 10%에, 뒤로 벗어나면 90%에 오게 넘긴다. 앞 창이 없거나
 *   폭이 바뀌었으면(배율을 바꿨으면) 재생 위치를 가운데에 둔다. 밖으로 나간 손잡이는 화면이 가장자리 표시로 알린다.
 *
 * 어느 쪽이든 잡을 수 있는 범위(min~max) 밖으로는 나가지 않고, 범위가 창보다 좁으면 범위 전체를 보여 준다.
 */
export function zoomedView({
  range,
  playheadSeconds,
  zoom,
  minSeconds,
  maxSeconds,
  previous,
}: {
  range: ClipRange;
  playheadSeconds: number;
  zoom: number;
  minSeconds: number;
  maxSeconds: number;
  /** 바로 앞에 그린 창. 처음이면 null */
  previous: TimelineView | null;
}): TimelineView {
  const span = zoomSpan(range, zoom, minSeconds, maxSeconds);
  const place = (start: number) => {
    const clamped = Math.min(Math.max(minSeconds, start), maxSeconds - span);
    return { startSeconds: roundMs(clamped), endSeconds: roundMs(clamped + span) };
  };
  if (zoom <= 100) return place((range.startSeconds + range.endSeconds) / 2 - span / 2);
  // 앞 창은 양 끝을 ms로 반올림해 두어 폭이 최대 1ms 다를 수 있다. 그보다 엄격하면 재생 틱마다 가운데로 다시 맞춘다
  const sameSpan =
    previous !== null && Math.abs(previous.endSeconds - previous.startSeconds - span) < 0.002;
  if (!sameSpan) return place(playheadSeconds - span / 2);
  if (playheadSeconds > previous.endSeconds) return place(playheadSeconds - span * 0.1);
  if (playheadSeconds < previous.startSeconds) return place(playheadSeconds - span * 0.9);
  return place(previous.startSeconds);
}

/** 줌 한 단계 — 끝에서 더 누르면 그대로 (버튼 비활성 판단도 이 값으로 한다) */
export function zoomStep(zoom: number, direction: 'in' | 'out'): number {
  const index = ZOOM_LEVELS.indexOf(zoom as (typeof ZOOM_LEVELS)[number]);
  if (index === -1) return zoom;
  const next = ZOOM_LEVELS[direction === 'in' ? index + 1 : index - 1];
  return next ?? zoom;
}

/**
 * 타임라인 높이 드래그 — 접기는 별도 상태라 여기선 높이만 자른다.
 *
 * `ceiling`은 화면이 잰 「지금 레이아웃에서 더 커질 수 있는 한계」다. 상수 상한만으로는
 * 부족하다 — 낮은 창에서는 460까지 늘리기 전에 미리보기가 최소 높이에 닿고, 남는 만큼이
 * 타임라인 위로 넘쳐 헤더 버튼을 덮는다 (POK-237). 레이아웃을 아는 쪽만 이 값을 알 수
 * 있어 인자로 받는다 — 재지 못했으면(비유한값) 상수 상한만 쓴다.
 */
export function clampTimelineHeight(px: number, ceiling = MAX_TIMELINE_HEIGHT): number {
  if (!Number.isFinite(px)) return DEFAULT_TIMELINE_HEIGHT;
  const top = Number.isFinite(ceiling)
    ? Math.min(MAX_TIMELINE_HEIGHT, ceiling)
    : MAX_TIMELINE_HEIGHT;
  // 최소 높이가 상한을 이긴다 — 창이 아주 낮아도 타임라인이 사라지지는 않는다
  return Math.round(Math.max(MIN_TIMELINE_HEIGHT, Math.min(top, px)));
}

/** 눈금 글자. 창이 이보다 좁으면 0.1초까지 적는다(초만 적으면 같은 초가 되풀이된다) */
const TENTHS_BELOW_SECONDS = 15;

export function rulerLabel(seconds: number, spanSeconds: number): string {
  // 초 표기도 0.1초 표기도 내림한다: 한쪽만 반올림하면 15초 경계를 넘나들 때 같은 눈금의 초 자리가 바뀌어 튀어 보인다
  const tenths = Math.floor(seconds * 10 + 1e-6);
  const whole = formatUptime(Math.floor(tenths / 10));
  return spanSeconds >= TENTHS_BELOW_SECONDS ? whole : `${whole}.${tenths % 10}`;
}

/**
 * 눈금자 눈금 위치(초). 창을 균등 분할한다:
 * 줌을 바꾸면 눈금도 따라 움직여야 해서 시안 값(1:21:40…1:22:55)을 박아둘 수 없다.
 */
export function rulerTicks(view: TimelineView, count = 6): number[] {
  if (count < 2) return [view.startSeconds];
  const span = view.endSeconds - view.startSeconds;
  return Array.from({ length: count }, (_, i) =>
    roundMs(view.startSeconds + (span * i) / (count - 1)),
  );
}
