// 편집 화면의 모양(레이아웃·자르는 자리·여백 채우기·작은 화면·자막 자리) ↔ 계약6 v2 출력 (POK-252).
//
// 화면에서 고른 그대로 영상이 나와야 한다. 그래서 두 방향을 한 파일에 둔다 — 저장할 때(화면 → 레시피)와
// 편집본을 다시 열 때(레시피 → 화면)가 같은 규칙을 써야 저장했다 연 편집본이 제자리로 온다.
//
// 결과 화면 안의 길이(테두리·구분선·모서리·자막 여백)는 **결과 폭에 대한 비**로 싣는다. 미리보기도 같은 비로
// 그린다(editorShared.module.css 의 cqw) — 화면 크기가 달라도 미리보기와 영상의 비례가 같다.
// 시안의 px 값은 결과 칸 기준 폭 240px(1440×900 화면에서 234px 실측)에서의 값으로 읽는다.

import type {
  RecipeBackground,
  RecipeDocument,
  RecipeOutputV2,
  RecipeSubtitles,
} from '@/api/clipEditor';
import { cropRectOf, maxCropSize, minZoomOf, type CropRect, type CropWindow } from './cropMath';
import {
  DEFAULT_PIP_BORDER,
  SOURCE_SIZE,
  defaultRegionWindow,
  layoutRegions,
  pipAspectOf,
  pipRectOf,
  resultAspect,
  type CaptionPosition,
  type EditorLayout,
} from './editorLayout';
import type { EditorRecipe, SubtitleMode } from './useClipEditorMockState';

/** 시안 px → 결과 폭 비. 미리보기 CSS 가 같은 기준(240)을 쓴다 */
export const UI_REFERENCE_WIDTH = 240;

/** 분할 경계선 — 시안 「두 영역 사이 2px 라인」, 색은 포인트 토큰(--pc-color-accent) 값 */
export const SPLIT_DIVIDER_PX = 2;
export const SPLIT_DIVIDER_COLOR = '#586fc4';
/** 작은 화면 모서리 — 시안 4px */
export const PIP_RADIUS_PX = 4;
/** 자막 위·아래 여백 — 시안 10px */
export const CAPTION_MARGIN_PX = 10;
/** 작은 화면 그림자 — 시안 0 4px 12px, 검정 45%. 렌더가 같은 모양으로 그린다 */
export const PIP_SHADOW = { offsetPx: 4, blurPx: 12, alpha: 0.45 } as const;
/** 흐린 바탕 세기 100 = 표준편차가 결과 폭의 4% (계약6 7절, 렌더와 같은 식) */
export const BLUR_SIGMA_AT_FULL = 0.04;

const ratio = (px: number) => px / UI_REFERENCE_WIDTH;

/** 이 레시피 모양의 모양 칸만 — 저장된 편집본에서 되살리는 범위다 */
export type EditorLook = Pick<
  EditorRecipe,
  | 'layout'
  | 'splitRatio'
  | 'splitBorder'
  | 'captionPosition'
  | 'pip'
  | 'pipBorder'
  | 'centerFill'
  | 'crops'
  | 'subtitleMode'
>;

/** 칸마다 원본에서 잘라 쓰는 자리. 화면(편집 훅)과 저장이 같은 계산을 쓴다 */
export interface RegionCrop {
  id: string;
  /** 소스에서 잘라낼 비율(가로/세로) */
  aspect: number;
  crop: CropRect;
  /** 그려지는 확대율 — 자리 모양이 바뀌어 하한이 오르면 저장된 값보다 클 수 있다 */
  zoom: number;
}

export function regionCropsOf(
  look: Pick<EditorLook, 'layout' | 'splitRatio' | 'pip' | 'crops'>,
): RegionCrop[] {
  // 작은 화면 비율은 결과의 자리 모양에서 온다 — 소스에서 잘라낼 프레임과 결과 배치에 같이 걸린다
  const pipRatio = pipAspectOf(look.pip, resultAspect(look.layout));
  return layoutRegions(look.layout, look.splitRatio, pipRatio).map((region) => {
    // 크롭 경계는 소스 해상도로 잰다 — 계약6이 종횡비를 픽셀 기준으로 검증하기 때문이다
    const maxSize = maxCropSize(region.aspect, SOURCE_SIZE.width, SOURCE_SIZE.height);
    const window = look.crops[region.id] ?? defaultRegionWindow(look.layout, region.id);
    return {
      id: region.id,
      aspect: region.aspect,
      crop: cropRectOf(window, maxSize),
      zoom: Math.max(window.zoom, minZoomOf(maxSize)),
    };
  });
}

