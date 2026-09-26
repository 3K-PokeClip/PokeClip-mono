'use client';

import { useCallback, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { withdrawMe } from '@/api/withdrawal';
import { markWithdrawn } from '@/features/account/withdrawHandoff';
import { useToast } from '@/ui';

// 디자인 1p 설정 · 계정의 탈퇴 (POK-251 — 실제 서버로 간다).
//
// 탈퇴는 DELETE /api/auth/me(POK-171)를 실제로 부른다. 성공하면 표식을 남기고 /goodbye 로 가고,
// 그 화면이 가드 밖에서 clearTokens() 로 세션을 접는다(withdrawHandoff 참고 — 여기서 지우면
// AuthGuard 가 /login 으로 채간다).
//
// 모달의 「보관함·구독」 수치는 줄 백엔드가 없다 — 숫자를 지어내지 않고 「준비 중」이라고 적는다.
// 미결제 차단(?mock=blocked)은 결제 도메인이 없어 개발 환경의 시안 확인용으로만 켜진다.
// 파일 이름의 Mock 은 그 자리들이 아직 가짜임을 뜻한다(usePluginMockState 선례).

/** 시안 1p 탈퇴 모달의 표기값. 보관함·구독 API가 없어 값이 아니라 안내 문구다. */
export const WITHDRAW_FACTS = {
  savedBroadcasts: '준비 중',
  archivedClips: '준비 중',
  remainingDays: '준비 중',
  /** 차단 모달(개발 토글 전용) 표기값 */
  unpaidAmount: 12900,
} as const;

export interface AccountMockState {
  facts: typeof WITHDRAW_FACTS;
  /** 미결제 잔액이 있어 탈퇴가 막힌 상태. 결제 도메인이 없어 `?mock=blocked`로만 켜진다. */
  blocked: boolean;
  /** 탈퇴 왕복 중 — 확정 버튼이 잠긴다 */
  withdrawing: boolean;
  completeWithdraw: () => void;
}

export function useAccountMockState(): AccountMockState {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { toast } = useToast();
  const [withdrawing, setWithdrawing] = useState(false);

  const completeWithdraw = useCallback(() => {
    if (withdrawing) return;
    setWithdrawing(true);
    withdrawMe()
      .then(() => {
        // 세션은 여기서 접지 않는다 — /goodbye 가 가드 밖에서 접는다(withdrawHandoff).
        markWithdrawn();
        router.replace('/goodbye');
      })
      .catch((e: unknown) => {
        setWithdrawing(false);
        toast({
          tone: 'error',
          title: '탈퇴하지 못했어요',
          description: e instanceof Error ? e.message : String(e),
        });
      });
  }, [withdrawing, router, toast]);

  return {
    facts: WITHDRAW_FACTS,
    // 개발에서만 켠다. 프로덕션 번들에서는 이 항이 통째로 죽어(NODE_ENV 치환) 주소를
    // 쳐도 없는 미결제 금액이 뜨지 않는다
    blocked: process.env.NODE_ENV !== 'production' && searchParams.get('mock') === 'blocked',
    withdrawing,
    completeWithdraw,
  };
}
