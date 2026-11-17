'use client';

import { useQueryClient } from '@tanstack/react-query';
import { isInvitationGone, respondFailureMessage } from '@/api/editors';
import { track } from '@/lib/analytics';
import { StaleSessionError } from '@/lib/useSessionMutation';
import { useToast } from '@/ui';

export type InvitationOutcome = 'accepted' | 'declined';

const DONE_TITLE: Record<InvitationOutcome, string> = {
  accepted: '초대를 수락했어요',
  declined: '초대를 거절했어요',
};

/**
 * 그 초대가 끝났는가(답이 끝남 · 이미 처리된 초대 · 세션이 바뀜). 모르는 실패(일시 장애)면 false — 부른 쪽은 거절 확인을
 * 남겨 「거절하기」만 다시 누르게 한다(PR #412 리뷰, 알림 창도 같은 규칙 — PR #481 4차)
 */
export function isInvitationFinished(error: unknown): boolean {
  return error === null || isInvitationGone(error) || error instanceof StaleSessionError;
}

/**
 * 받은 초대에 수락 · 거절로 답한 뒤의 공통 처리. 채널 스위처의 초대 줄과 알림 창의 초대 카드가 같이 쓴다 — 두 벌이면 한쪽만
 * 고쳐 문구 · 분석 이벤트 · 다시 읽는 목록이 조용히 갈린다(PR #481 3차).
 *
 * - 성공: 분석 이벤트(상대 이름 · 초대 번호는 싣지 않는다, POK-357)와 토스트
 * - 실패: 서버 사유 코드는 보이지 않는다 — 사람 말로 바꾸고 모르는 실패는 폴백 문구로
 * - 끝(onSettled): 성공 · 실패 모두 받은 초대와 참여 채널 목록을 다시 읽는다(수락하면 참여 채널이 는다). 실패는 대개 그 사이
 *   스트리머가 취소했거나 다른 곳에서 먼저 답했다는 뜻이라 목록이 낡았다(POK-481). 돌려주는 값은 「그 초대가 끝났는가」 —
 *   성공 · 이미 처리된 초대 · 세션이 바뀜이면 true, 모르는 실패(일시 장애)면 false라 부른 쪽이 덮개를 남겨 다시 누르게 한다
 *   (PR #412 리뷰)
 */
export function useInvitationResponse() {
  const { toast } = useToast();
  const queryClient = useQueryClient();
  return {
    onSuccess(outcome: InvitationOutcome) {
      track('editor_invitation_responded', { outcome });
      toast({ tone: 'success', title: DONE_TITLE[outcome] });
    },
    onError(e: unknown) {
      toast({ tone: 'error', ...respondFailureMessage(e) });
    },
    onSettled(error: unknown): boolean {
      void queryClient.invalidateQueries({ queryKey: ['editor-invitations'] });
      void queryClient.invalidateQueries({ queryKey: ['editor-delegations'] });
      return isInvitationFinished(error);
    },
  };
}
