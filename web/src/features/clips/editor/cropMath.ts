// 미리보기 크롭 좌표의 계산 (POK-109).
//
// 소스는 가로(16:9)인데 내보내는 것은 세로(9:16)나 정사각이다. 그래서 소스의 **어느 부분**을
// 쓸지 골라야 하고, 그 선택이 계약6 `outputs[].crop` 으로 저장된다.
//
// 값의 정본은 계약6 2절이다:
//  · 정규화 좌표 — x,y ∈ [0,1) · w,h ≥ 0.05 · x+w ≤ 1 · y+h ≤ 1
//  · 종횡비 일치는 **픽셀 기준**이다: (w × srcW) / (h × srcH) 가 목표 비율과 ±1% 이내.
//    정규화 값끼리의 비가 아니다 — 소스가 정사각이 아니면 둘이 다르다.
//  · ±1% 잔차는 렌더가 중심 유지 축소로 흡수하지만, **흡수가 일어나지 않게 맞추는 것까지가 UI 몫**이다.
//    그래서 여기서는 목표 비율을 정확히 만족하는 크기를 계산한다(오차 0).
//
// 컴포넌트에서 분리한 이유는 늘 같다 — jsdom 엔 레이아웃이 없어 드래그를 렌더 테스트로 못 재는데,
// 환산과 경계는 표로 검증할 수 있다 (timelineMath·playerMath 와 같은 결).

import type { EditorLayout } from './useClipEditorMockState';

/** 계약6 `outputs[].crop` 과 같은 모양. 렌더가 그대로 받는다 */
export interface CropRect {
  x: number;
  y: number;
  w: number;
  h: number;
}

/**
 * 사용자가 드래그로 옮기는 값 — 크롭 창의 **중심**(0..1).
 *
 * 왼쪽 위 모서리가 아니라 중심을 저장하는 이유: 비율을 9:16 ↔ 1:1 로 바꾸면 창 크기가 달라지는데,
 * 모서리를 저장해 두면 넓은 쪽으로 바꿀 때 프레이밍이 한쪽으로 밀린다. 중심은 그대로 남는다.
 */
export interface CropCenter {
  x: number;
  y: number;
}

export const DEFAULT_CROP_CENTER: CropCenter = { x: 0.5, y: 0.5 };

/** 계약6 2절 — 극소 crop 의 무의미한 확대를 막는 하한 */
export const MIN_CROP_SIZE = 0.05;

/** 키보드 한 걸음 — 소스 폭의 1% */
export const CROP_KEY_STEP = 0.01;

function clamp(value: number, min: number, max: number): number {
  // min > max 인 경우(창이 소스보다 크다)는 min 을 준다 — 잘라낼 여유가 없다는 뜻이다
  return Math.min(Math.max(value, min), Math.max(min, max));
}

/**
 * 미리보기 한 칸의 목표 비율(가로/세로).
 *
 * 프레임은 9:16(또는 1:1)이고, 상하분할이면 그 프레임을 splitRatio 로 나눠 가진다 —
 * 그래서 칸의 비율은 프레임 비율을 세로 지분으로 나눈 값이다.
 * (CSS 의 `.frame[data-layout]` 과 `flex` 지분이 정본이고, 여기는 그 사본이다.)
 */
export function paneAspect(layout: EditorLayout, splitRatio: number, paneIndex: number): number {
  if (layout === '1:1') return 1;
  const frameAspect = 9 / 16;
  if (layout !== 'split') return frameAspect;
  const total = splitRatio + 1;
  const share = paneIndex === 0 ? splitRatio / total : 1 / total;
  if (!(share > 0)) return frameAspect;
  return frameAspect / share;
}

/**
 * 목표 비율을 소스에서 잘라낼 때의 창 크기(정규화).
 *
 * 목표가 소스보다 홀쭉하면 세로를 다 쓰고 가로를 자른다(우리 화면 전부가 이 경우다).
 * 반대면 그 반대 — 나중에 가로 소스가 아닌 것이 와도 식이 성립한다.
 */
