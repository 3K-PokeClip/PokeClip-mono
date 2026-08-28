// 시안 1d 클립 편집기 타임라인의 수치·계산.
// 컴포넌트에서 분리한 순수 함수 — 구간 핸들 드래그 환산과 5초~3분 경계는
// jsdom 렌더 테스트로 검증할 수 없어(레이아웃이 없다) 여기서 단위 테스트한다
// (playerMath.ts와 같은 이유).

import { seekFractionFromPointer } from '@/features/player/playerMath';

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

/** 100% 배율에서 타임라인에 보이는 폭 — 시안 눈금자(1:21:40~1:22:55) */
export const BASE_VIEW_SECONDS = 75;

/**
 * 줌 단계 — 시안의 `100%`가 기본값이다.
 * 25%를 두는 이유: 창이 최대 구간(180초)보다 좁으면 포인터 환산이 창에 갇혀
 * 마우스로는 3:00을 만들 수 없다. 75초 기준 25%에서 창이 300초가 되어 그 문이 열린다.
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
  const span = view.endSeconds - view.startSeconds;
  if (!(span > 0)) return 0;
  return Math.min(1, Math.max(0, (seconds - view.startSeconds) / span));
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
 * 줌 배율에서 보이는 창. 중심(플레이헤드·구간)을 가운데 두되 원본 밖으로는 나가지 않는다 —
 * 창이 원본보다 넓으면 원본 전체를 보여준다.
 */
export function viewWindow(
  centerSeconds: number,
  zoom: number,
  durationSeconds: number,
): TimelineView {
  const span = Math.min(durationSeconds, (BASE_VIEW_SECONDS * 100) / zoom);
  const half = span / 2;
  const start = Math.min(Math.max(0, centerSeconds - half), Math.max(0, durationSeconds - span));
  return { startSeconds: roundMs(start), endSeconds: roundMs(start + span) };
}

/** 줌 한 단계 — 끝에서 더 누르면 그대로 (버튼 비활성 판단도 이 값으로 한다) */
export function zoomStep(zoom: number, direction: 'in' | 'out'): number {
  const index = ZOOM_LEVELS.indexOf(zoom as (typeof ZOOM_LEVELS)[number]);
  if (index === -1) return zoom;
  const next = ZOOM_LEVELS[direction === 'in' ? index + 1 : index - 1];
  return next ?? zoom;
}

/** 타임라인 높이 드래그 — 접기는 별도 상태라 여기선 높이만 자른다 */
export function clampTimelineHeight(px: number): number {
  if (!Number.isFinite(px)) return DEFAULT_TIMELINE_HEIGHT;
  return Math.round(Math.min(MAX_TIMELINE_HEIGHT, Math.max(MIN_TIMELINE_HEIGHT, px)));
}

/**
 * 눈금자 눈금 위치(초). 창을 균등 분할한다 —
 * 줌을 바꾸면 눈금도 따라 움직여야 해서 시안 값(1:21:40…1:22:55)을 박아둘 수 없다.
 */
export function rulerTicks(view: TimelineView, count = 6): number[] {
  if (count < 2) return [view.startSeconds];
  const span = view.endSeconds - view.startSeconds;
  return Array.from({ length: count }, (_, i) =>
    roundMs(view.startSeconds + (span * i) / (count - 1)),
  );
}
