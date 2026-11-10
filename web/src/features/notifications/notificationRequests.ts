'use client';

import { create } from 'zustand';
import type { AppNotification } from '@/api/notifications';
import { onSessionAdvance } from '@/stores/auth';

// 토스트 단추가 「지금 떠 있는 종」에게 일을 맡기는 우편함(POK-540, PR #481 리뷰).
// 토스트는 앱 전체의 ToastProvider에 남는데 종은 화면마다 바뀐다(홈 헤더 ↔ 사이드바). 토스트를 띄운 종의 함수를 그대로 쥐고 있으면
// 화면을 옮겨 그 종이 사라진 뒤 「알림 보기」를 눌러도 아무 창이 안 열린다. 그래서 단추는 여기에 부탁만 넣고, 지금 떠 있는 종이 받아 처리한다.
// 종이 떠오를 때 이미 들어와 있던 부탁은 앞 종 몫이라 처리하지 않는다(나중에 뜬 종이 엉뚱하게 창을 열지 않게). 단 종이 하나도
// 없을 때(전체 화면 편집기) 온 부탁은 「기다리는 부탁」으로 남겨, 다음에 뜨는 종이 한 번 처리한다 — 보낸 쪽은 종이 있는 홈으로
// 옮긴다(PR #481 2차 리뷰).

export type NotificationRequest =
  /** 알림 창 열기(「알림 보기」 · 「모아 보기」) */
  | { kind: 'panel' }
  /** 이 알림을 연다: 읽음으로 하고 그 화면으로 간다 */
  | { kind: 'item'; notification: AppNotification };

interface NotificationRequestState {
  /** 부탁마다 오르는 번호 — 종은 자기가 본 번호보다 큰 것만 처리한다 */
  seq: number;
  request: NotificationRequest | null;
  /** 받을 종이 없을 때 온 부탁이 남아 있다 — 다음에 뜨는 종이 take()로 한 번 처리한다 */
  pending: boolean;
  /** 지금 떠 있는 종 수 */
  bells: number;
  send: (request: NotificationRequest) => void;
  /** 종이 뜰 때 부른다. 돌려받은 함수로 떼어 낸다 */
  attach: () => () => void;
  /** 기다리는 부탁을 꺼낸다(없으면 null). 한 번 꺼내면 비운다 */
  take: () => NotificationRequest | null;
}

export const useNotificationRequests = create<NotificationRequestState>()((set, get) => ({
  seq: 0,
  request: null,
  pending: false,
  bells: 0,
  send: (request) => set((s) => ({ seq: s.seq + 1, request, pending: s.bells === 0 })),
  attach: () => {
    set((s) => ({ bells: s.bells + 1 }));
    return () => set((s) => ({ bells: Math.max(0, s.bells - 1) }));
  },
  take: () => {
    const { pending, request } = get();
    if (!pending) return null;
    set({ pending: false });
    return request;
  },
}));

// 계정이 바뀌면(로그아웃 · 다른 탭 로그인) 남은 부탁을 버린다: 앞 계정의 알림을 다음 계정의 종이 열어 남의 id로 읽음을 보내거나
// 앞 계정 채널로 바꾸지 않게(PR #481 3차). 종 수(bells)는 화면에 떠 있는 종의 것이라 그대로 둔다
onSessionAdvance(() => useNotificationRequests.setState({ request: null, pending: false }));
