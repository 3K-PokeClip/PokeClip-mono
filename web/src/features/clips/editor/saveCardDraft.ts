import {
  createRecipe,
  CUT_MAX_MS,
  CUT_MIN_MS,
  fetchBroadcast,
  type RecipeDocumentV2,
  type RecipeSnapshot,
} from '@/api/clipEditor';
import { editorTimelineOf } from './editorMedia';
import { cardFromList, recordingOf } from './prefetchCardEditor';
import { outputsFor } from './recipeLook';
import { initialRecipe } from './useClipEditorMockState';

// 하이라이트 카드에서 바로 보관함에 저장한다 — 편집기를 열지 않고, 편집기를 카드에서 처음 열었을 때와 같은 편집본(카드 구간 ·
// 기본 모양 · 첫 소리 트랙)을 만든다. 보관함에는 「편집 중」 편집본으로 서고, 출처 카드를 남긴다(POK-338과 같은 쿼리).
// 영상을 받지 않으므로 편집 미리 받기(POK-499)가 끝나기 전에도 된다.

/**
 * 카드 구간으로 만든 처음 편집본. StudioScreen의 documentFor가 카드에서 처음 연 편집기에서 만드는 것과 같아야 한다:
 * 구간 = 카드 창(5초~3분 밖이면 저장 문이 거절해 같은 식으로 맞춘다), 모양 = initialRecipe, 소리 = 트랙 0만 1배(trackSetup의 처음 값).
 * 자막은 편집기가 만들지 않아 칸을 뺀다(계약6: 생략 = 자막 없음).
 *
 * @param base 카드 · 조각 ms의 0초(editorTimelineOf) — 편집기와 같은 값이라야 다시 열 때 제자리다
 */
export function cardDraftDocument(
  streamId: string,
  base: number,
  window: { startMs: number; endMs: number },
): RecipeDocumentV2 {
  const range = { startSeconds: window.startMs / 1000, endSeconds: window.endMs / 1000 };
  const inAtMs = base + Math.round(range.startSeconds * 1000);
  const rawLength = Math.round((range.endSeconds - range.startSeconds) * 1000);
  const length = Math.min(CUT_MAX_MS, Math.max(CUT_MIN_MS, rawLength));
  return {
    schemaVersion: 2,
    streamId,
    cut: { inAtMs, outAtMs: inAtMs + length },
    outputs: outputsFor(initialRecipe(range), null),
    audio: { tracks: [{ trackId: 0, gain: 1 }] },
  };
}

/**
 * 카드를 보관함에 저장한다(편집본 하나를 새로 만든다). 카드 목록 · 녹화는 편집 미리 받기가 기억해 둔 것을 같이 쓴다.
 * 녹화를 못 읽으면 저장하지 않고 던진다: 기준점이 방송 시작으로 물러나 편집기에서 열 때와 수십 초 어긋난 구간이 저장된다.
 */
export async function saveCardDraft(streamId: string, cardId: string): Promise<RecipeSnapshot> {
  const [card, broadcast] = await Promise.all([
    cardFromList(streamId, cardId),
    fetchBroadcast(streamId),
  ]);
  if (card === null) throw new Error(`card ${cardId} not found`);
  if (broadcast === null || broadcast.startedAt === null) throw new Error('broadcast not started');
  const recording = await recordingOf(broadcast);
  const { base } = editorTimelineOf(broadcast, recording);
  return createRecipe(streamId, cardDraftDocument(streamId, base, card.window), cardId);
}
