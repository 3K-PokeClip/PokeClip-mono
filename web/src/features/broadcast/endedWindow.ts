// 끝난 방송을 읽는 창(그래프·시청자 기록·녹화). 라이브 화면과 지난 방송 목록이 같이 쓴다: 따로 정하면 목록은 「다시 보기
// 가능」인데 열면 「녹화 영상을 찾지 못했어요」였다(리뷰 6라운드). 지난 방송 채팅은 이 창이 아니라 영상 시각 둘레를 읽고,
// 하한은 녹화와 같은 자리(recordingSearchFrom)에서 보정 여유 60초만큼 앞이다(POK-324, useChatPanelMockState의
// REPLAY_LOWER_SLACK_MS. 녹화 하한에 맞추면 방송 시작 직후 채팅을 영영 못 읽었다)

/** 끝난 방송을 읽는 창의 최대 길이. 수집기가 한 번에 주는 시청자 기록 720점(1분 간격)과 같은 길이다 */
const ENDED_TAIL_MS = 12 * 60 * 60_000;

/**
 * 끝난 방송의 그래프·시청자 기록을 읽기 시작할 시각(epoch ms). 시작~끝이되 12시간이 넘거나 시작 시각을 모르면(종료 알림이
 * 먼저 와서 생긴 자리표시 방송) 끝 12시간이다. 둘이 이 하나로 같은 창을 읽는다: 따로 고르면 최고·평균은 있는데 선이 없었다
 * (리뷰 4라운드). 채팅은 이 창으로 자르지 않는다: 영상 시각 둘레만 읽고, 긴 방송의 앞쪽 카드 시점 채팅도 그 방송의 것이다(POK-324)
 */
export function endedWindowStart(startedAt: number | null, endedAt: number): number {
  return startedAt !== null && endedAt - startedAt <= ENDED_TAIL_MS
    ? startedAt
    : endedAt - ENDED_TAIL_MS;
}

/**
 * 녹화를 찾을 하한(epoch ms). 시작 시각을 알면 방송 시작이다: 12시간이 넘어도 앞쪽 카드에 영상이 있어야 한다. 모르면 위 창과 같은
 * 끝 12시간이다: 하한이 없으면 같은 송출 키로 한 다른 날 방송의 녹화가 섞였다(리뷰 5라운드). 같은 12시간 안에 같은 키로 한 앞
 * 회차는 시작 시각 없이는 가를 수 없다(clip이 자리표시 방송의 시작 시각을 채워야 닫힌다)
 */
export function recordingSearchFrom(
  startedAt: number | null,
  endedAt: number | null,
): number | null {
  if (startedAt !== null) return startedAt;
  return endedAt === null ? null : endedWindowStart(null, endedAt);
}
