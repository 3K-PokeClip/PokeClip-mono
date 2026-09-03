import { describe, expect, it } from 'vitest';
import {
  MIN_CROP_SIZE,
  cropRectOf,
  cropSizeOf,
  defaultCropWindow,
  maxCropSize,
  minZoomOf,
  moveCropWindow,
  normalizePointer,
  paneAspect,
  pointerDeltaToCrop,
  resizeCropWindow,
  zoomCropWindow,
  type CropWindow,
} from './cropMath';

// 계약6 2절이 정본이다 — 정규화 좌표, w·h ≥ 0.05, x+w ≤ 1, 종횡비는 **픽셀 기준**.
// 렌더가 ±1% 잔차를 흡수하지만 흡수가 일어나지 않게 맞추는 것까지가 UI 몫이라 오차 0을 노린다.

const SRC = { width: 1920, height: 1080 };
const FULL: CropWindow = { center: { x: 0.5, y: 0.5 }, zoom: 1 };

/** 계약6의 픽셀 기준 종횡비 — 정규화 값끼리의 비가 아니다 */
function pixelAspect(size: { w: number; h: number }): number {
  return (size.w * SRC.width) / (size.h * SRC.height);
}

describe('paneAspect', () => {
  it('9:16과 1:1은 내보내는 화면 비율 그대로다', () => {
    expect(paneAspect('9:16', 1.5, 0)).toBeCloseTo(9 / 16, 10);
    expect(paneAspect('1:1', 1.5, 0)).toBe(1);
  });

  it('상하분할은 화면을 세로 지분으로 나눠 가진다', () => {
    // 시안 1.5 : 1 → 위 60%, 아래 40%
    expect(paneAspect('split', 1.5, 0)).toBeCloseTo(9 / 16 / 0.6, 10);
    expect(paneAspect('split', 1.5, 1)).toBeCloseTo(9 / 16 / 0.4, 10);
  });

  it('아래 칸이 위보다 항상 납작하다 — 지분이 작으면 비율이 커진다', () => {
    expect(paneAspect('split', 1.5, 1)).toBeGreaterThan(paneAspect('split', 1.5, 0));
  });
});

describe('maxCropSize', () => {
  it('9:16은 세로를 다 쓰고 가로를 자른다 — 계약6 예시와 같다', () => {
    const size = maxCropSize(9 / 16, SRC.width, SRC.height);
    expect(size.h).toBe(1);
    // 계약6 예시의 VERT_9_16 crop w = 0.316
    expect(size.w).toBeCloseTo(0.31640625, 8);
    expect(pixelAspect(size)).toBeCloseTo(9 / 16, 10);
  });

  it('1:1도 세로를 다 쓴다 — 계약6 예시의 0.5625', () => {
    const size = maxCropSize(1, SRC.width, SRC.height);
    expect(size.h).toBe(1);
    expect(size.w).toBeCloseTo(0.5625, 8);
  });

  it('상하분할 두 칸 모두 픽셀 종횡비가 정확히 맞는다', () => {
    for (const index of [0, 1]) {
      const aspect = paneAspect('split', 1.5, index);
      const size = maxCropSize(aspect, SRC.width, SRC.height);
      expect(pixelAspect(size)).toBeCloseTo(aspect, 10);
      expect(size.w).toBeLessThanOrEqual(1);
      expect(size.h).toBeLessThanOrEqual(1);
    }
  });

  it('소스보다 납작한 목표는 가로를 다 쓰고 세로를 자른다', () => {
    const size = maxCropSize(21 / 9, SRC.width, SRC.height);
    expect(size.w).toBe(1);
    expect(size.h).toBeLessThan(1);
    expect(pixelAspect(size)).toBeCloseTo(21 / 9, 10);
  });

  it('소스 크기를 모르면 전체를 쓴다 — 0으로 나누지 않는다', () => {
    expect(maxCropSize(9 / 16, 0, 0)).toEqual({ w: 1, h: 1 });
  });
});

describe('cropSizeOf · minZoomOf', () => {
  const max = maxCropSize(9 / 16, SRC.width, SRC.height);

  it('확대율이 크기에 그대로 곱해진다 — 비율은 그대로다', () => {
    const half = cropSizeOf(max, 0.5);
    expect(half.w).toBeCloseTo(max.w / 2, 10);
    expect(half.h).toBeCloseTo(0.5, 10);
    expect(pixelAspect(half)).toBeCloseTo(9 / 16, 10);
  });

  it('계약6 하한(0.05) 아래로는 못 줄인다', () => {
    const tiny = cropSizeOf(max, 0.0001);
    expect(Math.min(tiny.w, tiny.h)).toBeGreaterThanOrEqual(MIN_CROP_SIZE - 1e-12);
    expect(minZoomOf(max)).toBeCloseTo(MIN_CROP_SIZE / max.w, 10);
  });

  it('1보다 크게는 못 늘린다 — 소스 밖을 잡을 수 없다', () => {
    expect(cropSizeOf(max, 3)).toEqual(max);
  });
});