const FULL = { x: 0, y: 0, w: 1, h: 1 };

/** 분할 지분 — 레이아웃 정의와 같은 가둠(10~90%) */
function splitTop(splitRatio: number): number {
  return Math.min(90, Math.max(10, splitRatio)) / 100;
}

/** 화면 → 계약6 v2 출력 한 벌(세로 9:16) */
export function outputFromLook(look: EditorLook): RecipeOutputV2 {
  const crops = regionCropsOf(look);
  const cropOf = (id: string) => crops.find((c) => c.id === id)!.crop;
  const base = { outputId: 'o1', aspect: 'VERT_9_16' as const };
  switch (look.layout) {
    case 'split': {
      const top = splitTop(look.splitRatio);
      return {
        ...base,
        layers: [
          { crop: cropOf('top'), box: { x: 0, y: 0, w: 1, h: top } },
          { crop: cropOf('bottom'), box: { x: 0, y: top, w: 1, h: 1 - top } },
        ],
        ...(look.splitBorder
          ? {
              dividers: [
                { y: top, thickness: ratio(SPLIT_DIVIDER_PX), color: SPLIT_DIVIDER_COLOR },
              ],
            }
          : {}),
      };
    }
    case 'center': {
      // 원본 비율(16:9)을 결과 폭 전부로 가운데 놓는다 — 높이 = 결과 비율 ÷ 원본 비율
      const main = crops[0]!;
      const h = resultAspect('center') / main.aspect;
      const background: RecipeBackground =
        look.centerFill.kind === 'blur'
          ? { kind: 'BLUR', strength: Math.round(look.centerFill.strength) }
          : { kind: 'COLOR', color: look.centerFill.color };
      return {
        ...base,
        background,
        layers: [{ crop: main.crop, box: { x: 0, y: (1 - h) / 2, w: 1, h } }],
      };
    }
    case 'crop': {
      const pip = pipRectOf(look.pip);
      return {
        ...base,
        layers: [
          { crop: cropOf('main'), box: FULL },
          {
            crop: cropOf('pip'),
            box: pip,
            ...(look.pipBorder.on
              ? {
                  frame: {
                    width: ratio(look.pipBorder.width),
                    color: look.pipBorder.color,
                    radius: ratio(PIP_RADIUS_PX),
                    shadow: true,
                  },
                }
              : {}),
          },
        ],
      };
    }
    case 'vert':
    case 'horiz':
    default:
      // 가로는 고를 수 없다(레이아웃 목록에서 잠김) — 세로 한 장이 결과를 꽉 채운다
      return { ...base, layers: [{ crop: cropOf('main'), box: FULL }] };
  }
}

/**
 * 저장할 출력 전부. 세로 한 벌은 화면 그대로 만들고, 편집기가 그리지 않는 다른 비율(정사각 등)은 저장된 것을 그대로 둔다 —
 * 편집기에 그 칸이 없다고 다시 저장할 때 버리면 이미 있던 영상 한 벌이 조용히 사라진다. 옛 v1 출력은 v2 모양(꽉 채우는
 * 층 하나)으로 옮긴다. 세로 출력의 이름(outputId)도 저장된 것을 잇는다 — 완성 영상 파일 이름이 이 값이다.
 */
export function outputsFor(look: EditorLook, saved: RecipeDocument | null): RecipeOutputV2[] {
  const savedOutputs: RecipeOutputV2[] = (saved?.outputs ?? []).map((o) =>
    'crop' in o
      ? { outputId: o.outputId, aspect: o.aspect, layers: [{ crop: o.crop, box: FULL }] }
      : o,
  );
  const main = outputFromLook(look);
  // 세로 출력은 저장된 자리에서 갈아 끼운다 — 순서가 바뀌면 고치지 않은 편집본도 「바뀌었다」가 된다
  if (savedOutputs.some((o) => o.aspect === 'VERT_9_16'))
    return savedOutputs.map((o) =>
      o.aspect === 'VERT_9_16' ? { ...main, outputId: o.outputId } : o,
    );
  // 세로가 없던 편집본 — 이름이 겹치면 저장 문이 거절하므로 안 쓰인 이름을 고른다
  const taken = new Set(savedOutputs.map((o) => o.outputId));
  let id = main.outputId;
  for (let n = 2; taken.has(id); n += 1) id = `o${n}`;
  return [...savedOutputs, { ...main, outputId: id }];
}

