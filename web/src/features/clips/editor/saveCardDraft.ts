import {
  createRecipe,
  fetchBroadcast,
  mediaStreamId,
  type RecipeDocumentV2,
  type RecipeSnapshot,
} from '@/api/clipEditor';
import { lookupRecordingSpans } from '@/api/mediaPlayback';
import { recordingTimeline } from '@/features/player/recordingTimeline';
import { editorTimelineOf } from './editorMedia';
import { cardFromList, recordingOf } from './prefetchCardEditor';
import { recipeDocumentOf } from './recipeDocument';
import { initialRecipe } from './useClipEditorMockState';

// 하이라이트 카드에서 바로 보관함에 저장한다 — 편집기를 열지 않고, 편집기를 카드에서 처음 열었을 때와 같은 편집본(카드 구간 ·
// 기본 모양 · 첫 소리 트랙)을 만든다. 보관함에는 「편집 중」 편집본으로 서고, 출처 카드를 남긴다(POK-338과 같은 쿼리).
// 영상을 받지 않으므로 편집 미리 받기(POK-499)가 끝나기 전에도 된다.

/**
 * 아직 저장할 수 없다 — 시각 기준점을 모른다(서버 기준점이 없고 녹화도 아직 없다). 방송 시작 직후의 카드다: 녹화가 올라오면 된다.
 * 부르는 쪽은 실패와 달리 「녹화가 올라온 뒤 다시」로 알린다
 */
export class CardDraftNotReadyError extends Error {
  constructor() {
    super('timeline origin not known yet');
    this.name = 'CardDraftNotReadyError';
  }
}

/**
 * 카드 구간으로 만든 처음 편집본. 편집기가 카드에서 처음 열렸을 때의 화면값(initialRecipe — 모양 · 트랙 0만 켬)을 편집기와 같은
 * 함수(recipeDocumentOf)로 문서로 바꾼다: 따로 조립하면 편집기 기본값이 바뀔 때 카드 저장본만 옛 모양으로 남는다.
 *
 * @param base 카드 · 조각 ms의 0초(editorTimelineOf) — 편집기와 같은 값이라야 다시 열 때 제자리다
 */
export function cardDraftDocument(
  streamId: string,
  base: number,
  window: { startMs: number; endMs: number },
): RecipeDocumentV2 {
  const range = { startSeconds: window.startMs / 1000, endSeconds: window.endMs / 1000 };
  return recipeDocumentOf({ streamId, base, recipe: initialRecipe(range), saved: null });
}

/**
 * 카드를 보관함에 저장한다(편집본 하나를 새로 만든다). 카드 목록 · 녹화는 편집 미리 받기가 기억해 둔 것을 같이 쓴다.
 *
 * 시각 기준점을 확실히 알 때만 저장한다: 기준점이 방송 시작으로 물러난 채 저장하면, 녹화가 올라온 뒤 편집기에서 열 때 기준점이
 * 첫 조각으로 바뀌어 구간이 수 초~수십 초 밀린다(렌더도 그 절대 시각을 자른다). 서버 기준점(timelineOriginAt)이 있으면 그것이다.
 * 없고 녹화도 없으면 — 방송 중이면 조각이 아직 0개라 기다리게 하고(CardDraftNotReadyError), 끝난 방송이면 목록을 못 물은 것(실패)과
 * 녹화가 정말 없는 것을 가른다: recordingOf는 묻기 실패를 「녹화 없음」으로 접는다(fetchRecordingSpans).
 *
 * @param stillCurrent 저장을 시작한 세션이 그대로인가(captureSession). 서버에 보내기 직전에 본다: 그 사이 계정이 바뀌면 다른 계정
 *   이름으로 편집본을 만들게 된다
 */
export async function saveCardDraft(
  streamId: string,
  cardId: string,
  { stillCurrent = () => true }: { stillCurrent?: () => boolean } = {},
): Promise<RecipeSnapshot> {
  const [card, broadcast] = await Promise.all([
    cardFromList(streamId, cardId),
    fetchBroadcast(streamId),
  ]);
  if (card === null) throw new Error(`card ${cardId} not found`);
  if (broadcast === null || broadcast.startedAt === null) throw new Error('broadcast not started');
  let recording = await recordingOf(broadcast);
  if (recording === null && !broadcast.timelineOriginAt) {
    if (broadcast.endedAt === null) throw new CardDraftNotReadyError();
    const spans = await lookupRecordingSpans(mediaStreamId(broadcast));
    if (spans === null) throw new Error('recording lookup failed');
    // 기억해 둔 「없음」이 낡았을 수 있어 방금 물은 목록으로 다시 세운다(정말 없으면 그대로 null — 편집기도 방송 시작을 쓴다)
    recording = recordingTimeline(
      spans,
      Date.parse(broadcast.startedAt),
      Date.parse(broadcast.endedAt),
    );
  }
  const { base } = editorTimelineOf(broadcast, recording);
  if (!stillCurrent()) throw new Error('session changed');
  return createRecipe(streamId, cardDraftDocument(streamId, base, card.window), cardId);
}