describe('cropRectOf', () => {
  const max = maxCropSize(9 / 16, SRC.width, SRC.height);

  it('가운데 중심이면 좌우가 똑같이 남는다', () => {
    const rect = cropRectOf(FULL, max);
    expect(rect.x).toBeCloseTo((1 - max.w) / 2, 10);
    expect(rect.y).toBe(0);
  });

  it('계약6 기하 규칙을 지킨다 — x+w ≤ 1, y+h ≤ 1', () => {
    for (const center of [
      { x: -5, y: -5 },
      { x: 5, y: 5 },
      { x: 0.5, y: 0.5 },
    ]) {
      for (const zoom of [0.1, 0.5, 1]) {
        const rect = cropRectOf({ center, zoom }, max);
        expect(rect.x).toBeGreaterThanOrEqual(0);
        expect(rect.y).toBeGreaterThanOrEqual(0);
        expect(rect.x + rect.w).toBeLessThanOrEqual(1 + 1e-12);
        expect(rect.y + rect.h).toBeLessThanOrEqual(1 + 1e-12);
      }
    }
  });
});

describe('moveCropWindow', () => {
  const max = maxCropSize(9 / 16, SRC.width, SRC.height);

  it('중심을 그만큼 옮긴다 — 확대율은 그대로', () => {
    const moved = moveCropWindow(FULL, { x: 0.1, y: 0 }, max);
    expect(moved.center.x).toBeCloseTo(0.6, 10);
    expect(moved.zoom).toBe(1);
  });

  it('가장자리를 넘으면 중심 자체를 가둔다 — 되돌아올 때 헛돌지 않게', () => {
    const far = moveCropWindow(FULL, { x: 99, y: 0 }, max);
    expect(far.center.x).toBeCloseTo(1 - max.w / 2, 10);
    const back = moveCropWindow(far, { x: -0.05, y: 0 }, max);
    expect(back.center.x).toBeCloseTo(1 - max.w / 2 - 0.05, 10);
  });

  it('세로 여유가 없으면 위아래로는 안 움직인다', () => {
    const moved = moveCropWindow(FULL, { x: 0, y: 0.3 }, max);
    expect(moved.center.y).toBe(0.5);
  });

  it('당겨 보면 세로로도 움직인다 — 여유가 생긴다', () => {
    const zoomed = { center: { x: 0.5, y: 0.5 }, zoom: 0.5 };
    const moved = moveCropWindow(zoomed, { x: 0, y: 0.2 }, max);
    expect(moved.center.y).toBeCloseTo(0.7, 10);
  });
});

describe('zoomCropWindow', () => {
  const max = maxCropSize(9 / 16, SRC.width, SRC.height);

  it('확대율을 바꾸고 중심은 그대로 둔다', () => {
    const zoomed = zoomCropWindow(FULL, 0.5, max);
    expect(zoomed.zoom).toBe(0.5);
    expect(zoomed.center.x).toBeCloseTo(0.5, 10);
  });

  it('넓히다 소스 밖으로 나가면 사각형을 끌어들인다', () => {
    // 오른쪽 끝에 붙여 두고 확대율을 최대로 올리면 중심이 안으로 들어와야 한다
    const atEdge = moveCropWindow({ center: { x: 0.5, y: 0.5 }, zoom: 0.2 }, { x: 99, y: 0 }, max);
    const widened = zoomCropWindow(atEdge, 1, max);
    const rect = cropRectOf(widened, max);
    expect(rect.x + rect.w).toBeLessThanOrEqual(1 + 1e-12);
  });

  it('경계를 벗어난 확대율은 잘린다', () => {
    expect(zoomCropWindow(FULL, 5, max).zoom).toBe(1);
    expect(zoomCropWindow(FULL, -1, max).zoom).toBeCloseTo(minZoomOf(max), 10);
  });
});

