'use client';

import { useCallback, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  soopLinkQueryOptions,
  startSoopLink,
  unlinkSoop,
  type SoopLinkState,
} from '@/api/soopLink';
import { track } from '@/lib/analytics';
import { captureSession } from '@/stores/auth';
import { useToast } from '@/ui';
import { assertSoopConsentUrl, goToSoopConsent } from './soopOAuth';

// SOOP 연동 실상태 훅 (POK-396). useChzzkLinkState의 쌍둥이다. 다른 점:
// - 온보딩 「채널 연동됨」 플래그는 쓰지 않는다. 두 플랫폼을 같이 보고 ChannelSettingsScreen이 쓴다(C8).
// - 닫힌 연동(BROKEN)에는 닉네임이 없다(서버가 파기한다). 그래서 끊김 문구에 이름을 넣지 않는다.

export type SoopView = 'loading' | 'unavailable' | 'none' | 'active' | 'expired' | 'broken';

export interface SoopLinkViewState {
  view: SoopView;
  /** 서버의 linked. 아직 모르면 undefined (화면의 온보딩 판정 재료). */
  linked?: boolean;
  /** active·expired에서만 있다. */
  nickname?: string;
  starting: boolean;
  retrying: boolean;
  startLink: () => void;
  retry: () => void;
  confirmOpen: boolean;
  openConfirm: () => void;
  closeConfirm: () => void;
  confirmUnlink: () => void;
  unlinking: boolean;
}

export function useSoopLinkState(): SoopLinkViewState {
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const status = useQuery(soopLinkQueryOptions);
  const [confirmOpen, setConfirmOpen] = useState(false);

  const start = useMutation({
    // 검증을 mutationFn 안에서 한다: 던지면 onError의 문구로 흐른다(onSuccess에서 던지면 안 잡힌다).
    mutationFn: async () => {
      const authorizeUrl = await startSoopLink();
      assertSoopConsentUrl(authorizeUrl);
      return authorizeUrl;
    },
    // 동의 주소는 요청한 회원 몫이다(state가 회원에 묶인다, POK-222): 보낼 때의 세션을 적어 둔다.
    onMutate: () => ({ isSameSession: captureSession() }),
    onSuccess: (authorizeUrl, _variables, sent) => {
      // 그 사이 계정이 바뀌었으면 다음 사람을 앞 계정의 동의 화면으로 보내지 않는다.
      if (!sent.isSameSession()) return;
      goToSoopConsent(authorizeUrl);
    },
    onError: (_error, _variables, sent) => {
      if (sent?.isSameSession() === false) return;
      toast({
        tone: 'error',
        title: '연동을 시작하지 못했어요',
        description: '잠시 후 다시 시도해 주세요.',
      });
    },
  });

  const unlink = useMutation({
    mutationFn: unlinkSoop,
    // 응답을 기다리는 사이 다른 탭이 계정을 바꿀 수 있다 (POK-222): 보낼 때의 세션을 적어 둔다.
    onMutate: () => ({ isSameSession: captureSession() }),
    onSuccess: (_data, _variables, sent) => {
      // 계정이 바뀌었으면 아무것도 반영하지 않는다. 크로스탭 핸들러가 비운 캐시에 앞 계정의
      // 「미연동」을 되심으면, 이어지는 재조회가 실패할 때 그 값이 다음 사람의 연동을 덮는다.
      if (!sent.isSameSession()) return;
      queryClient.setQueryData<SoopLinkState>(soopLinkQueryOptions.queryKey, { linked: false });
      track('platform_unlinked', { platform: 'soop' });
      // 되돌릴 수 없다: 서버가 토큰과 받은 방송국 정보를 바로 지운다.
      toast({
        tone: 'success',
        destructive: true,
        title: 'SOOP 연동을 해제했어요',
        description: '하이라이트 감지가 중단됐어요.',
      });
    },
    onError: (_error, _variables, sent) => {
      // 앞 계정의 실패도 다음 사람에게는 엉뚱한 안내다.
      if (sent?.isSameSession() === false) return;
      toast({
        tone: 'error',
        title: '연동 해제에 실패했어요',
        description: '잠시 후 다시 시도해 주세요.',
      });
    },
    onSettled: () => {
      setConfirmOpen(false);
      void queryClient.invalidateQueries({ queryKey: soopLinkQueryOptions.queryKey });
    },
  });

  const { mutate } = start;
  const startLink = useCallback(() => mutate(), [mutate]);
  const { mutate: mutateUnlink } = unlink;
  const confirmUnlink = useCallback(() => mutateUnlink(), [mutateUnlink]);
  const openConfirm = useCallback(() => setConfirmOpen(true), []);
  const closeConfirm = useCallback(() => setConfirmOpen(false), []);
  const { refetch } = status;
  const retry = useCallback(() => {
    void refetch();
  }, [refetch]);

  const view = toView(status.isPending, status.isError, status.data);

  return {
    view,
    linked: status.data?.linked,
    nickname: view === 'active' || view === 'expired' ? status.data?.nickname : undefined,
    starting: start.isPending,
    retrying: status.isFetching,
    startLink,
    retry,
    confirmOpen,
    openConfirm,
    closeConfirm,
    confirmUnlink,
    unlinking: unlink.isPending,
  };
}

/** useChzzkLinkState의 toView와 같은 판정(최초 실패만 오류, 판정 재료는 data 유무). */
function toView(isPending: boolean, isError: boolean, data: SoopLinkState | undefined): SoopView {
  if (isPending) return 'loading';
  if (isError && data === undefined) return 'unavailable';
  if (data?.status === 'ACTIVE') return 'active';
  if (data?.status === 'EXPIRED') return 'expired';
  if (data?.status === 'BROKEN') return 'broken';
  return 'none';
}