export function cropSizeFor(
  targetAspect: number,
  sourceWidth: number,
  sourceHeight: number,
): { w: number; h: number } {
  if (!(targetAspect > 0) || !(sourceWidth > 0) || !(sourceHeight > 0)) return { w: 1, h: 1 };
  // h = 1 로 두고 픽셀 종횡비를 맞춘다: (w·W)/(1·H) = target
  const w = (targetAspect * sourceHeight) / sourceWidth;
  if (w <= 1) return { w: Math.max(MIN_CROP_SIZE, w), h: 1 };
  // 가로가 모자라면 가로를 다 쓰고 세로를 자른다
  const h = sourceWidth / (targetAspect * sourceHeight);
  return { w: 1, h: Math.max(MIN_CROP_SIZE, Math.min(1, h)) };
}

/** 중심 + 창 크기 → 계약6 crop. 소스 밖으로 나가지 않게 자른다 */
export function cropRectOf(center: CropCenter, size: { w: number; h: number }): CropRect {
  const { w, h } = size;
  return {
    x: clamp(center.x - w / 2, 0, 1 - w),
    y: clamp(center.y - h / 2, 0, 1 - h),
    w,
    h,
  };
}

/**
 * 중심을 옮긴다. 창이 소스 밖으로 나가지 않는 범위로 중심 자체를 가둔다 —
 * 사각형만 자르면 가장자리에서 계속 끌 때 중심이 저 멀리 쌓여, 되돌아올 때 그만큼 헛돈다.
 */
export function moveCropCenter(
  center: CropCenter,
  delta: { x: number; y: number },
  size: { w: number; h: number },
): CropCenter {
  return {
    x: clamp(center.x + delta.x, size.w / 2, 1 - size.w / 2),
    y: clamp(center.y + delta.y, size.h / 2, 1 - size.h / 2),
  };
}

/**
 * 포인터가 움직인 픽셀 → 중심이 움직일 정규화 값.
 *
 * 부호가 뒤집힌다: 오른쪽으로 끌면 영상이 오른쪽으로 따라오고, 그것은 크롭 창이 **왼쪽**으로
 * 간다는 뜻이다. 칸의 폭이 소스의 `w` 만큼을 보여주므로 1px 은 `w/칸폭` 만큼의 소스다.
 */
export function cropCenterDelta(
  pointerDelta: { x: number; y: number },
  paneSize: { width: number; height: number },
  size: { w: number; h: number },
): { x: number; y: number } {
  return {
    x: paneSize.width > 0 ? (-pointerDelta.x / paneSize.width) * size.w : 0,
    y: paneSize.height > 0 ? (-pointerDelta.y / paneSize.height) * size.h : 0,
  };
}

/**
 * 크롭을 CSS `object-position` 으로. `object-fit: cover` 가 넘치게 그린 뒤 이 값이 어디를 보여줄지 정한다.
 *
 * 백분율의 뜻이 「넘친 양 중 얼마나 왼쪽을 자를까」라서, 남는 여유(1-w)에 대한 x 의 비율이 그대로 답이다.
 * 여유가 없으면(창이 소스 전체) 가운데로 둔다 — 0으로 나누지 않기 위해서이기도 하다.
 */
export function cropObjectPosition(rect: CropRect): { x: number; y: number } {
  const freeX = 1 - rect.w;
  const freeY = 1 - rect.h;
  return {
    x: freeX > 0 ? (rect.x / freeX) * 100 : 50,
    y: freeY > 0 ? (rect.y / freeY) * 100 : 50,
  };
}

/** 이 칸에서 실제로 움직일 수 있는 축. 둘 다 여유가 없으면 null(조작할 것이 없다) */
export function cropFreeAxis(size: { w: number; h: number }): 'x' | 'y' | null {
  const freeX = 1 - size.w;
  const freeY = 1 - size.h;
  // 부동소수 잡음(0.9999…)을 여유로 오해하지 않도록 눈에 보일 만한 값부터 센다
  const meaningful = 0.001;
  if (freeX > meaningful && freeX >= freeY) return 'x';
  if (freeY > meaningful) return 'y';
  return null;
}
