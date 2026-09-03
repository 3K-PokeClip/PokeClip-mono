// 크롭 영역의 계산 (POK-109).
//
// 편집자는 **소스 전체를 보면서 그 위의 사각형을 잡는다**. 사각형을 끌면 위치가, 모서리를 끌면
// 범위가 바뀌고, 그 사각형이 곧 계약6 `outputs[].crop` 이다.
//
// 값의 정본은 계약6 2절이다:
//  · 정규화 좌표 — x,y ∈ [0,1) · w,h ≥ 0.05 · x+w ≤ 1 · y+h ≤ 1
//  · 종횡비 일치는 **픽셀 기준**이다: (w × srcW) / (h × srcH) 가 목표 비율과 ±1% 이내.
//    정규화 값끼리의 비가 아니다 — 소스가 정사각이 아니면 둘이 다르다.
//  · ±1% 잔차는 렌더가 중심 유지 축소로 흡수하지만, **흡수가 일어나지 않게 맞추는 것까지가 UI 몫**이다.
//    그래서 사각형은 늘 목표 비율에 묶여 있다 — 모서리를 끌어도 비율은 안 바뀌고 크기만 바뀐다.
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

export interface CropCenter {
  x: number;
  y: number;
}

/**
 * 사용자가 잡는 값.
 *
 * 사각형을 `{x,y,w,h}` 로 바로 담지 않는 이유: 비율을 9:16 ↔ 1:1 로 바꾸면 같은 사각형이
 * 비율 규칙을 어기게 된다. **중심과 확대율**로 담아 두면 비율이 바뀔 때 규칙에 맞는 사각형을
 * 다시 만들 수 있고, 보고 있던 자리와 얼마나 당겨 봤는지는 그대로 남는다.
 */
export interface CropWindow {
  center: CropCenter;
  /** 그 비율로 잡을 수 있는 최대 사각형 대비 크기. 1 = 최대(가장 넓게), 작을수록 확대 */
  zoom: number;
}

/** 계약6 2절 — 극소 crop 의 무의미한 확대를 막는 하한 */
export const MIN_CROP_SIZE = 0.05;

/** 키보드 한 걸음 — 소스 기준 1% */
export const CROP_KEY_STEP = 0.01;
/** 모서리 키보드 한 걸음 — 확대율 5% */
export const CROP_ZOOM_STEP = 0.05;

export type CropCorner = 'nw' | 'ne' | 'sw' | 'se';
export const CROP_CORNERS: readonly CropCorner[] = ['nw', 'ne', 'sw', 'se'];

/** 모서리 이름 — 화면이 읽어 줄 말 */
export const CROP_CORNER_LABELS: Readonly<Record<CropCorner, string>> = {
  nw: '왼쪽 위',
  ne: '오른쪽 위',
  sw: '왼쪽 아래',
  se: '오른쪽 아래',
};

function clamp(value: number, min: number, max: number): number {
  // min > max 인 경우(창이 소스보다 크다)는 min 을 준다 — 잘라낼 여유가 없다는 뜻이다
  return Math.min(Math.max(value, min), Math.max(min, max));
}

/**
 * 미리보기 한 칸의 목표 비율(가로/세로).
 *
 * 내보내는 화면은 9:16(또는 1:1)이고, 상하분할이면 그 화면을 splitRatio 로 나눠 가진다 —
 * 그래서 칸의 비율은 화면 비율을 세로 지분으로 나눈 값이다.
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
 * 그 비율로 소스에서 잘라낼 수 있는 **가장 큰** 사각형(정규화).
 *
 * 목표가 소스보다 홀쭉하면 세로를 다 쓰고 가로를 자른다(우리 화면 전부가 이 경우다).
 * 반대면 그 반대 — 나중에 가로 소스가 아닌 것이 와도 식이 성립한다.
 */
export function maxCropSize(
  targetAspect: number,
  sourceWidth: number,
  sourceHeight: number,
): { w: number; h: number } {
  if (!(targetAspect > 0) || !(sourceWidth > 0) || !(sourceHeight > 0)) return { w: 1, h: 1 };
  // h = 1 로 두고 픽셀 종횡비를 맞춘다: (w·W)/(1·H) = target
  const w = (targetAspect * sourceHeight) / sourceWidth;
  if (w <= 1) return { w, h: 1 };
  // 가로가 모자라면 가로를 다 쓰고 세로를 자른다
  return { w: 1, h: sourceWidth / (targetAspect * sourceHeight) };
}

/** 계약6 하한(0.05)을 지키는 최소 확대율. 최대 사각형이 작을수록 더 못 줄인다 */
export function minZoomOf(maxSize: { w: number; h: number }): number {
  const byWidth = maxSize.w > 0 ? MIN_CROP_SIZE / maxSize.w : 1;
  const byHeight = maxSize.h > 0 ? MIN_CROP_SIZE / maxSize.h : 1;
  return Math.min(1, Math.max(byWidth, byHeight));
}

/** 확대율을 적용한 사각형 크기 */
export function cropSizeOf(maxSize: { w: number; h: number }, zoom: number): { w: number; h: number } {
  const z = clamp(zoom, minZoomOf(maxSize), 1);
  return { w: maxSize.w * z, h: maxSize.h * z };
}

/** 창 + 최대 크기 → 계약6 crop. 소스 밖으로 나가지 않게 자른다 */
export function cropRectOf(window: CropWindow, maxSize: { w: number; h: number }): CropRect {
  const { w, h } = cropSizeOf(maxSize, window.zoom);
  return {
    x: clamp(window.center.x - w / 2, 0, 1 - w),
    y: clamp(window.center.y - h / 2, 0, 1 - h),
    w,
    h,
  };
}

