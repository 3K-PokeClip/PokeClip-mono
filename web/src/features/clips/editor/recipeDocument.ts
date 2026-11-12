import { TRACK_COUNT } from '@/api/audioTracks';
import type { RecipeDocument, RecipeDocumentV2 } from '@/api/clipEditor';
import { outputsFor, subtitlesFor } from './recipeLook';
import type { EditorRecipe } from './useClipEditorMockState';

// 편집기 화면값(EditorRecipe) → 저장할 편집본 문서(계약6 v2). 편집기의 저장 · 영상 만들기와 카드에서 바로 보관함 저장(saveCardDraft)이
// 같이 쓴다: 따로 두면 편집기 기본값이 바뀔 때 카드 저장본만 옛 모양으로 남는다(PR #480 리뷰).

/** 편집기 트랙의 화면 키(0 = 최종 믹스, 1~5 = 소스 트랙) */
export const editorTrackId = (index: number) => `track-${index}`;

/**
 * @param cut 저장 판정(savable · cardDraftVerdict)이 낸 컷(목록 PDT 축 ms). 다시 셈하지 않는다 — 받은 값 그대로라 다시 열고
 *   손대지 않고 저장하면 같은 값이다. 길이(5초~3분)는 판정이 이미 막았다
 * @param saved 다시 여는 편집본의 저장 문서. 편집기가 그리지 않는 출력 · 자막 줄을 잇는다. 새 편집본이면 null
 */
export function recipeDocumentOf({
  streamId,
  cut: { inMs, outMs },
  recipe,
  saved,
}: {
  streamId: string;
  cut: { inMs: number; outMs: number };
  recipe: EditorRecipe;
  saved: RecipeDocument | null;
}): RecipeDocumentV2 {
  const cut = { inAtMs: inMs, outAtMs: outMs };
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
