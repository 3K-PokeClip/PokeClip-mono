// 편집기 레이아웃 5종과 각 모드가 소스에서 잡는 영역 (시안 1d, 2026-09-08 갱신분).
//
// 시안 캡션: "레이아웃은 씨미(ci.me) 편집기를 참고한 **세로 · 분할 · 중앙 · 크롭 · 가로** 5종 —
// 원본에서 영역 프레임을 직접 잡고(분할 = 상단·하단, 크롭 = 메인 + 작은 화면, 세로·중앙 = 비율 고정),
// 결과에서 바로 확인합니다."
//
// ⚠️ 계약6 v1 의 `aspect` enum 은 `VERT_9_16`·`SQUARE_1_1` 둘뿐이고 가로 16:9 는 명시적으로
// 제외돼 있다(rev1, 2026-08-24). 시안이 그보다 앞서 나갔으므로 **화면은 시안을 따르되**
// 레시피로 나갈 수 있는 것은 아직 세로뿐이다 — 나머지는 계약6 v2 논의가 선행해야 한다.

/** 시안의 모드 id 를 그대로 쓴다 */
export type EditorLayout = 'vert' | 'split' | 'center' | 'crop' | 'horiz';

export interface LayoutOption {
  value: EditorLayout;
  label: string;
  /** 시안의 smHint — 무엇이 되는지 한 줄로 */
  hint: string;
}

export const LAYOUT_OPTIONS: readonly LayoutOption[] = [
  { value: 'vert', label: '세로', hint: '9:16 꽉 채움' },
  { value: 'split', label: '분할', hint: '상단 · 하단 2영역' },
  { value: 'center', label: '중앙', hint: '원본 비율 유지' },
  { value: 'crop', label: '크롭', hint: '메인 + 작은 화면' },
  { value: 'horiz', label: '가로', hint: '16:9 원본' },
];

/** 분할 상단 지분(%) — 시안의 칩 3종 */
export const SPLIT_RATIOS: readonly number[] = [50, 60, 70];
export const DEFAULT_SPLIT_RATIO = 50;

/** 자막이 놓이는 자리 — 시안에서 레이아웃 패널이 아니라 자막 패널로 옮겨졌다 */
export type CaptionPosition = 'top' | 'edge' | 'bottom';
export const CAPTION_POSITIONS: readonly { value: CaptionPosition; label: string }[] = [
  { value: 'top', label: '상단' },
  { value: 'edge', label: '경계' },
  { value: 'bottom', label: '하단' },
];

/**
 * 중앙 모드의 「여백 채우기」 — 16:9 위·아래 빈 영역을 무엇으로 채울까 (시안 2026-09-08 갱신분).
 * 블러는 원본을 확대해 흐리게 깔고 강도(0..100)를 고른다. 단색은 지정한 색으로 채운다.
 */
export type CenterFill = { kind: 'blur'; strength: number } | { kind: 'color'; color: string };

/** 시안 슬라이더 기본값 */
export const DEFAULT_BLUR_STRENGTH = 60;
export const DEFAULT_CENTER_FILL: CenterFill = { kind: 'blur', strength: DEFAULT_BLUR_STRENGTH };

/** 단색 채움의 스와치 넷 — 시안의 값 그대로. 그 밖의 색은 직접 고른다 */
export const CENTER_COLOR_PRESETS: readonly { value: string; label: string }[] = [
  { value: '#0b0b10', label: '검정' },
  { value: '#ffffff', label: '흰색' },
  { value: '#1c2440', label: '남색' },
  { value: '#2a1a2e', label: '자줏빛' },
];

/**
 * 크롭 모드에서 결과의 작은 화면 둘레 — 시안은 1px 흰 라인 + 그림자 + 4px 라운드를 늘 그린다.
 * 끄거나 굵기·색을 바꿀 수 있게 설정으로 뺐다.
 */
export interface PipBorder {
  on: boolean;
  /** 라인 굵기(px 단위, 화면 배율은 렌더가 곱한다) */
  width: number;
  color: string;
}

export const DEFAULT_PIP_BORDER: PipBorder = { on: true, width: 1, color: '#ffffff' };
export const PIP_BORDER_WIDTHS: readonly number[] = [1, 2, 3];
export const PIP_BORDER_PRESETS: readonly { value: string; label: string }[] = [
  { value: '#ffffff', label: '흰색' },
  { value: '#000000', label: '검정' },
  { value: '#d44697', label: '포인트' },
  { value: '#9ca3af', label: '회색' },
];

/** 결과 화면에서 이 영역이 놓이는 방식 */
export type RegionPlacement =
  /** 결과 화면을 꽉 채운다 */
  | { kind: 'fill' }
  /** 세로로 쌓인다 — 지분만큼 높이를 가져간다 */
  | { kind: 'stack'; flex: number }
  /** 비율을 지킨 채 가운데 놓인다. 위아래 띠는 fill 이 정한다 */
  | { kind: 'contain'; aspect: number; fill: CenterFill }
  /** 다른 영역 위에 얹힌다 (크롭 모드의 작은 화면). 둘레는 border 가 정한다 */
  | {
      kind: 'overlay';
      left: number;
      top: number;
      width: number;
      aspect: number;
      border: PipBorder;
    };

