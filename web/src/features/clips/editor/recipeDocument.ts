import { TRACK_COUNT } from '@/api/audioTracks';
import {
  CUT_MAX_MS,
  CUT_MIN_MS,
  type RecipeDocument,
  type RecipeDocumentV2,
} from '@/api/clipEditor';
import { outputsFor, subtitlesFor } from './recipeLook';
import type { EditorRecipe } from './useClipEditorMockState';

// 편집기 화면값(EditorRecipe) → 저장할 편집본 문서(계약6 v2). 편집기의 저장 · 영상 만들기와 카드에서 바로 보관함 저장(saveCardDraft)이
// 같이 쓴다: 따로 두면 편집기 기본값이 바뀔 때 카드 저장본만 옛 모양으로 남는다(PR #480 리뷰).

/** 편집기 트랙의 화면 키(0 = 최종 믹스, 1~5 = 소스 트랙) */
export const editorTrackId = (index: number) => `track-${index}`;

/**
 * @param base 편집기 시간축 0초의 절대 ms(editorTimelineOf) — 구간(방송 시작 기준 초)을 계약6 컷(절대 ms)으로 옮긴다
 * @param saved 다시 여는 편집본의 저장 문서. 편집기가 그리지 않는 출력 · 자막 줄을 잇는다. 새 편집본이면 null
 */
export function recipeDocumentOf({
  streamId,
  base,
  recipe,
  saved,
}: {
  streamId: string;
  base: number;
  recipe: EditorRecipe;
  saved: RecipeDocument | null;
}): RecipeDocumentV2 {
  // 5초~3분 밖이면 저장 문이 거절하므로 여기서 맞춘다
  const inAtMs = base + Math.round(recipe.range.startSeconds * 1000);
  const rawLength = Math.round((recipe.range.endSeconds - recipe.range.startSeconds) * 1000);
  const length = Math.min(CUT_MAX_MS, Math.max(CUT_MIN_MS, rawLength));
  const cut = { inAtMs, outAtMs: inAtMs + length };
  // 켜 둔 트랙만 믹스된다(계약6). 0(최종 믹스)과 1~5는 같이 못 넣는다 — 소스 트랙을 하나라도 켰으면 0을 뺀다.
  let selected = Array.from({ length: TRACK_COUNT }, (_, i) => i)
    .filter((i) => !(recipe.trackMuted[editorTrackId(i)] ?? i !== 0))
    .map((i) => ({
      trackId: i,
      gain: Math.min(2, Math.max(0, (recipe.trackVolumes[editorTrackId(i)] ?? 100) / 100)),
    }));
  if (selected.some((t) => t.trackId !== 0)) selected = selected.filter((t) => t.trackId !== 0);
  if (selected.length === 0) selected = [{ trackId: 0, gain: 1 }];
  // 모양은 화면 그대로 v2로 싣는다(POK-252) — 저장된 편집본의 옛 출력을 두면 화면과 다른 영상이 나온다.
  // 자막 줄은 편집기가 만들지 않으므로 저장된 것을 그대로 두고, 방식·자리만 화면 값으로 바꾼다.
  const subtitles = subtitlesFor(recipe, saved?.subtitles?.segments ?? null);
  return {
    schemaVersion: 2,
    streamId,
    cut,
    outputs: outputsFor(recipe, saved),
    audio: { tracks: selected },
    ...(subtitles ? { subtitles } : {}),
  };
}
