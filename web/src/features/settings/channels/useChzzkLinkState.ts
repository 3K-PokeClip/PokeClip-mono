'use client';

import { useCallback, useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  chzzkLinkQueryOptions,
  startChzzkLink,
  unlinkChzzk,
  type ChzzkLinkState,
} from '@/api/chzzkLink';
import { useOnboardingStore } from '@/stores/onboarding';
import { useToast } from '@/ui';
import { goToChzzkConsent, warnIfCallbackMismatch } from './chzzkOAuth';

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
  /** `none`·`loading`·`unavailable`에서는 없다 — 미연동 화면에 채널명이 남지 않게. */
  channelName?: string;
  /** 이미 ko-KR로 포맷된 표시용 날짜. 클라에서 데이터를 받은 뒤에만 변환한다(SSR 안전). */
  linkedAt?: string;
  /** 동의 URL 발급 중 — 버튼 잠금. */
  starting: boolean;
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
  const setChannelLinked = useOnboardingStore((s) => s.setChannelLinked);
  const status = useQuery(chzzkLinkQueryOptions);
  const [confirmOpen, setConfirmOpen] = useState(false);

  const start = useMutation({
    mutationFn: startChzzkLink,
    onSuccess: (authorizeUrl) => {
      // 등록 주소가 어긋나 있으면 동의를 다 마친 뒤에야 증상이 나온다 — 개발 빌드에서 미리 알린다.
      warnIfCallbackMismatch(authorizeUrl);
      goToChzzkConsent(authorizeUrl);
    },
    onError: () => {
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
    onSuccess: () => {
      // 재조회가 끝나기 전에도 행이 미연동으로 서게 캐시를 먼저 갱신한다.
      queryClient.setQueryData<ChzzkLinkState>(chzzkLinkQueryOptions.queryKey, { linked: false });
      // destructive 표식이 undo를 컴파일 단계에서 막는다 (ADR-044). 실제로 되돌릴 수 없다 —
      // 서버가 옛 토큰을 secrets에서 지우고 치지직에 revoke까지 던진다.
      toast({
        tone: 'success',
        destructive: true,
        title: '치지직 연동을 해제했어요',
        description: '하이라이트 감지가 중단됐어요.',
      });
    },
    onError: () => {
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

  // 홈 시작 가이드 1단계 체크는 이 플래그를 본다. 필자는 이 이펙트 하나뿐이고,
  // 계약의 `linked`(ACTIVE·EXPIRED만 true)를 그대로 따라간다 — 쓰는 곳이 둘이면 갈라진다.
  const linked = status.data?.linked;
  useEffect(() => {
    if (linked === undefined) return; // 모르는 동안엔 건드리지 않는다
    if (useOnboardingStore.getState().channelLinked === linked) return; // 불필요한 localStorage 쓰기 차단
    setChannelLinked(linked);
  }, [linked, setChannelLinked]);

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
    channelName: named ? status.data?.channelName : undefined,
    linkedAt: named ? formatLinkedAt(status.data?.linkedAt) : undefined,
    starting: start.isPending,
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

function formatLinkedAt(iso: string | undefined): string | undefined {
  if (iso === undefined) return undefined;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? undefined : date.toLocaleDateString('ko-KR');
}
