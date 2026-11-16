// 미디어가 있는 구간 여럿을 한 시간축에 놓는다(POK-253). 송출이 끊기거나 녹화기가 다시 서면 한 방송의 영상이 구간 여럿이
// 되고, 구간 사이 틈에는 미디어가 없다. 편집기 원천(source/editorSourceTimeline)이 ③ 되감기 목록으로 구간을 짓고, 미리보기
// 창(source/editorWindow)이 여기서 그 자리에서 틀 수 있는 한 덩어리를 고른다.
//
// 시간축은 틈도 남긴다 — 틈을 접으면 틈 뒤 카드가 전부 틈 길이만큼 어긋난다.

/** 미디어가 있는 구간 하나 — 축의 초로 [fromSeconds, toSeconds) */
export interface RecordingPiece {
  fromSeconds: number;
  toSeconds: number;
}

/**
 * 그 자리에서 틀 수 있는 한 덩어리(구간은 시간순이고 겹치지 않는다). 틈 안이면 다음 구간 시작부터다. 마지막 구간 끝을
 * 지났으면 null
 */
export function playableFrom(
  timeline: { pieces: readonly RecordingPiece[] },
  seconds: number,
): RecordingPiece | null {
  const piece = timeline.pieces.find((p) => p.toSeconds > seconds);
  if (piece === undefined) return null;
  return { fromSeconds: Math.max(seconds, piece.fromSeconds), toSeconds: piece.toSeconds };
}
