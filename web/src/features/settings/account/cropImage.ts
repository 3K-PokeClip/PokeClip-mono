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
 * 🔴 **자르지 못하면 `null`이다** — 원본을 폴백으로 돌려주지 않는다. 예전에는 원본을 그대로
 * 돌려줬는데, 그 값은 정상 base64 data URL이라 호출부의 어떤 검사에도 걸리지 않고 그대로
 * 업로드됐다: 캔버스를 못 잡은 브라우저에서 **잘리지 않은 원본(최대 5MB)이 조용히 아바타로
 * 확정**되거나, 2MB를 넘어 「사진이 너무 커요」라는 엉뚱한 사유를 받았다. 자르지 못한 것과
 * 잘라낸 것을 값의 모양으로 구분할 수 없었던 것이 원인이라 아예 형을 갈랐다.
 */
export function cropToDataUrl(
  img: HTMLImageElement,
  transform: CropTransform,
  /** 화면에 그려진 마스크 지름. 미리보기가 쓴 것과 같은 값이어야 한다. */
  maskPx: number,
): string | null {
  const shortSide = Math.min(img.naturalWidth, img.naturalHeight);
  if (shortSide === 0) return null; // 아직 못 읽은 이미지 — 캔버스를 잡을 것도 없다
  const canvas = document.createElement('canvas');
  canvas.width = OUTPUT_PX;
  canvas.height = OUTPUT_PX;
  const ctx = canvas.getContext('2d');
  if (ctx === null) return null;

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

/**
 * 이동량을 그림이 마스크를 계속 덮는 범위로 자른다.
 *
 * 없으면 그림을 원 밖으로 완전히 끌어낸 채 「적용」할 수 있고, 그 결과는 캔버스 기본값
 * (투명)만 담긴 정사각 PNG다 — 아바타가 빈 원이 되고 되돌릴 안내도 없다.
 * 확대·회전이 바뀌면 덮는 범위도 달라지므로 그때마다 다시 잘라야 한다.
 */
export function clampOffset(
  transform: CropTransform,
  naturalWidth: number,
  naturalHeight: number,
  maskPx: number,
): CropTransform {
  if (naturalWidth === 0 || naturalHeight === 0) return transform;
  const scale =
    baseScale(Math.min(naturalWidth, naturalHeight), maskPx) * zoomToScale(transform.zoom);
  // 90·270도에서는 가로·세로가 뒤바뀐다
  const swapped = Math.abs(transform.rotation / 90) % 2 === 1;
  const halfWidth = ((swapped ? naturalHeight : naturalWidth) * scale) / 2;
  const halfHeight = ((swapped ? naturalWidth : naturalHeight) * scale) / 2;
  // 그림 반폭에서 원 반지름을 뺀 만큼까지만 밀 수 있다. 음수면(덮지 못하면) 중앙 고정.
  const limitX = Math.max(0, halfWidth - maskPx / 2);
  const limitY = Math.max(0, halfHeight - maskPx / 2);
  const x = Math.min(limitX, Math.max(-limitX, transform.x));
  const y = Math.min(limitY, Math.max(-limitY, transform.y));
  // 바뀐 것이 없으면 같은 객체를 돌려준다 — 호출부가 이 결과를 그대로 상태에 넣으므로,
  // 매번 새 객체를 주면 재클램프 이펙트가 자기 자신을 깨워 무한 루프가 된다
  if (x === transform.x && y === transform.y) return transform;
  return { ...transform, x, y };
}
