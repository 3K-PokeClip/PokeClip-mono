'use client';

import { useCallback, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  chzzkLinkQueryOptions,
  startChzzkLink,
  unlinkChzzk,
  type ChzzkLinkState,
} from '@/api/chzzkLink';
import { track } from '@/lib/analytics';
import { captureSession } from '@/stores/auth';
import { useToast } from '@/ui';
import { assertChzzkConsentUrl, goToChzzkConsent, warnIfCallbackMismatch } from './chzzkOAuth';

// 치지직 연동 실상태 훅 (POK-205) — useChannelMockState의 교체 주석이 약속한 자리.
// useStreamKeyState와 같은 규약이다: 상태 판단은 전부 여기서 하고 화면은 결과만 읽는다.

/**
 * 서버가 주는 `linked` × `status` 조합을 화면이 그릴 수 있는 모양으로 접은 것.
 *
 * `UNLINKED`를 `none`으로 접는다 — 계약은 해제된 채널의 이름도 주지만, 방금 스스로
 * 해제한 사용자에게 「연동 해제됨 · 게임하는너구리」를 보여줄 이유가 없고 채널 식별
 * 정보를 남길 이유는 더 없다. 해제 후 재조회가 정확히 초기 상태로 돌아온다.
 */
export type ChzzkView = 'loading' | 'unavailable' | 'none' | 'active' | 'expired' | 'broken';

export interface ChzzkLinkViewState {
  view: ChzzkView;
  /** 서버의 linked. 아직 모르면 undefined. 온보딩 플래그는 SOOP과 합쳐 ChannelSettingsScreen이 쓴다(POK-396). */
  linked?: boolean;
  /** `none`·`loading`·`unavailable`에서는 없다 — 미연동 화면에 채널명이 남지 않게. */
  channelName?: string;
  /** 동의 URL 발급 중 — 버튼 잠금. */
  starting: boolean;
  /** 조회 실패 후 다시 시도가 도는 중 — 버튼에 진행 표시를 준다. */
  retrying: boolean;
  /** 첫 연동·다시 연동 전부 이 한 경로다 — 서버가 준 authorizeUrl로 나간다. */
  startLink: () => void;
  retry: () => void;
  /** 해제 확인 모달 — 파괴적 동작이라 모달로 확인받고 토스트는 결과만 알린다 (ADR-044). */
  confirmOpen: boolean;
  openConfirm: () => void;
  closeConfirm: () => void;
  /** 해제 실행. 요청 중에는 모달이 닫히지 않는다. */
  confirmUnlink: () => void;
  unlinking: boolean;
}

export function useChzzkLinkState(): ChzzkLinkViewState {
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const status = useQuery(chzzkLinkQueryOptions);
  const [confirmOpen, setConfirmOpen] = useState(false);

  const start = useMutation({
    // 검증을 mutationFn 안에서 한다 — onSuccess에서 던지면 잡히지 않고 unhandled로 샌다.
    // 여기서 던지면 아래 onError의 「연동을 시작하지 못했어요」 경로로 그대로 흐른다.
    mutationFn: async () => {
      const authorizeUrl = await startChzzkLink();
      assertChzzkConsentUrl(authorizeUrl);
      return authorizeUrl;
    },
    // 동의 주소는 요청한 회원 몫이다(state가 회원에 묶인다, POK-222): 보낼 때의 세션을 적어 둔다.
    onMutate: () => ({ isSameSession: captureSession() }),
    onSuccess: (authorizeUrl, _variables, sent) => {
      // 그 사이 계정이 바뀌었으면 다음 사람을 앞 계정의 동의 화면으로 보내지 않는다.
      if (!sent.isSameSession()) return;
      // 등록 주소가 어긋나 있으면 동의를 다 마친 뒤에야 증상이 나온다 — 개발 빌드에서 미리 알린다.
      warnIfCallbackMismatch(authorizeUrl);
      goToChzzkConsent(authorizeUrl);
    },
    onError: (_error, _variables, sent) => {
      if (sent?.isSameSession() === false) return;
      // 401은 apiFetch가 회전을 시도하고 AuthGuard가 /login으로 보낸다 — 별도 문구 없음.
      toast({
        tone: 'error',
        title: '연동을 시작하지 못했어요',
        description: '잠시 후 다시 시도해 주세요.',
      });
    },
  });

  const unlink = useMutation({
    mutationFn: unlinkChzzk,
    // 응답을 기다리는 사이 다른 탭이 계정을 바꿀 수 있다 (POK-222): 보낼 때의 세션을 적어 둔다.
    onMutate: () => ({ isSameSession: captureSession() }),
    onSuccess: (_data, _variables, sent) => {
      // 계정이 바뀌었으면 아무것도 반영하지 않는다. 크로스탭 핸들러가 비운 캐시에 앞 계정의
      // 「미연동」을 되심으면, 이어지는 재조회가 실패할 때 그 값이 다음 사람의 연동을 덮는다.
      if (!sent.isSameSession()) return;
      // 재조회가 끝나기 전에도 행이 미연동으로 서게 캐시를 먼저 갱신한다.
      queryClient.setQueryData<ChzzkLinkState>(chzzkLinkQueryOptions.queryKey, { linked: false });
      track('platform_unlinked', { platform: 'chzzk' });
      // destructive 표식이 undo를 컴파일 단계에서 막는다 (ADR-044). 실제로 되돌릴 수 없다 —
      // 서버가 옛 토큰을 secrets에서 지우고 치지직에 revoke까지 던진다.
      toast({
        tone: 'success',
        destructive: true,
        title: '치지직 연동을 해제했어요',
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
      // 성공·실패 모두 모달을 닫는다 — 결과는 토스트가 알린다. 모달에 오류를 또 그리면
      // 같은 말이 두 번 나온다 (ADR-044: 토스트는 결과만 알린다).
      setConfirmOpen(false);
      void queryClient.invalidateQueries({ queryKey: chzzkLinkQueryOptions.queryKey });
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
  const named = view === 'active' || view === 'expired' || view === 'broken';

  return {
    view,
    linked: status.data?.linked,
    channelName: named ? status.data?.channelName : undefined,
    starting: start.isPending,
    // isPending이 아닌 isFetching이다 — 최초 조회는 view가 'loading'이라 겹치지 않고,
    // 여기서 잡아야 할 것은 실패 후 재조회다.
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

function toView(isPending: boolean, isError: boolean, data: ChzzkLinkState | undefined): ChzzkView {
  if (isPending) return 'loading';
  // 한 번이라도 읽었으면 재조회 실패에 오류 화면으로 갈아끼우지 않는다 — "아무것도 모르는"
  // 최초 실패만 오류로 그린다. 미연동으로 오인시키는 쪽이 훨씬 나쁘다. (useStreamKeyState와 같은 판정)
  //
  // 판정 재료는 data 유무다. status 유무로 보면 안 된다 — 연동이 없을 때 서버는
  // {linked:false}만 주고 status 필드를 아예 빼므로, 미연동을 정상적으로 읽은 뒤
  // 재조회가 실패하면 멀쩡한 미연동 화면이 오류로 뒤집힌다.
  if (isError && data === undefined) return 'unavailable';
  if (data?.status === 'ACTIVE') return 'active';
  if (data?.status === 'EXPIRED') return 'expired';
  if (data?.status === 'BROKEN') return 'broken';
  return 'none';
}
