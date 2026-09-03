import { describe, expect, it } from 'vitest';
import {
  DEFAULT_CROP_CENTER,
  MIN_CROP_SIZE,
  cropCenterDelta,
  cropFreeAxis,
  cropObjectPosition,
  cropRectOf,
  cropSizeFor,
  moveCropCenter,
  paneAspect,
} from './cropMath';

// 계약6 2절이 정본이다 — 정규화 좌표, w·h ≥ 0.05, x+w ≤ 1, 종횡비는 **픽셀 기준**.
// 렌더가 ±1% 잔차를 흡수하지만 흡수가 일어나지 않게 맞추는 것까지가 UI 몫이라 오차 0을 노린다.

const SRC = { width: 1920, height: 1080 };

/** 계약6의 픽셀 기준 종횡비 — 정규화 값끼리의 비가 아니다 */
function pixelAspect(size: { w: number; h: number }): number {
  return (size.w * SRC.width) / (size.h * SRC.height);
}

describe('paneAspect', () => {
  it('9:16과 1:1은 프레임 비율 그대로다', () => {
    expect(paneAspect('9:16', 1.5, 0)).toBeCloseTo(9 / 16, 10);
    expect(paneAspect('1:1', 1.5, 0)).toBe(1);
  });

  it('상하분할은 프레임을 세로 지분으로 나눠 가진다', () => {
    // 시안 1.5 : 1 → 위 60%, 아래 40%
    expect(paneAspect('split', 1.5, 0)).toBeCloseTo(9 / 16 / 0.6, 10);
    expect(paneAspect('split', 1.5, 1)).toBeCloseTo(9 / 16 / 0.4, 10);
  });

  it('아래 칸이 위보다 항상 납작하다 — 지분이 작으면 비율이 커진다', () => {
    expect(paneAspect('split', 1.5, 1)).toBeGreaterThan(paneAspect('split', 1.5, 0));
  });
});

describe('cropSizeFor', () => {
  it('9:16은 세로를 다 쓰고 가로를 자른다 — 계약6 예시와 같다', () => {
    const size = cropSizeFor(9 / 16, SRC.width, SRC.height);
    expect(size.h).toBe(1);
    // 계약6 예시의 VERT_9_16 crop w = 0.316
    expect(size.w).toBeCloseTo(0.31640625, 8);
    expect(pixelAspect(size)).toBeCloseTo(9 / 16, 10);
  });

  it('1:1도 세로를 다 쓴다 — 계약6 예시의 0.5625', () => {
    const size = cropSizeFor(1, SRC.width, SRC.height);
    expect(size.h).toBe(1);
    expect(size.w).toBeCloseTo(0.5625, 8);
    expect(pixelAspect(size)).toBeCloseTo(1, 10);
  });

  it('상하분할 두 칸 모두 픽셀 종횡비가 정확히 맞는다', () => {
    for (const index of [0, 1]) {
      const aspect = paneAspect('split', 1.5, index);
      const size = cropSizeFor(aspect, SRC.width, SRC.height);
      expect(pixelAspect(size)).toBeCloseTo(aspect, 10);
      expect(size.w).toBeLessThanOrEqual(1);
      expect(size.h).toBeLessThanOrEqual(1);
    }
  });

  it('소스보다 납작한 목표는 가로를 다 쓰고 세로를 자른다', () => {
    const size = cropSizeFor(21 / 9, SRC.width, SRC.height);
    expect(size.w).toBe(1);
    expect(size.h).toBeLessThan(1);
    expect(pixelAspect(size)).toBeCloseTo(21 / 9, 10);
  });

  it('계약6 하한(0.05) 아래로는 안 내려간다', () => {
    const size = cropSizeFor(0.01, SRC.width, SRC.height);
    expect(size.w).toBeGreaterThanOrEqual(MIN_CROP_SIZE);
  });

  it('소스 크기를 모르면 전체를 쓴다 — 0으로 나누지 않는다', () => {
    expect(cropSizeFor(9 / 16, 0, 0)).toEqual({ w: 1, h: 1 });
  });
});

