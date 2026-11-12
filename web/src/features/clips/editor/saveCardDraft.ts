import { createRecipe, type RecipeDocumentV2, type RecipeSnapshot } from '@/api/clipEditor';
import { CardNeedsEditorError } from './cardDraftErrors';
import { cardFromList } from './prefetchCardEditor';
import { recipeDocumentOf } from './recipeDocument';
import { cardDraftVerdict } from './source/rangeEndpoints';
import { initialRecipe } from './useClipEditorMockState';

// 하이라이트 카드에서 바로 보관함에 저장한다 — 편집기를 열지 않고, 편집기를 카드에서 처음 열었을 때와 같은 편집본(카드 구간 ·
// 기본 모양 · 첫 소리 트랙)을 만든다. 보관함에는 「편집 중」 편집본으로 서고, 출처 카드를 남긴다(POK-338과 같은 쿼리).
// 영상을 받지 않으므로 편집 미리 받기(POK-499)가 끝나기 전에도 된다.
// 구간은 clip 이 카드 응답에 실어 준 창 두 끝(playerWindow · 목록 PDT 축)을 그대로 쓴다(POK-473 E7 · 설계 prw2 3.4). 그래서 시각
// 기준점 · 녹화 목록을 묻지 않는다 — 녹화 재생 서버(/playback)를 부르지 않는다.

/**
 * 카드 구간으로 만든 처음 편집본. 편집기가 카드에서 처음 열렸을 때의 화면값(initialRecipe — 모양 · 트랙 0만 켬)을 편집기와 같은
 * 함수(recipeDocumentOf)로 문서로 바꾼다: 따로 조립하면 편집기 기본값이 바뀔 때 카드 저장본만 옛 모양으로 남는다.
 *
 * @param cut 판정(cardDraftVerdict)이 낸 컷 — 받은 값 그대로 싣는다
 */
export function cardDraftDocument(
  streamId: string,
  cut: { inMs: number; outMs: number },
): RecipeDocumentV2 {
  // 문서의 컷은 cut 이 정한다 — 화면값의 구간은 문서에 실리지 않아 길이만 맞춰 둔다
  const range = { startSeconds: 0, endSeconds: (cut.outMs - cut.inMs) / 1000 };
  return recipeDocumentOf({ streamId, cut, recipe: initialRecipe(range), saved: null });
}

/**
 * 카드를 보관함에 저장한다(편집본 하나를 새로 만든다). 카드 목록은 편집 미리 받기가 기억해 둔 것을 같이 쓴다.
 *
 * clip 이 창 두 끝을 주지 않았거나(칸 없음 · 장면 없음 null) 길이가 5–180초 밖이면 CardNeedsEditorError 를 던진다: 카드 창은
 * 어림(estimate)이라 저장 값이 아니다 — 부르는 쪽은 「편집기에서 열기」로 안내한다.
 *
 * @param stillCurrent 저장을 시작한 세션이 그대로인가(captureSession). 서버에 보내기 직전에 본다: 그 사이 계정이 바뀌면 다른 계정
 *   이름으로 편집본을 만들게 된다
 */
export async function saveCardDraft(
  streamId: string,
  cardId: string,
  { stillCurrent = () => true }: { stillCurrent?: () => boolean } = {},
): Promise<RecipeSnapshot> {
  const card = await cardFromList(streamId, cardId);
  if (card === null) throw new Error(`card ${cardId} not found`);
  const verdict = cardDraftVerdict(card);
  if (!verdict.ok) throw new CardNeedsEditorError();
  if (!stillCurrent()) throw new Error('session changed');
  return createRecipe(streamId, cardDraftDocument(streamId, verdict), cardId);
}
