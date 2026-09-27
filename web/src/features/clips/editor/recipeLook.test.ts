import { describe, expect, it } from 'vitest';
import type { RecipeDocument } from '@/api/clipEditor';
import { DEFAULT_CENTER_FILL, DEFAULT_PIP, DEFAULT_PIP_BORDER } from './editorLayout';
import {
  lookFromDocument,
  outputFromLook,
  outputsFor,
  regionCropsOf,
  sameRecipe,
  subtitlePositionOf,
  subtitlesFor,
  type EditorLook,
} from './recipeLook';

// 화면 모양 ↔ 계약6 v2 (POK-252). 저장했다 다시 연 편집본이 제자리로 와야 한다 — 두 방향을 같이 잰다.

const BASE: EditorLook = {
  layout: 'split',
  splitRatio: 50,
  splitBorder: true,
  captionPosition: 'bottom',
  pip: DEFAULT_PIP,
  pipBorder: DEFAULT_PIP_BORDER,
  centerFill: DEFAULT_CENTER_FILL,
  crops: {},
  subtitleMode: 'burn-cc',
};

function docOf(look: EditorLook): RecipeDocument {
  return {
    schemaVersion: 2,
    streamId: 's',
    cut: { inAtMs: 0, outAtMs: 10_000 },
    outputs: [outputFromLook(look)],
    audio: { tracks: [{ trackId: 0, gain: 1 }] },
    subtitles: subtitlesFor(look, []),
  };
}

/** 저장 → 다시 열기 → 다시 저장이 같은 본문이다 */
function roundTrips(look: EditorLook) {
  const first = docOf(look);
  const reopened = { ...BASE, ...lookFromDocument(first) };
  expect(sameRecipe(docOf(reopened), first)).toBe(true);
  return reopened;
}

describe('화면 → 레시피 → 화면', () => {
  it('세로 — 옮긴 자르는 자리가 돌아온다', () => {
    const look = {
      ...BASE,
      layout: 'vert' as const,
      crops: { main: { center: { x: 0.3, y: 0.5 }, zoom: 0.8 } },
    };
    const back = roundTrips(look);
    expect(back.layout).toBe('vert');
    expect(back.crops.main?.zoom).toBeCloseTo(0.8, 9);
  });

  it('분할 — 지분·경계선·두 칸의 자리가 돌아온다', () => {
    const look = {
      ...BASE,
      splitRatio: 70,
      splitBorder: false,
      crops: {
        top: { center: { x: 0.4, y: 0.4 }, zoom: 0.6 },
        bottom: { center: { x: 0.7, y: 0.8 }, zoom: 0.3 },
      },
    };
    const back = roundTrips(look);
    expect(back).toMatchObject({ layout: 'split', splitRatio: 70, splitBorder: false });
  });

  it('중앙 — 흐림 세기와 단색이 돌아온다', () => {
    expect(
      roundTrips({ ...BASE, layout: 'center', centerFill: { kind: 'blur', strength: 25 } })
        .centerFill,
    ).toEqual({
      kind: 'blur',
      strength: 25,
    });
    expect(
      roundTrips({ ...BASE, layout: 'center', centerFill: { kind: 'color', color: '#1c2440' } })
        .centerFill,
    ).toEqual({ kind: 'color', color: '#1c2440' });
  });

  it('크롭 — 작은 화면 자리·모양과 테두리가 돌아온다, 끈 테두리는 꺼진 채다', () => {
    const pip = { x: 0.1, y: 0.1, w: 0.5, h: 0.2 };
    const on = roundTrips({
      ...BASE,
      layout: 'crop',
      pip,
      pipBorder: { on: true, width: 2, color: '#d44697' },
    });
    expect(on.pip).toEqual(pip);
    expect(on.pipBorder).toEqual({ on: true, width: 2, color: '#d44697' });
    expect(
      roundTrips({ ...BASE, layout: 'crop', pip, pipBorder: { ...DEFAULT_PIP_BORDER, on: false } })
        .pipBorder.on,
    ).toBe(false);
  });

  it('자막 방식과 자리가 돌아온다', () => {
    const back = roundTrips({ ...BASE, subtitleMode: 'cc', captionPosition: 'top' });
    expect(back.subtitleMode).toBe('cc');
    expect(back.captionPosition).toBe('top');
    expect(roundTrips({ ...BASE, captionPosition: 'edge', splitRatio: 60 }).captionPosition).toBe(
      'edge',
    );
  });
});

