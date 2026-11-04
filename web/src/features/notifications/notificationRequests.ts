'use client';

import { create } from 'zustand';
import type { AppNotification } from '@/api/notifications';

// 토스트 단추가 「지금 떠 있는 종」에게 일을 맡기는 우편함(POK-540, PR #481 리뷰).
// 토스트는 앱 전체의 ToastProvider에 남는데 종은 화면마다 바뀐다(홈 헤더 ↔ 사이드바). 토스트를 띄운 종의 함수를 그대로 쥐고 있으면
// 화면을 옮겨 그 종이 사라진 뒤 「알림 보기」를 눌러도 아무 창이 안 열린다. 그래서 단추는 여기에 부탁만 넣고, 지금 떠 있는 종이 받아 처리한다.
// 종이 떠오를 때 이미 들어와 있던 부탁은 앞 종 몫이라 처리하지 않는다(나중에 뜬 종이 엉뚱하게 창을 열지 않게).

export type NotificationRequest =
  /** 알림 창 열기(「알림 보기」 · 「모아 보기」) */
  | { kind: 'panel' }
  /** 이 알림을 연다: 읽음으로 하고 그 화면으로 간다 */
  | { kind: 'item'; notification: AppNotification };

interface NotificationRequestState {
  /** 부탁마다 오르는 번호 — 종은 자기가 본 번호보다 큰 것만 처리한다 */
  seq: number;
  request: NotificationRequest | null;
  send: (request: NotificationRequest) => void;
}

export const useNotificationRequests = create<NotificationRequestState>()((set) => ({
  seq: 0,
  request: null,
  send: (request) => set((s) => ({ seq: s.seq + 1, request })),
}));
