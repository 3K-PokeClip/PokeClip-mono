import { describe, expect, it, vi } from 'vitest';
import {
  baseScale,
  clampOffset,
  cropToDataUrl,
  INITIAL_CROP,
  MASK_PX,
  OUTPUT_PX,
  zoomToScale,
} from './cropImage';

// 미리보기(CSS)와 결과(캔버스)가 같은 식을 써야 보이는 대로 잘린다 — 그 식만 따로 검사한다.

describe('cropImage 배율', () => {
  it('슬라이더 0은 등배, 100은 3배다', () => {
    expect(zoomToScale(0)).toBe(1);
    expect(zoomToScale(100)).toBe(3);
  });

  it('배율 1에서 짧은 변이 마스크를 꽉 채운다', () => {
    expect(baseScale(MASK_PX, MASK_PX)).toBe(1);
    expect(baseScale(MASK_PX * 2, MASK_PX)).toBe(0.5);
  });

  it('마스크가 커지면 배율도 함께 커진다 — 창이 넓으면 --pc-u가 마스크를 늘린다', () => {
    // 1920px 창에서 calc(176 * var(--pc-u))는 약 234px가 된다
    expect(baseScale(MASK_PX, 234)).toBeCloseTo(234 / 176);
  });

  it('크기를 아직 모르면 1로 둔다 — 0으로 나누지 않는다', () => {
    expect(baseScale(0, MASK_PX)).toBe(1);
  });
});

describe('cropToDataUrl', () => {
  const img = (w: number, h: number) => ({ naturalWidth: w, naturalHeight: h }) as HTMLImageElement;

  it('이미지 크기를 모르면 원본을 그대로 돌려준다', () => {
    expect(cropToDataUrl(img(0, 0), INITIAL_CROP, 'data:original', MASK_PX)).toBe('data:original');
  });

  it('마스크 안쪽을 정사각 512로 그린다 — 이동량도 같은 배율로 늘어난다', () => {
    const ctx = {
      translate: vi.fn(),
      rotate: vi.fn(),
      scale: vi.fn(),
      drawImage: vi.fn(),
    };
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(
      ctx as unknown as CanvasRenderingContext2D,
    );
    vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockReturnValue('data:cropped');

    const out = cropToDataUrl(
      img(MASK_PX, MASK_PX),
      { x: 10, y: -20, zoom: 0, rotation: -90 },
      'data:original',
      MASK_PX,
    );

    const k = OUTPUT_PX / MASK_PX;
    expect(out).toBe('data:cropped');
    expect(ctx.translate).toHaveBeenCalledWith(OUTPUT_PX / 2 + 10 * k, OUTPUT_PX / 2 - 20 * k);
    expect(ctx.rotate).toHaveBeenCalledWith(-Math.PI / 2);
    // 원본이 마스크와 같은 크기라 배율은 출력 확대분(k)만 남는다
    expect(ctx.scale).toHaveBeenCalledWith(k, k);
    vi.restoreAllMocks();
  });

  it('넓은 창에서도 미리보기가 원을 꽉 채우면 결과도 캔버스를 꽉 채운다', () => {
    // 1920px 창이면 calc(176 * var(--pc-u))가 약 234px로 렌더된다.
    // 미리보기 배율이 176 고정이면 그림이 원의 75%만 덮는데 내보내기는 꽉 찬 것으로 쳐서
    // 결과가 1.33배 더 당겨진다 — 두 식이 같은 마스크를 써야 이 관계가 성립한다.
    const wideMask = 234;
    const shortSide = 400;
    const rendered = baseScale(shortSide, wideMask) * zoomToScale(0) * shortSide;
    expect(rendered).toBeCloseTo(wideMask);
  });

  it('이동량이 실측한 마스크 기준으로 확대된다 — 176 고정이면 드래그가 엉뚱하게 찍힌다', () => {
    const ctx = { translate: vi.fn(), rotate: vi.fn(), scale: vi.fn(), drawImage: vi.fn() };
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(
      ctx as unknown as CanvasRenderingContext2D,
    );
    vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockReturnValue('data:cropped');

    const wideMask = 234;
    cropToDataUrl(img(400, 400), { x: 10, y: -20, zoom: 0, rotation: 0 }, 'data:o', wideMask);

    const k = OUTPUT_PX / wideMask; // 176을 쓰면 k가 1.33배 커져 이동이 과장된다
    expect(ctx.translate).toHaveBeenCalledWith(OUTPUT_PX / 2 + 10 * k, OUTPUT_PX / 2 - 20 * k);
    vi.restoreAllMocks();
  });
});

describe('clampOffset', () => {
  const M = 176;

  it('마스크를 덮는 범위 밖으로는 밀리지 않는다', () => {
    // 512 원본을 확대 100(3배)로 보면 반폭 264 — 원 반지름 88을 뺀 176까지만 밀 수 있다
    const t = clampOffset({ x: 9999, y: -9999, zoom: 100, rotation: 0 }, 512, 512, M);
    expect(t.x).toBeCloseTo(176);
    expect(t.y).toBeCloseTo(-176);
  });

  it('범위 안의 값은 그대로 둔다', () => {
    const t = clampOffset({ x: 10, y: -20, zoom: 100, rotation: 0 }, 512, 512, M);
    expect(t.x).toBe(10);
    expect(t.y).toBe(-20);
  });

  it('확대가 최소면 그림이 원과 같아 중앙에 고정된다', () => {
    const t = clampOffset({ x: 50, y: 50, zoom: 0, rotation: 0 }, 512, 512, M);
    expect(t.x).toBe(0);
    expect(t.y).toBe(0);
  });

  it('90도 회전이면 가로·세로 한계가 뒤바뀐다', () => {
    // 세로로 긴 원본(400×800)을 90도 돌리면 가로가 길어진다
    const upright = clampOffset({ x: 9999, y: 9999, zoom: 0, rotation: 0 }, 400, 800, M);
    const turned = clampOffset({ x: 9999, y: 9999, zoom: 0, rotation: -90 }, 400, 800, M);
    expect(upright.x).toBeCloseTo(0);
    expect(turned.y).toBeCloseTo(0);
    expect(turned.x).toBeCloseTo(upright.y);
  });

  it('바뀐 것이 없으면 같은 객체를 돌려준다 — 재클램프 이펙트가 자신을 깨우지 않게', () => {
    const t = { x: 10, y: -20, zoom: 100, rotation: 0 };
    expect(clampOffset(t, 512, 512, M)).toBe(t);
  });

  it('마스크가 줄면 한계도 줄어 이미 밀어 둔 값이 다시 잘린다', () => {
    const wide = clampOffset({ x: 9999, y: 0, zoom: 100, rotation: 0 }, 512, 512, 234);
    const narrow = clampOffset(wide, 512, 512, 176);
    expect(narrow.x).toBeLessThan(wide.x);
    expect(narrow.x).toBeCloseTo(176);
  });

  it('크기를 아직 모르면 건드리지 않는다', () => {
    const t = { x: 5, y: 5, zoom: 42, rotation: 0 };
    expect(clampOffset(t, 0, 0, M)).toBe(t);
  });
});