const MODE_TO_CONTRACT: Readonly<Record<SubtitleMode, RecipeSubtitles['mode']>> = {
  'burn-cc': 'BURN_AND_CC',
  burn: 'BURN_ONLY',
  cc: 'CC_ONLY',
};

/** 자막 자리 — 위·아래는 여백 10px 만큼 안쪽, 「경계」는 분할 지분 자리(미리보기와 같다) */
export function subtitlePositionOf(
  look: Pick<EditorLook, 'captionPosition' | 'splitRatio'>,
): NonNullable<RecipeSubtitles['position']> {
  // 여백은 결과 폭 비라 높이 비로 옮긴다(9:16)
  const margin = ratio(CAPTION_MARGIN_PX) * resultAspect('vert');
  switch (look.captionPosition) {
    case 'top':
      return { anchor: 'TOP', y: margin };
    case 'edge':
      return { anchor: 'MIDDLE', y: look.splitRatio / 100 };
    case 'bottom':
    default:
      return { anchor: 'BOTTOM', y: 1 - margin };
  }
}

/**
 * 저장할 자막. 자막 줄은 편집기가 만들지 않는다(AI 자막은 2번 몫) — 저장된 편집본에 있던 줄을 그대로 두고
 * 화면에서 고른 방식(번인·CC)과 자리만 싣는다. 줄도 없고 저장된 자막도 없으면 칸을 뺀다(계약6: 생략 = 자막 없음).
 */
export function subtitlesFor(
  look: Pick<EditorLook, 'subtitleMode' | 'captionPosition' | 'splitRatio'>,
  segments: RecipeSubtitles['segments'] | null,
): RecipeSubtitles | undefined {
  if (segments === null) return undefined;
  return {
    mode: MODE_TO_CONTRACT[look.subtitleMode],
    segments,
    position: subtitlePositionOf(look),
  };
}

// ── 레시피 → 화면 ──

/** 작은 화면이 없는 레이아웃에서 자리 계산에 넘기는 값 — 그 레이아웃은 작은 화면 비율을 안 쓴다 */
const DEFAULT_PIP_FOR_READ = { x: 0, y: 0, w: 1, h: 1 };

const near = (a: number, b: number) => Math.abs(a - b) < 1e-6;

/** 저장된 사각형을 그 자리의 창(중심·확대율)으로 되돌린다 */
function windowOf(rect: CropRect, regionAspect: number): CropWindow {
  const maxSize = maxCropSize(regionAspect, SOURCE_SIZE.width, SOURCE_SIZE.height);
  return {
    center: { x: rect.x + rect.w / 2, y: rect.y + rect.h / 2 },
    zoom: maxSize.w > 0 ? rect.w / maxSize.w : 1,
  };
}

function cropsFor(
  layout: EditorLayout,
  splitRatio: number,
  pip: EditorLook['pip'],
  rects: CropRect[],
): EditorLook['crops'] {
  const regions = layoutRegions(layout, splitRatio, pipAspectOf(pip, resultAspect(layout)));
  const crops: Record<string, CropWindow> = {};
  regions.forEach((region, index) => {
    const rect = rects[index];
    if (rect !== undefined) crops[region.id] = windowOf(rect, region.aspect);
  });
  return crops;
}

const MODE_FROM_CONTRACT: Readonly<Record<RecipeSubtitles['mode'], SubtitleMode>> = {
  BURN_AND_CC: 'burn-cc',
  BURN_ONLY: 'burn',
  CC_ONLY: 'cc',
};

function captionOf(subtitles: RecipeSubtitles | null | undefined): CaptionPosition | undefined {
  switch (subtitles?.position?.anchor) {
    case 'TOP':
      return 'top';
    case 'MIDDLE':
      return 'edge';
    case 'BOTTOM':
      return 'bottom';
    default:
      return undefined;
  }
}

/**
 * 저장된 편집본의 모양을 화면 값으로. 편집기가 만든 모양(세로·분할·중앙·크롭)만 알아본다 —
 * 모르는 모양이면 모양 칸을 비워 편집기 기본값으로 연다(저장하면 그때 화면 모양으로 바뀐다).
 */
