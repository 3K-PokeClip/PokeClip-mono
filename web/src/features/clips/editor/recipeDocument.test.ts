import { describe, expect, it } from 'vitest';
import type { RecipeDocumentV2 } from '@/api/clipEditor';
import { editorTrackId, recipeDocumentOf } from './recipeDocument';
import { initialRecipe } from './useClipEditorMockState';

// 편집기 저장 · 영상 만들기와 카드에서 바로 보관함 저장이 같이 쓰는 문서 만들기(PR #480 리뷰로 StudioScreen에서 뺐다)

const BASE = Date.parse('2026-11-04T01:00:00Z');
const RANGE = { startSeconds: 40, endSeconds: 100 };

function docFor(
  muted: Record<string, boolean> = {},
  volumes: Record<string, number> = {},
  saved: RecipeDocumentV2 | null = null,
) {
  return recipeDocumentOf({
    streamId: 'S-1',
    base: BASE,
    recipe: initialRecipe(RANGE, muted, volumes),
    saved,
  });
}

describe('recipeDocumentOf', () => {
  it('구간은 기준점 + 방송 시작 기준 초, 5초~3분 밖이면 맞춘다', () => {
    expect(docFor().cut).toEqual({ inAtMs: BASE + 40_000, outAtMs: BASE + 100_000 });
    const short = recipeDocumentOf({
      streamId: 'S-1',
      base: BASE,
      recipe: initialRecipe({ startSeconds: 10, endSeconds: 11 }),
      saved: null,
    });
    expect(short.cut).toEqual({ inAtMs: BASE + 10_000, outAtMs: BASE + 15_000 });
  });

  it('처음 값은 최종 믹스(트랙 0) 하나 · 1배다', () => {
    expect(docFor().audio.tracks).toEqual([{ trackId: 0, gain: 1 }]);
  });

  it('소스 트랙을 켜면 트랙 0을 뺀다(계약6: 0과 1~5는 같이 못 넣는다), 음량은 0~2배로 자른다', () => {
    const tracks = docFor(
      { [editorTrackId(2)]: false, [editorTrackId(3)]: false },
      { [editorTrackId(2)]: 150, [editorTrackId(3)]: 300 },
    ).audio.tracks;
    expect(tracks).toEqual([
      { trackId: 2, gain: 1.5 },
      { trackId: 3, gain: 2 },
    ]);
  });

  it('다 끄면 최종 믹스로 돌아간다 — 소리 없는 편집본을 만들지 않는다', () => {
    expect(docFor({ [editorTrackId(0)]: true }).audio.tracks).toEqual([{ trackId: 0, gain: 1 }]);
  });

  it('자막은 저장된 줄이 있을 때만 싣고, 방식 · 자리는 화면 값이다', () => {
    expect('subtitles' in docFor()).toBe(false);
    const saved: RecipeDocumentV2 = {
      ...docFor(),
      subtitles: {
        mode: 'CC_ONLY',
        segments: [{ startAtMs: BASE + 41_000, endAtMs: BASE + 43_000, text: '와' }],
      },
    };
    const subtitles = docFor({}, {}, saved).subtitles!;
    expect(subtitles.segments).toEqual(saved.subtitles!.segments);
    // initialRecipe의 방식은 「둘 다」(burn-cc)다: 저장된 CC_ONLY가 아니라 화면 값으로 싣는다
    expect(subtitles.mode).toBe('BURN_AND_CC');
  });
});
