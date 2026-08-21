// 원형 크롭의 좌표 계산 (디자인 1p ④).
//
// 스테이지에서는 CSS transform으로 미리보고, 「적용」에서 같은 변환을 캔버스에 다시 그린다.
// 두 곳이 같은 식을 써야 보이는 대로 잘린다 — 그래서 배율·순서를 여기 한 군데 둔다.

export interface CropTransform {
  /** 원 중심 기준 좌우 이동 (스테이지 px) */
  x: number;
  /** 원 중심 기준 상하 이동 (스테이지 px) */
  y: number;
  /** 확대 슬라이더 0~100 */
  zoom: number;
  /** 왼쪽 90도씩 (도) */
  rotation: number;
}

/** 1p의 슬라이더 초기값은 42다. */
export const INITIAL_CROP: CropTransform = { x: 0, y: 0, zoom: 42, rotation: 0 };

/**
 * 시안 1p의 원형 마스크 지름. **실제 렌더 지름이 아니다** — CSS가 `calc(176 * var(--pc-u))`
 * 이고 `--pc-u`는 루트 font-size(`clamp(1rem, 1.1104vw, 1.35rem)`)를 타므로 뷰포트가
 * 1441px을 넘으면 이보다 커진다(1920px에서 약 234px). 그래서 계산은 실측한 지름을 받고,
 * 이 상수는 시안 기준값이자 **측정 불가일 때의 대비값**으로만 쓴다.
 */
export const MASK_PX = 176;

/** 내보내는 정사각 크기. 1p의 업로드 안내가 「정사각 512px 권장」이다. */
export const OUTPUT_PX = 512;

/** 슬라이더 0~100 → 배율 1~3배. */
export function zoomToScale(zoom: number): number {
  return 1 + zoom / 50;
}

/**
 * 원본 픽셀 → 스테이지 픽셀 배율. 짧은 변이 원을 꽉 채우는 크기가 기준(배율 1).
 * `maskPx`는 **화면에 실제로 그려진** 마스크 지름이어야 미리보기와 결과가 같아진다.
 */
export function baseScale(naturalShortSide: number, maskPx: number): number {
  return naturalShortSide === 0 ? 1 : maskPx / naturalShortSide;
}

/**
 * 마스크 안쪽만 정사각으로 잘라 data URL로 돌려준다.
 *
 * 캔버스를 얻지 못하거나 이미지 크기를 아직 모르면 `fallback`(원본)을 그대로 돌려준다 —
 * jsdom처럼 이미지를 실제로 디코드하지 않는 환경에서 화면이 비는 것을 막기 위한 것이지,
 * 실패를 감추는 자리가 아니다. 브라우저에서는 두 조건 모두 서지 않는다.
 */
export function cropToDataUrl(
  img: HTMLImageElement,
  transform: CropTransform,
  fallback: string,
  /** 화면에 그려진 마스크 지름. 미리보기가 쓴 것과 같은 값이어야 한다. */
  maskPx: number,
): string {
  const shortSide = Math.min(img.naturalWidth, img.naturalHeight);
  if (shortSide === 0) return fallback; // 아직 못 읽은 이미지 — 캔버스를 잡을 것도 없다
  const canvas = document.createElement('canvas');
  canvas.width = OUTPUT_PX;
  canvas.height = OUTPUT_PX;
  const ctx = canvas.getContext('2d');
  if (ctx === null) return fallback;

  // 스테이지 px → 출력 px 배율. 마스크가 출력(512)으로 늘어난 만큼 이동량도 늘어난다
  const k = OUTPUT_PX / maskPx;
  const scale = baseScale(shortSide, maskPx) * zoomToScale(transform.zoom) * k;

  // CSS의 translate → rotate → scale 순서를 그대로 옮긴다
  ctx.translate(OUTPUT_PX / 2 + transform.x * k, OUTPUT_PX / 2 + transform.y * k);
  ctx.rotate((transform.rotation * Math.PI) / 180);
  ctx.scale(scale, scale);
  ctx.drawImage(img, -img.naturalWidth / 2, -img.naturalHeight / 2);
  return canvas.toDataURL('image/png');
}