export function lookFromDocument(doc: RecipeDocument): Partial<EditorLook> {
  const subtitles = doc.subtitles;
  const position = subtitles && 'position' in subtitles ? subtitles.position : undefined;
  const middle = position?.anchor === 'MIDDLE' ? position : undefined;
  const common: Partial<EditorLook> = {
    ...(subtitles ? { subtitleMode: MODE_FROM_CONTRACT[subtitles.mode] } : {}),
    ...(captionOf(subtitles) ? { captionPosition: captionOf(subtitles) } : {}),
    // 「경계」 자막의 자리는 분할 지분이다 — 분할이 아닌 레이아웃에서도 저장한 자리로 돌아오게 되살린다(분할이면 아래가 덮는다)
    ...(middle ? { splitRatio: Math.round(middle.y * 100) } : {}),
  };
  // 편집기가 그리는 것은 세로 한 벌이다 — 첫 출력이 아니라 세로 출력을 읽는다
  const first = (doc.outputs as { aspect: string }[]).find((o) => o.aspect === 'VERT_9_16') as
    { crop?: CropRect } | undefined;
  if (first === undefined) return common;
  if (doc.schemaVersion === 1) {
    const crop = first.crop!;
    return { ...common, layout: 'vert', crops: cropsFor('vert', 50, DEFAULT_PIP_FOR_READ, [crop]) };
  }
  const output = first as RecipeOutputV2;
  const [a, b] = output.layers;
  if (a === undefined) return common;
  const isFull = (box: CropRect) =>
    near(box.x, 0) && near(box.y, 0) && near(box.w, 1) && near(box.h, 1);

  if (output.layers.length === 1 && isFull(a.box)) {
    return {
      ...common,
      layout: 'vert',
      crops: cropsFor('vert', 50, DEFAULT_PIP_FOR_READ, [a.crop]),
    };
  }
  if (output.layers.length === 1 && near(a.box.w, 1)) {
    const bg = output.background;
    return {
      ...common,
      layout: 'center',
      centerFill:
        bg?.kind === 'COLOR'
          ? { kind: 'color', color: bg.color }
          : { kind: 'blur', strength: bg?.kind === 'BLUR' ? bg.strength : 0 },
      crops: cropsFor('center', 50, DEFAULT_PIP_FOR_READ, [a.crop]),
    };
  }
  if (b !== undefined && output.layers.length === 2 && near(a.box.y, 0) && near(b.box.y, a.box.h)) {
    const splitRatio = Math.round(a.box.h * 100);
    return {
      ...common,
      layout: 'split',
      splitRatio,
      splitBorder: (output.dividers?.length ?? 0) > 0,
      crops: cropsFor('split', splitRatio, DEFAULT_PIP_FOR_READ, [a.crop, b.crop]),
    };
  }
  if (b !== undefined && output.layers.length === 2 && isFull(a.box)) {
    const pip = { ...b.box };
    return {
      ...common,
      layout: 'crop',
      pip,
      pipBorder: b.frame
        ? {
            on: true,
            width: Math.round(b.frame.width * UI_REFERENCE_WIDTH),
            color: b.frame.color,
          }
        : { ...DEFAULT_PIP_BORDER, on: false },
      crops: cropsFor('crop', 50, pip, [a.crop, b.crop]),
    };
  }
  return common;
}

/**
 * 두 본문이 같은 영상을 만드는가. 숫자는 1e-9 까지 같게 본다 — 저장된 자르는 자리를 화면 값(중심·확대율)으로
 * 되돌렸다가 다시 사각형으로 만들면 끝자리가 흔들린다. 문자열로 비교하면 고치지 않은 편집본도 「바뀌었다」가 되어
 * 영상 만들기 때마다 판이 오른다. 모양이 다르면(v1 ↔ v2) 다르다 — v1 편집본은 처음 한 번 v2로 다시 저장된다.
 * 값이 null 인 칸과 없는 칸은 같다.
 */
export function sameRecipe(a: unknown, b: unknown): boolean {
  if (typeof a === 'number' && typeof b === 'number') return Math.abs(a - b) < 1e-9;
  if (Array.isArray(a) && Array.isArray(b))
    return a.length === b.length && a.every((item, i) => sameRecipe(item, b[i]));
  if (a !== null && b !== null && typeof a === 'object' && typeof b === 'object') {
    // 빈 칸은 없는 칸이다 — clip은 자막 없는 편집본을 `"subtitles": null`로 주고 편집기는 칸을 뺀다
    const present = (o: object) =>
      Object.keys(o).filter((k) => (o as Record<string, unknown>)[k] != null);
    const ka = present(a);
    const kb = present(b);
    return (
      ka.length === kb.length &&
      ka.every((k) =>
        sameRecipe((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k]),
      )
    );
  }
  return a === b;
}