describe('cropRectOf', () => {
  const size = cropSizeFor(9 / 16, SRC.width, SRC.height);

  it('가운데 중심이면 좌우가 똑같이 남는다', () => {
    const rect = cropRectOf(DEFAULT_CROP_CENTER, size);
    expect(rect.x).toBeCloseTo((1 - size.w) / 2, 10);
    expect(rect.y).toBe(0);
  });

  it('계약6 기하 규칙을 지킨다 — x+w ≤ 1, y+h ≤ 1', () => {
    for (const center of [
      { x: -5, y: -5 },
      { x: 5, y: 5 },
      { x: 0.5, y: 0.5 },
    ]) {
      const rect = cropRectOf(center, size);
      expect(rect.x).toBeGreaterThanOrEqual(0);
      expect(rect.y).toBeGreaterThanOrEqual(0);
      expect(rect.x + rect.w).toBeLessThanOrEqual(1);
      expect(rect.y + rect.h).toBeLessThanOrEqual(1);
    }
  });

  it('창이 소스 전체면 0에 붙는다', () => {
    expect(cropRectOf({ x: 0.9, y: 0.9 }, { w: 1, h: 1 })).toEqual({ x: 0, y: 0, w: 1, h: 1 });
  });
});

describe('moveCropCenter', () => {
  const size = cropSizeFor(9 / 16, SRC.width, SRC.height);

  it('중심을 그만큼 옮긴다', () => {
    const moved = moveCropCenter({ x: 0.5, y: 0.5 }, { x: 0.1, y: 0 }, size);
    expect(moved.x).toBeCloseTo(0.6, 10);
  });

  it('가장자리를 넘으면 중심 자체를 가둔다 — 되돌아올 때 헛돌지 않게', () => {
    const far = moveCropCenter({ x: 0.5, y: 0.5 }, { x: 99, y: 0 }, size);
    expect(far.x).toBeCloseTo(1 - size.w / 2, 10);
    // 여기서 반대로 조금만 끌면 그만큼 바로 움직인다(쌓인 값이 없다)
    const back = moveCropCenter(far, { x: -0.05, y: 0 }, size);
    expect(back.x).toBeCloseTo(1 - size.w / 2 - 0.05, 10);
  });

  it('여유가 없는 축은 가운데에 머문다', () => {
    const moved = moveCropCenter({ x: 0.5, y: 0.5 }, { x: 0, y: 0.3 }, { w: 0.3, h: 1 });
    expect(moved.y).toBe(0.5);
  });
});

describe('cropCenterDelta', () => {
  const size = { w: 0.5, h: 1 };

  it('오른쪽으로 끌면 크롭 창은 왼쪽으로 간다 — 영상이 손을 따라온다', () => {
    const delta = cropCenterDelta({ x: 100, y: 0 }, { width: 400, height: 700 }, size);
    // 칸 400px이 소스의 0.5를 보여주니 100px = 0.125
    expect(delta.x).toBeCloseTo(-0.125, 10);
  });

  it('칸이 넓을수록 같은 픽셀이 덜 움직인다', () => {
    const narrow = cropCenterDelta({ x: 50, y: 0 }, { width: 200, height: 700 }, size);
    const wide = cropCenterDelta({ x: 50, y: 0 }, { width: 800, height: 700 }, size);
    expect(Math.abs(narrow.x)).toBeGreaterThan(Math.abs(wide.x));
  });

  it('칸 크기를 모르면 안 움직인다 — 0으로 나누지 않는다', () => {
    expect(cropCenterDelta({ x: 100, y: 100 }, { width: 0, height: 0 }, size)).toEqual({
      x: 0,
      y: 0,
    });
  });
});

describe('cropObjectPosition', () => {
  it('왼쪽 끝은 0%, 오른쪽 끝은 100%', () => {
    expect(cropObjectPosition({ x: 0, y: 0, w: 0.5, h: 1 }).x).toBe(0);
    expect(cropObjectPosition({ x: 0.5, y: 0, w: 0.5, h: 1 }).x).toBe(100);
  });

  it('가운데는 50%', () => {
    expect(cropObjectPosition({ x: 0.25, y: 0, w: 0.5, h: 1 }).x).toBe(50);
  });

  it('여유가 없는 축은 가운데로 둔다', () => {
    expect(cropObjectPosition({ x: 0, y: 0, w: 0.5, h: 1 }).y).toBe(50);
  });
});

describe('cropFreeAxis', () => {
  it('세로를 다 쓰면 가로가 움직인다', () => {
    expect(cropFreeAxis({ w: 0.316, h: 1 })).toBe('x');
  });

  it('가로를 다 쓰면 세로가 움직인다', () => {
    expect(cropFreeAxis({ w: 1, h: 0.6 })).toBe('y');
  });

  it('소스 전체를 쓰면 고를 것이 없다', () => {
    expect(cropFreeAxis({ w: 1, h: 1 })).toBeNull();
  });

  it('부동소수 잡음을 여유로 오해하지 않는다', () => {
    expect(cropFreeAxis({ w: 0.9999, h: 0.9999 })).toBeNull();
  });
});