describe('화면 → 레시피', () => {
  it('자르는 자리는 칸의 비율과 픽셀로 같다 — 렌더의 ±1% 검사를 넘는다', () => {
    for (const look of [
      { ...BASE, layout: 'vert' as const },
      { ...BASE, layout: 'split' as const, splitRatio: 60 },
      { ...BASE, layout: 'center' as const },
      { ...BASE, layout: 'crop' as const, pip: { x: 0.2, y: 0.6, w: 0.5, h: 0.3 } },
    ]) {
      for (const layer of outputFromLook(look).layers) {
        const cropRatio = (layer.crop.w * 1920) / (layer.crop.h * 1080);
        const boxRatio = (layer.box.w * 1080) / (layer.box.h * 1920);
        expect(cropRatio / boxRatio).toBeCloseTo(1, 6);
      }
    }
  });

  it('화면의 자르는 자리(편집 훅)와 저장하는 자리가 같은 계산이다', () => {
    const look = { ...BASE, layout: 'crop' as const };
    expect(outputFromLook(look).layers.map((l) => l.crop)).toEqual(
      regionCropsOf(look).map((c) => c.crop),
    );
  });

  it('자막 줄이 없으면 자막 칸을 빼고, 있으면 방식·자리를 싣는다', () => {
    expect(subtitlesFor(BASE, null)).toBeUndefined();
    const segments = [{ startAtMs: 1, endAtMs: 2, text: '가' }];
    expect(subtitlesFor({ ...BASE, subtitleMode: 'burn' }, segments)).toEqual({
      mode: 'BURN_ONLY',
      segments,
      position: { anchor: 'BOTTOM', y: 1 - (10 / 240) * (9 / 16) },
    });
  });

  it('자막 자리 — 위·아래는 10px 안쪽, 경계는 분할 지분', () => {
    expect(subtitlePositionOf({ captionPosition: 'top', splitRatio: 50 })).toEqual({
      anchor: 'TOP',
      y: (10 / 240) * (9 / 16),
    });
    expect(subtitlePositionOf({ captionPosition: 'edge', splitRatio: 70 })).toEqual({
      anchor: 'MIDDLE',
      y: 0.7,
    });
  });
});

describe('레시피 → 화면', () => {
  it('옛 편집본(v1)은 세로 한 장으로 연다', () => {
    const look = lookFromDocument({
      schemaVersion: 1,
      streamId: 's',
      cut: null,
      outputs: [
        { outputId: 'o1', aspect: 'VERT_9_16', crop: { x: 0.5, y: 0, w: 0.31640625, h: 1 } },
      ],
      audio: { tracks: [] },
    });
    expect(look.layout).toBe('vert');
    expect(look.crops?.main?.center.x).toBeCloseTo(0.5 + 0.31640625 / 2, 9);
    expect(look.crops?.main?.zoom).toBeCloseTo(1, 9);
  });

  it('편집기가 만들지 않는 모양이면 모양 칸을 비운다(편집기 기본값으로 연다)', () => {
    const look = lookFromDocument({
      schemaVersion: 2,
      streamId: 's',
      cut: null,
      outputs: [
        {
          outputId: 'o1',
          aspect: 'VERT_9_16',
          layers: [
            { crop: { x: 0, y: 0, w: 0.3, h: 0.3 }, box: { x: 0.1, y: 0.1, w: 0.3, h: 0.3 } },
            { crop: { x: 0, y: 0, w: 0.3, h: 0.3 }, box: { x: 0.5, y: 0.5, w: 0.3, h: 0.3 } },
          ],
        },
      ],
      audio: { tracks: [] },
    });
    expect(look).toEqual({});
  });
});

describe('outputsFor', () => {
  const v2 = (outputs: unknown[]): RecipeDocument =>
    ({
      schemaVersion: 2,
      streamId: 's',
      cut: null,
      outputs,
      audio: { tracks: [] },
    }) as RecipeDocument;
  const square = {
    outputId: 'o1',
    aspect: 'SQUARE_1_1' as const,
    layers: [{ crop: { x: 0.2, y: 0, w: 0.5625, h: 1 }, box: { x: 0, y: 0, w: 1, h: 1 } }],
  };

  it('세로는 저장된 자리에서 갈아 끼운다 — 순서를 안 바꾼다', () => {
    const vert = { ...outputFromLook(BASE), outputId: 'v' };
    const out = outputsFor(BASE, v2([square, vert]));
    expect(out.map((o) => [o.outputId, o.aspect])).toEqual([
      ['o1', 'SQUARE_1_1'],
      ['v', 'VERT_9_16'],
    ]);
  });

  it('세로가 없던 편집본이면 겹치지 않는 이름으로 붙인다', () => {
    const out = outputsFor(BASE, v2([square]));
    expect(out.map((o) => o.outputId)).toEqual(['o1', 'o2']);
  });
});

describe('sameRecipe', () => {
  it('숫자 끝자리 흔들림은 같다고 보고, 모양·칸이 다르면 다르다', () => {
    expect(sameRecipe({ a: [0.1 + 0.2] }, { a: [0.3] })).toBe(true);
    expect(sameRecipe({ a: 1 }, { a: 1, b: undefined })).toBe(true);
    expect(sameRecipe({ a: 1 }, { a: 1, b: 2 })).toBe(false);
    expect(sameRecipe({ a: [1, 2] }, { a: [1] })).toBe(false);
    expect(sameRecipe({ a: 0.3 }, { a: 0.3001 })).toBe(false);
  });
});
