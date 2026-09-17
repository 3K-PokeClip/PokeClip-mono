'use client';

import { useSearchParams } from 'next/navigation';

// 디자인 1b의 두 상태 — 방송 중이면 대시보드, 아니면 오프라인 안내 (POK-227).
//
// 왜 useLiveMockState에 안 넣는가: 그쪽 반환 형태는 POK-180(SSE 실연동)이 내부만 갈아끼우기로 한
// 계약이고, SSE는 이미 열린 방송 하나의 이벤트라 「지금 방송이 없다」를 말할 수 없다.
// 「방송이 있는가」는 SSE를 열기 전에 답해야 하는 다른 질문이라 훅을 가른다.
//
// 교체 방법: 원천 후보는 GET /api/clip/broadcasts?state=live(BroadcastListController) — 목록이 비면
// 오프라인이다(후보일 뿐 확정이 아니다). 붙이는 티켓이 조회 중 상태를 이 유니온에 더한다 — 지금은
// 조회가 없어 「모름」이 생기지 않는다.

export type LiveStatus = 'live' | 'offline';

export interface LiveStatusMockState {
  status: LiveStatus;
}

export function useLiveStatusMockState(): LiveStatusMockState {
  const searchParams = useSearchParams();
  // 방송 상태 원천이 없어 오프라인은 `?mock=offline`으로만 켠다. 개발에서만 켠다 — 프로덕션 번들에서는
  // 이 항이 통째로 죽어(NODE_ENV 치환) 주소를 쳐도 방송 중인 화면이 사라지지 않는다
  const offline = process.env.NODE_ENV !== 'production' && searchParams.get('mock') === 'offline';
  return { status: offline ? 'offline' : 'live' };
}
