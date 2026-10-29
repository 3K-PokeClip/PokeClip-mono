import { useId } from 'react';
import { ACCENT_CLIP, INDIGO_CLIP, PLAY, RING } from './BrandSymbol';
import styles from './BrandLogoDraw.module.css';

// 불러오는 중에 쓰는 PokeClip 로고 그리기 — 브랜드 모션(public/brand/motion/logo_animated.svg)을 되풀이로 옮겼다.
// 고리는 굵은 획(마스크)이 지나간 자리만 드러나고, 다 그리면 재생 마크가 튀어나온다. 움직임은 BrandLogoDraw.module.css.
// 원본은 한 번 그리고 멈추므로 키프레임을 한 바퀴 안으로 줄여 무한 반복하고, 끝의 흐려짐도 같은 SVG 안(로고 묶음)에서 돈다:
// 바깥 상자의 opacity로 흐리면 그것은 합성 스레드, 그리기는 메인 스레드라 바퀴 경계에서 한 프레임 어긋나 다 그린 로고가 비쳤다.
// 고리 · 재생 마크 · 색 가름은 BrandSymbol과 같은 도형이다(재생 마크도 같은 --pc-color-brand-mark — 원본 흰색은 라이트에서 사라진다).
// 한 화면에 여럿이 떠도 마스크 · 클립 id가 서로를 가리키지 않게 인스턴스마다 다르게 붙인다.

/** 고리를 드러내는 획의 길 — 원본 SVG의 spine-main(인디고) · spine-accent(마젠타) 그대로 */
const SPINE_MAIN =
  'M 858.42,590.46 C 856.93,577.92 854.74,565.45 853.46,552.89 C 851.27,531.35 849.75,509.71 846.83,488.28 C 843.90,466.84 840.73,445.53 835.91,424.28 C 831.09,403.03 825.02,381.43 817.90,360.79 C 810.78,340.16 803.63,319.83 793.20,300.48 C 782.77,281.13 769.72,260.86 755.32,244.70 C 740.91,228.54 724.90,214.70 706.74,203.51 C 688.59,192.32 667.25,183.43 646.40,177.58 C 625.54,171.73 603.20,170.09 581.60,168.40 C 559.99,166.71 538.53,167.60 516.77,167.45 C 495.00,167.30 472.90,167.22 450.99,167.50 C 429.08,167.78 407.03,167.24 385.31,169.15 C 363.58,171.07 341.69,173.88 320.62,178.97 C 299.56,184.06 273.93,186.39 258.90,199.68 C 243.87,212.98 237.39,238.08 230.45,258.73 C 223.52,279.38 221.23,301.83 217.27,323.57 C 213.31,345.30 210.84,367.79 206.67,389.14 C 202.50,410.48 197.29,430.58 192.24,451.65 C 187.20,472.72 181.08,494.04 176.39,515.55 C 171.70,537.06 166.64,559.09 164.12,580.69 C 161.60,602.30 159.77,623.66 161.25,645.18 C 162.73,666.70 166.20,689.16 172.98,709.82 C 179.77,730.48 189.91,751.35 201.97,769.12 C 214.03,786.89 228.28,803.46 245.35,816.45 C 262.42,829.44 283.91,841.61 304.39,847.07 C 324.86,852.52 347.25,852.76 368.19,849.16 C 389.13,845.56 409.55,833.18 430.03,825.47 C 450.50,817.75 469.97,805.57 491.02,802.87 C 512.07,800.18 535.81,803.58 556.32,809.30 C 576.82,815.01 593.91,829.74 614.06,837.16 C 634.22,844.58 656.14,853.35 677.26,853.80 C 698.39,854.26 721.64,849.20 740.82,839.90 C 759.99,830.60 777.85,813.86 792.32,798.02 C 806.80,782.19 818.03,763.95 827.67,744.90 C 835.15,730.13 841.33,714.24 846.24,697.95';
const SPINE_ACCENT =
  'M 833.84,731.81 C 840.57,716.45 846.01,700.19 850.18,683.72 C 855.57,662.49 859.44,639.32 859.98,617.52 C 860.53,595.71 855.65,574.43 853.46,552.89 C 851.52,533.86 850.11,514.74 847.80,495.77';

export function BrandLogoDraw({ size, className }: { size: number; className?: string }) {
  const uid = useId().replace(/[^a-zA-Z0-9_-]/g, '');
  const ring = `pc-draw-ring-${uid}`;
  const indigo = `pc-draw-indigo-${uid}`;
  const accent = `pc-draw-accent-${uid}`;
  const maskMain = `pc-draw-mask-main-${uid}`;
  const maskAccent = `pc-draw-mask-accent-${uid}`;
  return (
    <svg
      viewBox="0 0 1024 1024"
      width={size}
      height={size}
      className={className}
      aria-hidden="true"
      focusable="false"
    >
      <defs>
        <path id={ring} d={RING} />
        <clipPath id={indigo}>
          <polygon points={INDIGO_CLIP} />
        </clipPath>
        <clipPath id={accent}>
          <polygon points={ACCENT_CLIP} />
        </clipPath>
        <mask id={maskMain} maskUnits="userSpaceOnUse" x="0" y="0" width="1024" height="1024">
          <path
            className={styles.spineMain}
            d={SPINE_MAIN}
            pathLength={1}
            fill="none"
            stroke="#fff"
            strokeWidth={210}
            strokeLinecap="butt"
          />
        </mask>
        <mask id={maskAccent} maskUnits="userSpaceOnUse" x="0" y="0" width="1024" height="1024">
          <path
            className={styles.spineAccent}
            d={SPINE_ACCENT}
            pathLength={1}
            fill="none"
            stroke="#fff"
            strokeWidth={210}
            strokeLinecap="butt"
          />
        </mask>
      </defs>
      <g className={styles.logo}>
        <use
          href={`#${ring}`}
          fill="#586FC4"
          clipPath={`url(#${indigo})`}
          mask={`url(#${maskMain})`}
        />
        <use
          href={`#${ring}`}
          fill="#D63F9F"
          clipPath={`url(#${accent})`}
          mask={`url(#${maskAccent})`}
        />
        <path className={styles.play} d={PLAY} fill="var(--pc-color-brand-mark)" />
      </g>
    </svg>
  );
}
