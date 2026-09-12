// 라이브 대시보드의 목업들이 함께 쓰는 값.
//
// 채팅 패널(useChatPanelMockState)과 실시간 통계(useLiveStatsMockState)는 같은 「분당」을 말해야
// 하는데, 한쪽이 다른 쪽 훅 모듈을 import하면 상수 하나 때문에 타이머·메시지 픽스처까지 끌고 오고,
// 실연동으로 한쪽 훅이 사라지는 날 관계없는 다른 쪽이 모듈 해석 실패로 함께 죽는다. 값만 여기 둔다.

/** 분당 평균 채팅 — 시안 1b 값. 채팅 하단 상태줄과 통계 지표가 이 하나를 같이 본다. */
export const MOCK_CHAT_RATE_PER_MINUTE = 402;
