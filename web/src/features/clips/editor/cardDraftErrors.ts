// 카드에서 바로 보관함 저장(saveCardDraft)의 오류 종류. 따로 둔 까닭: 라이브 화면은 이 종류만 알면 되는데, saveCardDraft를 정적으로
// 가져오면 편집기 상태 훅 묶음(useClipEditorMockState 등)이 라이브 라우트에 같이 실린다 — 저장은 누를 때 import()로 불러온다(PR #480 리뷰)

/**
 * 바로 저장할 수 없다 — clip 이 카드 창 두 끝(playerWindow)을 주지 않았거나(칸 없음 · 서버가 못 정한 null) 그 길이가 5–180초
 * 밖이다(POK-473 E7 · 설계 prw2 3.4). 두 끝이 없으면 남는 카드 창은 어림(estimate)이라 저장 값이 아니고, 길이가 밖이면 편집기
 * 저장과 같은 길이 규칙에 걸려 늘리거나 줄여 대신 저장하지 않는다. 부르는 쪽은 실패와 달리 「편집기에서 열기」로 안내한다 —
 * 편집기에서 끝점을 정하면 저장된다
 */
export class CardNeedsEditorError extends Error {
  constructor() {
    super('card needs the editor to set its range');
    this.name = 'CardNeedsEditorError';
  }
}
