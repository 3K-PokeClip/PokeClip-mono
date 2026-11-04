import {
  createRecipe,
  fetchBroadcast,
  type RecipeDocumentV2,
  type RecipeSnapshot,
} from '@/api/clipEditor';
import type { RecordingTimeline } from '@/features/player/recordingTimeline';
import { editorTimelineOf, loadRecording } from './editorMedia';
import { cardFromList } from './prefetchCardEditor';
import { CardDraftNotReadyError } from './cardDraftErrors';
import { recipeDocumentOf } from './recipeDocument';
import { initialRecipe } from './useClipEditorMockState';

export { CardDraftNotReadyError };

// 하이라이트 카드에서 바로 보관함에 저장한다 — 편집기를 열지 않고, 편집기를 카드에서 처음 열었을 때와 같은 편집본(카드 구간 ·
// 기본 모양 · 첫 소리 트랙)을 만든다. 보관함에는 「편집 중」 편집본으로 서고, 출처 카드를 남긴다(POK-338과 같은 쿼리).
// 영상을 받지 않으므로 편집 미리 받기(POK-499)가 끝나기 전에도 된다.

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
 * 카드를 보관함에 저장한다(편집본 하나를 새로 만든다). 카드 목록은 편집 미리 받기가 기억해 둔 것을 같이 쓴다.
 *
 * 시각 기준점을 확실히 알 때만 저장한다: 기준점이 물러난 채 저장하면 편집기에서 열 때 기준점이 바뀌어 구간이 밀린다(렌더도 그 절대
 * 시각을 자른다). 기준점 없는 방송 줄은 기억해 둔 것을 쓰지 않고 새로 읽는다: 그 줄은 20초 동안 다시 쓰이는데, 그 사이 녹화가
 * 생기면 녹화 시작이 기준점이 된다 — 서버 기준점은 `playback_pdt − start_pts_ms`라 pts가 앞 방송에서 이어지면 녹화 시작과 몇
 * 시간씩 갈린다(PR #480 2차 리뷰, 1시간 재현). 기준점이 찬 줄은 기억해 둔 것을 그대로 쓴다(편집기도 그 줄로 연다): 끝난 옛 방송은
 * 다시 읽으려면 지난 방송 목록을 여러 쪽 넘겨야 한다(3차 리뷰).
 * - 서버 기준점(timelineOriginAt)이 있으면 그것이다. 녹화는 묻지 않는다(기준점 계산에 안 쓰이고, 그 실패가 저장을 막지 않게)
 * - 없는데 방송 중이면 녹화가 있어도 기다리게 한다(CardDraftNotReadyError): 조각이 생기면 곧 서버 기준점이 생긴다
 * - 없는 끝난 방송이면(조각 장부가 없다 — 편집기도 녹화 시작이나 방송 시작을 쓴다) 녹화 목록을 한 번 묻는다. 목록을 못 물은 것은
 *   실패로, 녹화가 정말 없는 것은 null(방송 시작)로 가른다(loadRecording strict). 미리 받기가 기억해 둔 녹화는 쓰지 않는다: 그
 *   「없음」은 묻기 실패를 접은 것이거나 낡은 것일 수 있고, 다시 확인하려 같은 목록을 두 번 묻게 된다(3차 리뷰)
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
    fetchBroadcast(streamId, undefined, { fresh: (row) => !row.timelineOriginAt }),
  ]);
  if (card === null) throw new Error(`card ${cardId} not found`);
  if (broadcast === null || broadcast.startedAt === null) throw new Error('broadcast not started');
  let recording: RecordingTimeline | null = null;
  if (!broadcast.timelineOriginAt) {
    if (broadcast.endedAt === null) throw new CardDraftNotReadyError();
    recording = await loadRecording(broadcast, { strict: true });
  }
  const { base } = editorTimelineOf(broadcast, recording);
  if (!stillCurrent()) throw new Error('session changed');
  return createRecipe(streamId, cardDraftDocument(streamId, base, card.window), cardId);
}