export interface LayoutRegion {
  id: string;
  /** 프레임 안 좌상단에 붙는 배지 — 시안: 상단 / 하단 / 메인 화면 / 작은 화면 */
  label: string;
  /** 소스에서 잘라낼 비율(가로/세로) */
  aspect: number;
  /** 주 영역은 accent, 보조 영역은 point — 시안의 색 규약 */
  tone: 'accent' | 'point';
  /** 모서리를 끌어 크기를 바꿀 수 있는가. 가로는 소스 전체라 잡을 것이 없다 */
  resizable: boolean;
  /** 선택했을 때 바깥을 어둡게 덮는가. 크롭 모드는 두 영역을 함께 봐야 해서 끈다 */
  scrim: boolean;
  placement: RegionPlacement;
}

const VERT_ASPECT = 9 / 16;
const HORIZ_ASPECT = 16 / 9;

/**
 * 크롭 모드의 작은 화면이 결과 안에서 놓이는 자리 — 메인 화면 기준 정규화 사각형(0..1).
 * **비율을 묶지 않는다.** 꼭짓점을 잡아 자유롭게 늘이고, 그렇게 잡은 모양(픽셀 비율)이 원본에서
 * 잘라낼 「작은 화면」 프레임의 비율이 된다 — 따로 고르는 설정은 없고, 원본 프레임 쪽은 이 비율에
 * 묶인 채 크기만 바뀐다.
 * 시안의 cropPipTarget: left:18% top:50% width:64%, 4:3.
 */
export interface PipBox {
  x: number;
  y: number;
  w: number;
  h: number;
}

/** 시안 기본값 — 폭 64% 에 4:3. 결과가 9:16 이라 정규화 높이는 0.64 × 3/4 × 9/16 = 0.27 */
export const DEFAULT_PIP: PipBox = { x: 0.18, y: 0.5, w: 0.64, h: 0.27 };
/** 작은 화면 크기의 허용 범위 — 너무 작으면 안 보이고, 너무 크면 메인을 가린다 */
export const PIP_MIN_WIDTH = 0.2;
export const PIP_MIN_HEIGHT = 0.06;
export const PIP_MAX_SIZE = 0.9;
/** 시안 기본 비율(4:3) — 레이아웃 정의가 비율을 따로 받지 않을 때 쓴다 */
export const PIP_ASPECT = 4 / 3;

function clamp01(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), Math.max(min, max));
}

/** 작은 화면의 결과 안 사각형 — 크기를 허용 범위에, 자리를 메인 안에 가둔다 */
export function pipRectOf(pip: PipBox): PipBox {
  const w = clamp01(pip.w, PIP_MIN_WIDTH, PIP_MAX_SIZE);
  const h = clamp01(pip.h, PIP_MIN_HEIGHT, PIP_MAX_SIZE);
  return {
    x: clamp01(pip.x, 0, 1 - w),
    y: clamp01(pip.y, 0, 1 - h),
    w,
    h,
  };
}

/**
 * 작은 화면의 픽셀 비율(가로/세로). 결과 화면이 세로(9:16)라 같은 정규화 값이라도 픽셀 비율이
 * 다르다 — 정규화 비에 결과 비율을 곱해야 계약6 의 픽셀 기준 종횡비가 된다.
 */
export function pipAspectOf(pip: PipBox, resultAspect: number): number {
  const rect = pipRectOf(pip);
  return (rect.w / rect.h) * resultAspect;
}

/** 작은 화면을 옮긴다 (정규화 이동량) */
export function movePip(pip: PipBox, delta: { x: number; y: number }): PipBox {
  const rect = pipRectOf(pip);
  return {
    x: clamp01(rect.x + delta.x, 0, 1 - rect.w),
    y: clamp01(rect.y + delta.y, 0, 1 - rect.h),
    w: rect.w,
    h: rect.h,
  };
}

/**
 * 작은 화면의 모서리를 끌어 모양을 바꾼다 — 반대편 모서리를 못 박고, 가로·세로를 따로 잰다.
 * `pointer` 는 메인 화면 안의 정규화 좌표다.
 */
export function resizePip(
  pip: PipBox,
  corner: 'nw' | 'ne' | 'sw' | 'se',
  pointer: { x: number; y: number },
): PipBox {
  const rect = pipRectOf(pip);
  const west = corner === 'nw' || corner === 'sw';
  const north = corner === 'nw' || corner === 'ne';
  const anchor = { x: west ? rect.x + rect.w : rect.x, y: north ? rect.y + rect.h : rect.y };
  // 부호를 살려 잰다 — 고정점을 지나쳐 끌었을 때 되레 커지지 않게.
  // 상한은 고정점에서 메인 경계까지 — 그래야 고정점이 안 움직인다
  const reachX = west ? anchor.x - pointer.x : pointer.x - anchor.x;
  const reachY = north ? anchor.y - pointer.y : pointer.y - anchor.y;
  const ceilingX = Math.min(PIP_MAX_SIZE, west ? anchor.x : 1 - anchor.x);
  const ceilingY = Math.min(PIP_MAX_SIZE, north ? anchor.y : 1 - anchor.y);
  const w = clamp01(reachX, PIP_MIN_WIDTH, Math.max(PIP_MIN_WIDTH, ceilingX));
  const h = clamp01(reachY, PIP_MIN_HEIGHT, Math.max(PIP_MIN_HEIGHT, ceilingY));
  return {
    x: west ? anchor.x - w : anchor.x,
    y: north ? anchor.y - h : anchor.y,
    w,
    h,
  };
}

