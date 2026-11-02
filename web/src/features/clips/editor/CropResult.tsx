import { maxCropSize, type CropRect } from './cropMath';
import { SOURCE_SIZE, resultAspect, type RegionPlacement } from './editorLayout';
import { BLUR_SIGMA_AT_FULL, PIP_RADIUS_PX, PIP_SHADOW, UI_REFERENCE_WIDTH } from './recipeLook';
import { VideoSurface } from './VideoSurface';
import styles from './editorShared.module.css';

// 잡은 영역이 결과 화면의 어디에 어떤 모양으로 놓이는지 보여주는 칸 (POK-109).
//
// 실제 영상이 있으면 크롭 좌표대로 잘라 그리고, 없으면 자리(placement)·여백 채우기·테두리만 시안대로 그리고
// 안에는 라벨을 둔다.
//
// 테두리·모서리·그림자·흐림은 **결과 칸 폭에 대한 비**(cqw)로 그린다 — 렌더가 같은 비로 영상에 그린다(recipeLook.ts).

/**
 * 시안 px(결과 칸 기준 폭 240px에서의 값) → 결과 칸 폭 비례 길이. `--pc-fu`는 결과 칸이 정한다(결과 칸 폭 ÷
 * {@link UI_REFERENCE_WIDTH}, editorShared.module.css)
 */
const frameLength = (px: number) => `calc(var(--pc-fu) * ${px})`;

/** 흐린 바탕에 깔 원본 — 원본 전체를 결과(9:16)에 꽉 차게. 렌더와 같은 자리다 */
const COVER_SIZE = maxCropSize(resultAspect('center'), SOURCE_SIZE.width, SOURCE_SIZE.height);
const COVER_CROP: CropRect = {
  x: (1 - COVER_SIZE.w) / 2,
  y: (1 - COVER_SIZE.h) / 2,
  w: COVER_SIZE.w,
  h: COVER_SIZE.h,
};

export function CropResult({
  label,
  placement,
  video = null,
  crop = null,
}: {
  label: string;
  /** 실제 영상(있으면 라벨 대신 잡은 영역을 잘라 그린다) */
  video?: HTMLVideoElement | null;
  crop?: CropRect | null;
  /** 결과 화면에서 이 영역이 놓이는 방식 — 레이아웃이 정한다 */
  placement: RegionPlacement;
}) {
  // 배치는 레이아웃이 정한다 — 꽉 채우거나, 지분만큼 쌓이거나, 비율을 지켜 가운데 놓이거나,
  // 다른 영역 위에 얹힌다(크롭 모드의 작은 화면).
  const style =
    placement.kind === 'stack'
      ? { flex: placement.flex }
      : placement.kind === 'overlay'
        ? {
            position: 'absolute' as const,
            left: `${placement.left * 100}%`,
            top: `${placement.top * 100}%`,
            width: `${placement.width * 100}%`,
            aspectRatio: placement.aspect,
            zIndex: 2,
            // 시안: 1px 라인 + 그림자 + 4px 라운드. 설정으로 끄면 맨 그림이다
            ...(placement.border.on
              ? {
                  // 선은 1px 밑으로 안 내린다 — 결과 칸이 기준 폭보다 작으면 브라우저가 기기 픽셀로 깎아 시안보다 가늘어진다
                  border: `max(1px, ${frameLength(placement.border.width)}) solid ${placement.border.color}`,
                  borderRadius: frameLength(PIP_RADIUS_PX),
                  boxShadow: `0 ${frameLength(PIP_SHADOW.offsetPx)} ${frameLength(PIP_SHADOW.blurPx)} rgb(0 0 0 / ${PIP_SHADOW.alpha})`,
                  overflow: 'hidden' as const,
                }
              : {}),
          }
        : { flex: 1 };

  // 중앙 모드 — 시안: 블러 배경(flex 1) / 원본 16:9 그대로(폭 100%) / 블러 배경(flex 1).
  if (placement.kind === 'contain') {
    const fill = placement.fill;
    return (
      <div
        className={styles.resultPane}
        data-placement="contain"
        // 단색이면 바닥색으로, 블러면 강도를 CSS 변수로 넘기고 원본 전체를 흐리게 깐다(영상이 있을 때)
        style={
          fill.kind === 'color'
            ? { ...style, background: fill.color }
            : { ...style, ['--pc-blur' as string]: String(fill.strength / 100) }
        }
      >
        {video && fill.kind === 'blur' ? (
          <BlurBackdrop video={video} strength={fill.strength} />
        ) : null}
        <div className={styles.resultContain} style={{ aspectRatio: placement.aspect }}>
          {video ? (
            <VideoSurface video={video} crop={crop} />
          ) : (
            <span className={styles.sourcePlaceholder}>{label}</span>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className={styles.resultPane} data-placement={placement.kind} style={style}>
      {video ? (
        <VideoSurface video={video} crop={crop} />
      ) : (
        <span className={styles.sourcePlaceholder}>{label}</span>
      )}
    </div>
  );
}

/**
 * 흐린 바탕 — 표준편차 = 세기/100 × 결과 폭의 4%(렌더와 같은 식). CSS 흐림은 가장자리가 투명하게 빠지므로
 * 흐림 반경(3σ)만큼 칸 밖으로 넓게 깐다 — 렌더는 가장자리 픽셀을 늘여 흐려서 빠지는 테두리가 없다.
 */
function BlurBackdrop({ video, strength }: { video: HTMLVideoElement; strength: number }) {
  const sigma = (strength / 100) * BLUR_SIGMA_AT_FULL;
  return (
    <div
      className={styles.resultBlur}
      data-testid="result-blur"
      style={{
        inset: `calc(var(--pc-fu) * ${-3 * sigma * UI_REFERENCE_WIDTH})`,
        filter: sigma > 0 ? `blur(calc(var(--pc-fu) * ${sigma * UI_REFERENCE_WIDTH}))` : undefined,
      }}
    >
      <VideoSurface video={video} crop={COVER_CROP} />
    </div>
  );
}
