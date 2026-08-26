'use client';

import { useCallback, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  youtubeLinkQueryOptions,
  startYoutubeLink,
  unlinkYoutube,
  type YoutubeLinkState,
} from '@/api/youtubeLink';
import { useToast } from '@/ui';
import {
  assertYoutubeConsentUrl,
  goToYoutubeConsent,
  warnIfCallbackMismatch,
} from './youtubeOAuth';

// 유튜브 연동 실상태 훅 (POK-221) — useChzzkLinkState와 같은 규약이다: 상태 판단은
// 전부 여기서 하고 화면은 결과만 읽는다.
//
// 치지직과 달리 온보딩 플래그(useOnboardingHydration·setChannelLinked)를 쓰지 않는다 —
// `channelLinked`는 방송 감지(치지직) 전용이다. 유튜브 연동은 홈 시작 가이드의
// 체크 대상이 아니므로 미러링 이펙트를 여기로 복사하지 말 것.

/**
 * 서버가 주는 `linked` × `status` 조합을 화면이 그릴 수 있는 모양으로 접은 것.
 * 치지직과 달리 `expired`가 없다 — 계약에 `EXPIRED` 자체가 없다(구글 access 만료는
 * 갱신으로 해소되는 일상이라 화면 상태가 아니다).
 *
 * `UNLINKED`를 `none`으로 접는다 — 계약은 해제된 채널의 이름도 주지만, 방금 스스로
 * 해제한 사용자에게 「연동 해제됨 · OOO」을 보여줄 이유가 없고 채널 식별 정보를 남길
 * 이유는 더 없다. 해제 후 재조회가 정확히 초기 상태로 돌아온다.
 */
export type YoutubeView = 'loading' | 'unavailable' | 'none' | 'active' | 'broken';

export interface YoutubeLinkViewState {
  view: YoutubeView;
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

export function useYoutubeLinkState(): YoutubeLinkViewState {
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const status = useQuery(youtubeLinkQueryOptions);
  const [confirmOpen, setConfirmOpen] = useState(false);

  const start = useMutation({
    // 검증을 mutationFn 안에서 한다 — onSuccess에서 던지면 잡히지 않고 unhandled로 샌다.
    // 여기서 던지면 아래 onError의 「연동을 시작하지 못했어요」 경로로 그대로 흐른다.
    mutationFn: async () => {
      const authorizeUrl = await startYoutubeLink();
      assertYoutubeConsentUrl(authorizeUrl);
      return authorizeUrl;
    },
    onSuccess: (authorizeUrl) => {
      // 등록 주소가 어긋나 있으면 동의를 다 마친 뒤에야 증상이 나온다 — 개발 빌드에서 미리 알린다.
      warnIfCallbackMismatch(authorizeUrl);
      goToYoutubeConsent(authorizeUrl);
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
    mutationFn: unlinkYoutube,
    onSuccess: () => {
      // 재조회가 끝나기 전에도 행이 미연동으로 서게 캐시를 먼저 갱신한다.
      queryClient.setQueryData<YoutubeLinkState>(youtubeLinkQueryOptions.queryKey, {
        linked: false,
      });
      // destructive 표식이 undo를 컴파일 단계에서 막는다 (ADR-044). 실제로 되돌릴 수 없다 —
      // 서버가 토큰 원문을 secrets에서 지운다. 다만 구글에 revoke는 안 보내므로(ADR-052:
      // 계정 단위라 남의 연동까지 끊는다) 구글 쪽 권한은 남는다 — 그 사실과 지울 곳을
      // 액션으로 안내한다. undo가 아니라 외부 안내라 destructive와 공존한다.
      toast({
        tone: 'success',
        destructive: true,
        title: '유튜브 연동을 해제했어요',
        description: '구글 계정에 준 권한은 남아 있어요.',
        action: {
          label: '구글 권한 관리',
          onClick: () =>
            window.open('https://myaccount.google.com/permissions', '_blank', 'noopener'),
        },
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
      void queryClient.invalidateQueries({ queryKey: youtubeLinkQueryOptions.queryKey });
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
  const named = view === 'active' || view === 'broken';

  return {
    view,
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

function toView(
  isPending: boolean,
  isError: boolean,
  data: YoutubeLinkState | undefined,
): YoutubeView {
  if (isPending) return 'loading';
  // 한 번이라도 읽었으면 재조회 실패에 오류 화면으로 갈아끼우지 않는다 — "아무것도 모르는"
  // 최초 실패만 오류로 그린다. 미연동으로 오인시키는 쪽이 훨씬 나쁘다. (useChzzkLinkState와 같은 판정)
  //
  // 판정 재료는 data 유무다. status 유무로 보면 안 된다 — 연동이 없을 때 서버는
  // {linked:false}만 주고 status 필드를 아예 빼므로, 미연동을 정상적으로 읽은 뒤
  // 재조회가 실패하면 멀쩡한 미연동 화면이 오류로 뒤집힌다.
  if (isError && data === undefined) return 'unavailable';
  if (data?.status === 'ACTIVE') return 'active';
  if (data?.status === 'BROKEN') return 'broken';
  // UNLINKED와 모르는 status는 전부 none — 계약 밖의 값(치지직의 EXPIRED 같은)이 섞여
  // 들어와도 「갱신 필요」류 상시 표기가 저절로 생기지 않게 명시 분기만 그린다.
  return 'none';
}
