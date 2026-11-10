// 카드에서 바로 보관함 저장(saveCardDraft)의 오류 종류. 따로 둔 까닭: 라이브 화면은 이 종류만 알면 되는데, saveCardDraft를 정적으로
// 가져오면 편집기 상태 훅 묶음(useClipEditorMockState 등)이 라이브 라우트에 같이 실린다 — 저장은 누를 때 import()로 불러온다(PR #480 리뷰)

/**
 * 아직 저장할 수 없다 — 시각 기준점을 모른다(방송 중인데 서버 기준점이 아직 없다). 방송 시작 직후의 카드다: 조각이 생기면 된다.
 * 부르는 쪽은 실패와 달리 「녹화가 올라온 뒤 다시」로 알린다
 */
export class CardDraftNotReadyError extends Error {
  constructor() {
    super('timeline origin not known yet');
    this.name = 'CardDraftNotReadyError';
  }
}
