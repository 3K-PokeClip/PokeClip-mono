'use client';

import { useCallback } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { meQueryOptions, type Me } from '@/api/auth';
import { updateAnalyticsOptOut } from '@/api/profile';
import { useMe } from '@/features/auth/useSession';
import { announceAnalyticsPreference } from '@/lib/analytics';
import { useSessionMutation } from '@/lib/useSessionMutation';
import { useToast } from '@/ui';

// 설정 › 계정의 「이용 분석 참여」 토글(처리방침 7·9절). 값은 auth 계정에 둔다(모든 기기에 같다).
// 끄는 일 자체는 AuthGuard → identifyUser가 한다 — 여기서는 저장하고 me 캐시를 갈아 끼우기만 한다.
// 캐시가 바뀌면 가드가 새 값으로 다시 건다.

export interface AnalyticsOptOutState {
  /** 참여 중인가. me를 아직 못 읽었으면 undefined — 토글을 잠근다 */
  participating: boolean | undefined;
  saving: boolean;
  setParticipating: (next: boolean) => void;
}

export function useAnalyticsOptOutState(): AnalyticsOptOutState {
  const me = useMe().data;
  const queryClient = useQueryClient();
  const { toast } = useToast();

  const mutation = useSessionMutation({
    mutationFn: updateAnalyticsOptOut,
    onMutate: async () => {
      // PATCH 전에 날아가 있던 GET me가 뒤늦게 도착하면 옛 값으로 캐시를 되돌린다 — 끊는다
      await queryClient.cancelQueries({ queryKey: meQueryOptions.queryKey });
    },
    onSuccess: async (next) => {
      await queryClient.cancelQueries({ queryKey: meQueryOptions.queryKey });
      // 🔴 캐시의 주인이 응답의 주인과 같을 때만 쓴다 — 왕복 중 다른 계정이 로그인했으면 그 사람 값을 덮지 않는다
      // (useAccountState.patchMe와 같은 규칙)
      const optOut = next.analyticsOptOut === true;
      queryClient.setQueryData<Me>(meQueryOptions.queryKey, (prev) =>
        prev === undefined || prev.id !== next.id ? prev : { ...prev, analyticsOptOut: optOut },
      );
      if (queryClient.getQueryData<Me>(meQueryOptions.queryKey)?.id !== next.id) return;
      // 같은 브라우저의 다른 탭도 새 값을 쓰게 알린다 — 옛 캐시로 꺼 둔 선택을 다시 켜지 않게(PR #382 리뷰)
      announceAnalyticsPreference({ userId: next.id, optOut });
      toast({
        tone: 'success',
        title: optOut ? '이용 분석을 껐어요' : '이용 분석을 다시 켰어요',
      });
    },
    onError: () => {
      toast({
        tone: 'error',
        title: '이용 분석 설정을 바꾸지 못했어요',
        description: '잠시 후 다시 시도해 주세요.',
      });
    },
  });
  const { mutate, isPending: saving } = mutation;

  const setParticipating = useCallback(
    (next: boolean) => {
      if (saving) return;
      mutate(!next);
    },
    [mutate, saving],
  );

  return {
    participating: me === undefined ? undefined : me.analyticsOptOut !== true,
    saving,
    setParticipating,
  };
}