/**
 * 사각형을 옮긴다. 소스 밖으로 나가지 않는 범위로 중심 자체를 가둔다 —
 * 사각형만 자르면 가장자리에서 계속 끌 때 중심이 저 멀리 쌓여, 되돌아올 때 그만큼 헛돈다.
 */
export function moveCropWindow(
  window: CropWindow,
  delta: { x: number; y: number },
  maxSize: { w: number; h: number },
): CropWindow {
  const { w, h } = cropSizeOf(maxSize, window.zoom);
  return {
    zoom: window.zoom,
    center: {
      x: clamp(window.center.x + delta.x, w / 2, 1 - w / 2),
      y: clamp(window.center.y + delta.y, h / 2, 1 - h / 2),
    },
  };
}

/** 확대율만 바꾼다 — 중심은 그대로 두되 소스 밖으로 나가면 끌어들인다 */
export function zoomCropWindow(
  window: CropWindow,
  nextZoom: number,
  maxSize: { w: number; h: number },
): CropWindow {
  const zoom = clamp(nextZoom, minZoomOf(maxSize), 1);
  return moveCropWindow({ center: window.center, zoom }, { x: 0, y: 0 }, maxSize);
}

/** 어느 모서리를 잡았을 때 고정되는 반대편 모서리의 좌표 */
function anchorOf(rect: CropRect, corner: CropCorner): { x: number; y: number } {
  return {
    x: corner === 'nw' || corner === 'sw' ? rect.x + rect.w : rect.x,
    y: corner === 'nw' || corner === 'ne' ? rect.y + rect.h : rect.y,
  };
}

/**
 * 모서리를 끌어 범위를 바꾼다. **반대편 모서리를 못 박고** 비율은 유지한다 —
 * 비율이 흔들리면 계약6의 종횡비 검증에 걸려 렌더가 거부한다.
 *
 * `pointer` 는 소스 안의 정규화 좌표다(0..1). 두 축 중 더 많이 끈 쪽을 따라간다 —
 * 비율이 묶여 있어 한 축만 봐도 되지만, 그러면 세로로 끄는 손짓에 반응하지 않는다.
 */
export function resizeCropWindow(
  window: CropWindow,
  corner: CropCorner,
  pointer: { x: number; y: number },
  maxSize: { w: number; h: number },
): CropWindow {
  const rect = cropRectOf(window, maxSize);
  const anchor = anchorOf(rect, corner);
  if (!(maxSize.w > 0) || !(maxSize.h > 0)) return window;

  const west = corner === 'nw' || corner === 'sw';
  const north = corner === 'nw' || corner === 'ne';
  // **부호를 살려서** 잰다. 절댓값으로 재면 고정점을 지나쳐 끌었을 때 반대 방향 거리를 크기로
  // 오해해서, 작게 만들려고 끌었는데 되레 커진다.
  const reachX = west ? anchor.x - pointer.x : pointer.x - anchor.x;
  const reachY = north ? anchor.y - pointer.y : pointer.y - anchor.y;
  const wanted = Math.max(
    Math.max(0, reachX) / maxSize.w,
    Math.max(0, reachY) / maxSize.h,
  );
  // 고정한 모서리에서 소스 경계까지 남은 만큼이 상한이다 — 그래야 고정점이 안 움직인다
  const availableX = west ? anchor.x : 1 - anchor.x;
  const availableY = north ? anchor.y : 1 - anchor.y;
  const ceiling = Math.min(1, availableX / maxSize.w, availableY / maxSize.h);
  const floor = minZoomOf(maxSize);
  const zoom = clamp(wanted, floor, Math.max(floor, ceiling));

  const { w, h } = cropSizeOf(maxSize, zoom);
  return {
    zoom,
    center: {
      x: west ? anchor.x - w / 2 : anchor.x + w / 2,
      y: north ? anchor.y - h / 2 : anchor.y + h / 2,
    },
  };
}

/** 소스 판 안의 픽셀 좌표 → 정규화 좌표 */
export function normalizePointer(
  clientPoint: { x: number; y: number },
  panel: { left: number; top: number; width: number; height: number },
): { x: number; y: number } {
  return {
    x: panel.width > 0 ? clamp((clientPoint.x - panel.left) / panel.width, 0, 1) : 0.5,
    y: panel.height > 0 ? clamp((clientPoint.y - panel.top) / panel.height, 0, 1) : 0.5,
  };
}

/**
 * 소스 판에서 끈 픽셀 → 정규화 이동량.
 *
 * 소스를 통째로 보여주는 판이라 환산이 1:1이다 — 사각형이 손을 그대로 따라온다.
 * (영상을 끄는 방식이었을 때는 부호가 뒤집히고 확대율이 끼었다.)
 */
export function pointerDeltaToCrop(
  pointerDelta: { x: number; y: number },
  panelSize: { width: number; height: number },
): { x: number; y: number } {
  return {
    x: panelSize.width > 0 ? pointerDelta.x / panelSize.width : 0,
    y: panelSize.height > 0 ? pointerDelta.y / panelSize.height : 0,
  };
}

/**
 * 처음 열었을 때의 사각형.
 *
 * 상하분할은 위·아래 두 영역을 **같은 소스에서** 잡는다(게임 화면과 캠이 한 화면에 합성돼 온다).
 * 그래서 기본값을 위·아래로 갈라 둔다 — 둘 다 한가운데면 완전히 겹쳐서 뭘 잡은 건지 안 보인다.
 */
export function defaultCropWindow(layout: EditorLayout, paneIndex: number): CropWindow {
  if (layout !== 'split') return { center: { x: 0.5, y: 0.5 }, zoom: 1 };
  return paneIndex === 0
    ? { center: { x: 0.5, y: 0.25 }, zoom: 0.5 }
    : { center: { x: 0.5, y: 0.75 }, zoom: 0.5 };
}