describe('resizeCropWindow', () => {
  const max = maxCropSize(9 / 16, SRC.width, SRC.height);
  const start: CropWindow = { center: { x: 0.5, y: 0.5 }, zoom: 0.5 };

  it('반대편 모서리가 고정된다 — 비율도 그대로', () => {
    const before = cropRectOf(start, max);
    // 오른쪽 아래를 끌면 왼쪽 위가 고정
    const next = resizeCropWindow(start, 'se', { x: 0.9, y: 0.9 }, max);
    const after = cropRectOf(next, max);

    expect(after.x).toBeCloseTo(before.x, 8);
    expect(after.y).toBeCloseTo(before.y, 8);
    expect(pixelAspect(after)).toBeCloseTo(9 / 16, 8);
    expect(after.w).toBeGreaterThan(before.w);
  });

  it('왼쪽 위를 끌면 오른쪽 아래가 고정된다', () => {
    const before = cropRectOf(start, max);
    const next = resizeCropWindow(start, 'nw', { x: 0.1, y: 0.1 }, max);
    const after = cropRectOf(next, max);

    expect(after.x + after.w).toBeCloseTo(before.x + before.w, 8);
    expect(after.y + after.h).toBeCloseTo(before.y + before.h, 8);
  });

  it('고정한 모서리 쪽으로 끌면 작아진다 — 하한까지', () => {
    const next = resizeCropWindow(start, 'se', { x: 0.5, y: 0.5 }, max);
    expect(next.zoom).toBeLessThan(start.zoom);
    const tiny = resizeCropWindow(start, 'se', { x: 0, y: 0 }, max);
    expect(tiny.zoom).toBeCloseTo(minZoomOf(max), 10);
  });

  it('소스 밖으로는 못 넓힌다 — 고정점이 움직이지 않는다', () => {
    const before = cropRectOf(start, max);
    const next = resizeCropWindow(start, 'se', { x: 9, y: 9 }, max);
    const after = cropRectOf(next, max);

    expect(after.x).toBeCloseTo(before.x, 8);
    expect(after.x + after.w).toBeLessThanOrEqual(1 + 1e-12);
    expect(after.y + after.h).toBeLessThanOrEqual(1 + 1e-12);
  });

  it('두 축 중 더 많이 끈 쪽을 따라간다', () => {
    // 세로로만 크게 끌어도 사각형이 커져야 한다 (비율이 묶여 있어 가로도 함께 커진다)
    const next = resizeCropWindow(start, 'se', { x: 0.5, y: 0.99 }, max);
    expect(next.zoom).toBeGreaterThan(start.zoom);
  });
});

describe('normalizePointer · pointerDeltaToCrop', () => {
  const panel = { left: 100, top: 50, width: 800, height: 450 };

  it('판 안의 픽셀을 0..1로 바꾼다', () => {
    expect(normalizePointer({ x: 500, y: 275 }, panel)).toEqual({ x: 0.5, y: 0.5 });
  });

  it('판 밖은 경계로 자른다', () => {
    expect(normalizePointer({ x: -999, y: 9999 }, panel)).toEqual({ x: 0, y: 1 });
  });

  it('끈 픽셀이 판 크기에 대한 비율로 그대로 간다 — 사각형이 손을 따라온다', () => {
    const delta = pointerDeltaToCrop({ x: 80, y: 45 }, panel);
    expect(delta.x).toBeCloseTo(0.1, 10);
    expect(delta.y).toBeCloseTo(0.1, 10);
  });

  it('판 크기를 모르면 안 움직인다 — 0으로 나누지 않는다', () => {
    expect(pointerDeltaToCrop({ x: 100, y: 100 }, { width: 0, height: 0 })).toEqual({ x: 0, y: 0 });
  });
});

describe('defaultCropWindow', () => {
  it('단일 화면은 소스 한가운데를 가장 넓게 잡는다', () => {
    expect(defaultCropWindow('9:16', 0)).toEqual({ center: { x: 0.5, y: 0.5 }, zoom: 1 });
  });

  it('상하분할은 한 소스를 위·아래로 갈라 잡는다 — 겹쳐 있으면 뭘 잡았는지 안 보인다', () => {
    const top = defaultCropWindow('split', 0);
    const bottom = defaultCropWindow('split', 1);
    expect(top.center.y).toBeLessThan(bottom.center.y);

    const topRect = cropRectOf(top, maxCropSize(paneAspect('split', 1.5, 0), SRC.width, SRC.height));
    const bottomRect = cropRectOf(
      bottom,
      maxCropSize(paneAspect('split', 1.5, 1), SRC.width, SRC.height),
    );
    // 위 사각형이 끝나는 자리에서 아래 사각형이 시작한다
    expect(topRect.y + topRect.h).toBeLessThanOrEqual(bottomRect.y + 1e-12);
  });
});