/** 결과 화면 자체의 비율. 가로만 16:9 고, 나머지는 세로다 */
export function resultAspect(layout: EditorLayout): number {
  return layout === 'horiz' ? HORIZ_ASPECT : VERT_ASPECT;
}

/**
 * 이 모드가 소스에서 잡는 영역들.
 *
 * 분할의 두 영역은 지분에 따라 비율이 달라진다 — 상단이 60%면 그 영역은 결과의 60% 높이를
 * 채워야 하므로, 소스에서 잘라낼 모양도 그만큼 납작해진다.
 * 크롭의 작은 화면도 같은 이유로 `pipAspect` 를 받는다 — 결과에 4:3 으로 놓일 화면은 소스에서도
 * 4:3 으로 잘라야 찌그러지지 않는다.
 */
export function layoutRegions(
  layout: EditorLayout,
  splitRatio: number,
  pipAspect: number = PIP_ASPECT,
): readonly LayoutRegion[] {
  switch (layout) {
    case 'split': {
      const top = Math.min(90, Math.max(10, splitRatio)) / 100;
      const bottom = 1 - top;
      return [
        {
          id: 'top',
          label: '상단',
          aspect: VERT_ASPECT / top,
          tone: 'accent',
          resizable: true,
          scrim: true,
          placement: { kind: 'stack', flex: top },
        },
        {
          id: 'bottom',
          label: '하단',
          aspect: VERT_ASPECT / bottom,
          tone: 'point',
          resizable: true,
          scrim: true,
          placement: { kind: 'stack', flex: bottom },
        },
      ];
    }
    case 'crop':
      return [
        {
          id: 'main',
          label: '메인 화면',
          aspect: VERT_ASPECT,
          tone: 'accent',
          resizable: true,
          // 두 영역을 같이 보고 배치해야 해서 그늘을 끈다 (시안: box-shadow none)
          scrim: false,
          placement: { kind: 'fill' },
        },
        {
          id: 'pip',
          label: '작은 화면',
          aspect: pipAspect,
          tone: 'point',
          resizable: true,
          scrim: false,
          placement: {
            kind: 'overlay',
            left: 0.18,
            top: 0.5,
            width: 0.64,
            aspect: pipAspect,
            border: DEFAULT_PIP_BORDER,
          },
        },
      ];
    case 'center':
      return [
        {
          id: 'main',
          label: '원본 비율',
          aspect: HORIZ_ASPECT,
          tone: 'accent',
          resizable: true,
          scrim: true,
          placement: { kind: 'contain', aspect: HORIZ_ASPECT, fill: DEFAULT_CENTER_FILL },
        },
      ];
    case 'horiz':
      return [
        {
          id: 'main',
          label: '원본 16:9',
          aspect: HORIZ_ASPECT,
          tone: 'accent',
          // 소스 전체가 그대로 나가므로 잡을 것이 없다 (시안: soloResizable 에서 제외)
          resizable: false,
          scrim: false,
          placement: { kind: 'fill' },
        },
      ];
    case 'vert':
    default:
      return [
        {
          id: 'main',
          label: '세로 9:16',
          aspect: VERT_ASPECT,
          tone: 'accent',
          resizable: true,
          scrim: true,
          placement: { kind: 'fill' },
        },
      ];
  }
}

/** 이 모드에서 그 영역을 처음 열었을 때의 자리·확대율 — 시안 프레임 좌표를 옮긴 값 */
export function defaultRegionWindow(
  layout: EditorLayout,
  regionId: string,
): { center: { x: number; y: number }; zoom: number } {
  if (layout === 'split') {
    // 위·아래로 갈라 둔다 — 겹쳐 있으면 뭘 잡았는지 안 보인다
    return regionId === 'top'
      ? { center: { x: 0.5, y: 0.25 }, zoom: 0.5 }
      : { center: { x: 0.5, y: 0.75 }, zoom: 0.5 };
  }
  // 시안은 작은 화면을 오른쪽 위에 작게 둔다 (left:72% top:6% width:26%)
  if (layout === 'crop' && regionId === 'pip') return { center: { x: 0.85, y: 0.25 }, zoom: 0.35 };
  // 시안의 중앙 프레임은 원본의 84% 폭이다
  if (layout === 'center') return { center: { x: 0.5, y: 0.5 }, zoom: 0.84 };
  return { center: { x: 0.5, y: 0.5 }, zoom: 1 };
}
