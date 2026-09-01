'use client';

import { useCallback } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { markWithdrawn } from '@/features/account/withdrawHandoff';

// 디자인 1p 설정 · 계정에서 **아직 목업인 부분** — 탈퇴와 그 모달의 표기값 (POK-206).
//
// 표시 이름·프로필 사진 저장은 useAccountState가 실제 서버로 보낸다(POK-208). 여기 남은 것은
// 탈퇴뿐이다: 서버 창구(DELETE /api/auth/me, POK-171)는 생겼지만 웹 배선은 별도 티켓이라,
// 지금은 아무것도 지우지 않고 로컬 세션만 접는다. 보관함·구독 수치도 API가 없어 목업이다.
// 실·목업을 파일로 가른 이유(usePluginMockState 선례): 파일 이름이 「여기는 가짜다」라는 표식이다.

/** 시안 1p 탈퇴 모달의 표기값. 보관함·구독 API가 없어 목업으로 둔다. */
export const WITHDRAW_FACTS = {
  savedBroadcasts: 42,
  archivedClips: 128,
  remainingDays: 23,
  unpaidAmount: 12900,
} as const;

export interface AccountMockState {
  facts: typeof WITHDRAW_FACTS;
  /** 미결제 잔액이 있어 탈퇴가 막힌 상태. 결제 도메인이 없어 `?mock=blocked`로만 켜진다. */
  blocked: boolean;
  completeWithdraw: () => void;
}

export function useAccountMockState(): AccountMockState {
  const router = useRouter();
  const searchParams = useSearchParams();

  const completeWithdraw = useCallback(() => {
    // POK-171 백엔드가 붙으면 여기서 탈퇴를 부르고, 성공한 뒤에 아래로 넘어간다.
    // 지금은 아무것도 지우지 않는다 — 같은 계정으로 다시 로그인하면 그대로 돌아온다.
    //
    // 세션은 여기서 접지 않는다. clearTokens()가 도는 순간 AuthGuard가 리렌더되고 그
    // 이펙트가 의도적 종료 표식과 무관하게 /login으로 보내는데, 그것이 바로 아래 이동보다
    // 나중에 호출돼 이긴다 — 완료 화면이 뜰 틈이 없다. 가드 밖으로 나간 뒤 /goodbye가 접는다.
    markWithdrawn();
    router.replace('/goodbye');
  }, [router]);

  return {
    facts: WITHDRAW_FACTS,
    // 개발에서만 켠다. 프로덕션 번들에서는 이 항이 통째로 죽어(NODE_ENV 치환) 주소를
    // 쳐도 없는 미결제 금액이 뜨지 않는다
    blocked: process.env.NODE_ENV !== 'production' && searchParams.get('mock') === 'blocked',
    completeWithdraw,
  };
}
