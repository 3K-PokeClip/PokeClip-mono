'use client';

// 알림 목록 · 읽음 처리 · 초대 답 · 누르면 그 화면으로(POK-540).
// 서버가 없으면(NOTIFICATIONS_SERVER_READY=false) 목록을 읽지 않고 빈 목록이다. 개발 서버의 ?mock=notifications는 가짜 저장소를 쓴다.

import { useCallback, useEffect, useMemo, useRef } from 'react';
import { useRouter } from 'next/navigation';
import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import {
  NOTIFICATIONS_QUERY_KEY,
  NOTIFICATIONS_SERVER_READY,
  fetchNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  type AppNotification,
  type NotificationsResponse,
} from '@/api/notifications';
import {
  acceptInvitation,
  declineInvitation,
  delegationsAsEditorQueryOptions,
  type StreamerDelegation,
} from '@/api/editors';
import { useMe } from '@/features/auth/useSession';
import { useActiveChannel, useSwitchChannel } from '@/features/channel/useActiveChannel';
import { useInvitationResponse } from '@/features/channel/useInvitationResponse';
import { captureSession, onSessionAdvance } from '@/stores/auth';
import { useChannelStore } from '@/stores/channel';
import { useSessionMutation } from '@/lib/useSessionMutation';
import { useToast, type ToastOptions, type ToastTone } from '@/ui';
import {
  fetchMockNotifications,
  markAllMockRead,
  markMockRead,
  resolveMock,
  useNotificationsMockMode,
} from './mockNotifications';
import { useNotificationRequests } from './notificationRequests';
import {
  describeNotification,
  isOpenTodo,
  unreadCount,
  visibleNotifications,
  type NotificationTone,
} from './notificationView';

/** 서버가 실시간 전달(SSE 등)을 열기 전까지는 탭이 보이는 동안 이 주기로 다시 읽는다(react-query가 숨은 탭에선 멈춘다) */
const POLL_MS = 30_000;
/** 읽음 · 모두 읽음이 함께 쓰는 뮤테이션 키 — 겹친 읽음 중 마지막 것만 다시 읽게 센다 */
const READ_MUTATION_KEY = ['notifications', 'read'] as const;
const MOCK_POLL_MS = 3_000;

function patchCache(
  queryClient: QueryClient,
  key: readonly unknown[],
  change: (n: AppNotification) => AppNotification,
) {
  queryClient.setQueryData<NotificationsResponse>(key, (prev) =>
    prev ? { items: prev.items.map(change) } : prev,
  );
}

export function useNotifications() {
  const mockMode = useNotificationsMockMode();
  const me = useMe().data;
  const queryClient = useQueryClient();
  const key = [...NOTIFICATIONS_QUERY_KEY, mockMode ?? 'server'] as const;
  const enabled = me !== undefined && (NOTIFICATIONS_SERVER_READY || mockMode !== null);

  const query = useQuery({
    queryKey: key,
    queryFn: () =>
      mockMode !== null && me
        ? fetchMockNotifications(mockMode, { id: me.id, name: me.name })
        : fetchNotifications(),
    enabled,
    refetchInterval: mockMode !== null ? MOCK_POLL_MS : POLL_MS,
  });

  // 최근 14일 · 최대 50개만 남긴다(풀리지 않은 할 일은 남김). 받은 목록이 바뀔 때만 다시 거른다 — 그래야 새 알림 토스트가
  // 렌더마다 목록을 다시 훑지 않는다
  const data = query.data;
  const allItems = useMemo(() => data?.items ?? [], [data]);
  const items = useMemo(() => visibleNotifications(allItems, Date.now()), [allItems]);

  // 읽음은 화면에 바로 그린다(낙관적) — 읽음 하나 실패로 토스트를 띄울 일은 아니다. 그리기 전에 나가 있던 재조회를 멈춘다:
  // 누르기 직전에 나간 응답(readAt null)이 늦게 오면 방금 그린 읽음을 덮어 점 · 숫자가 다음 재조회까지 되살아난다.
  // 끝나면(성공 · 실패 모두) 서버 값으로 다시 맞춘다
  // 끝나면 서버 값으로 다시 맞추되, 다른 읽음이 아직 날아가고 있으면 맞추지 않는다: 먼저 끝난 쪽이 다시 읽은 서버 값(아직
  // 처리 안 된 쪽은 readAt null)이 그쪽의 낙관 갱신을 덮는다. 마지막으로 끝나는 읽음이 한 번만 다시 읽는다(PR #481 2차 리뷰).
  // onSettled 때 끝나는 그 읽음은 아직 진행 중으로 세어진다 — 그래서 1이면 마지막이다
  const resyncIfLast = () => {
    if (queryClient.isMutating({ mutationKey: READ_MUTATION_KEY }) <= 1)
      void queryClient.invalidateQueries({ queryKey: key });
  };

  const markRead = useSessionMutation({
    mutationKey: READ_MUTATION_KEY,
    mutationFn: (id: string) => (mockMode !== null ? markMockRead(id) : markNotificationRead(id)),
    onMutate: async (id: string) => {
      await queryClient.cancelQueries({ queryKey: key });
      const at = new Date().toISOString();
      patchCache(queryClient, key, (n) =>
        n.id === id && n.readAt === null ? { ...n, readAt: at } : n,
      );
    },
    onSettled: resyncIfLast,
  });

  const markAllRead = useSessionMutation({
    mutationKey: READ_MUTATION_KEY,
    mutationFn: () => (mockMode !== null ? markAllMockRead() : markAllNotificationsRead()),
    onMutate: async () => {
      await queryClient.cancelQueries({ queryKey: key });
      const at = new Date().toISOString();
      patchCache(queryClient, key, (n) => (n.held || n.readAt !== null ? n : { ...n, readAt: at }));
    },
    onSettled: resyncIfLast,
  });

  return {
    items,
    /** 서버가 준 그대로(14일 · 50개로 자르기 전) — 새 알림 판정은 이것으로 한다 */
    allItems,
    unread: unreadCount(items),
    /** 서버가 없어 읽지 않는 중이면 false — 창은 빈 안내만 */
    enabled,
    isPending: enabled && query.isPending,
    isError: query.data === undefined && query.isError,
    refetch: () => void query.refetch(),
    markRead: (id: string) => markRead.mutate(id),
    markAllRead: () => markAllRead.mutate(),
    mockMode,
    queryKey: key,
  };
}

export type NotificationsState = ReturnType<typeof useNotifications>;

/**
 * 편집자 초대에 수락 · 거절로 답한다 — 채널 스위처의 초대 줄과 같은 API, 답한 뒤 처리(문구 · 분석 이벤트 · 받은 초대와 참여
 * 채널 다시 읽기)는 useInvitationResponse를 같이 쓴다. 그 초대가 끝났으면 알림도 다시 읽는다(서버가 resolvedAt을 채운다).
 */
export function useRespondInvitation(state: NotificationsState) {
  const respond = useInvitationResponse();
  const queryClient = useQueryClient();
  return useSessionMutation({
    mutationFn: async ({ n, answer }: { n: AppNotification; answer: 'accept' | 'decline' }) => {
      if (n.payload.kind !== 'EDITOR_INVITED') return;
      if (state.mockMode !== null) return resolveMock(n.id);
      await (answer === 'accept' ? acceptInvitation : declineInvitation)(n.payload.invitationId);
    },
    onSuccess: (_data, { answer }) =>
      respond.onSuccess(answer === 'accept' ? 'accepted' : 'declined'),
    onError: respond.onError,
    onSettled: (_data, error) => {
      if (!respond.onSettled(error)) return;
      // 같이 보낸 읽음이 아직 날아가는 중이면 다시 읽기는 읽음 쪽(resyncIfLast)에 맡긴다: 먼저 다시 읽으면 읽음이 반영되기
      // 전 목록(readAt null)이 낙관 갱신을 덮어 종 숫자가 1 올랐다 내려간다(PR #481 4차)
      if (queryClient.isMutating({ mutationKey: READ_MUTATION_KEY }) > 0) return;
      void queryClient.invalidateQueries({ queryKey: state.queryKey });
    },
  });
}

/**
 * 알림이 가리키는 화면으로 간다. 알림이 다른 채널 일이면(내 채널 알림을 남의 채널을 보던 중에 눌렀거나, 맡은 채널 방송 시작)
 * 그 채널로 바꾼 다음 간다 — 채널 연동 같은 화면은 내 채널에서만 열린다.
 *
 * - 지금 채널이 아직 안 섰으면(새로고침 직후 참여 목록을 읽는 중) 저장된 선택으로 판단한다: 내 채널로 치면 전환을 건너뛰었다가
 *   목록이 선 뒤 남의 채널로 서서 내 채널 전용 화면이 막힌다(PR #481 리뷰).
 * - 전환에 갈 주소를 같이 싣는다: 방송에 묶인 화면에서 바꾸면 ChannelScope가 떠나는데, 홈이 아니라 그 주소로 가게 한다.
 * - 내 채널 알림인데 저장된 선택이 남의 채널이면(참여 목록 첫 읽기가 실패해 내 채널로 풀려 보이는 상태) 내 채널로 바꾼 뒤
 *   간다: 그냥 가면 목록이 나중에 읽혀 남의 채널로 돌아가 내 채널 전용 화면(채널 연동)이 막힌다(PR #481 4차).
 * - 남의 채널은 참여 목록에서 확인된 때만 바꾼다. 받아 둔 목록에 없거나 아직 없으면 새로 받아 본다: 다른 기기에서 막 수락한
 *   채널은 이 탭의 목록(staleTime 60초)에 아직 없다. 새로 받아도 없으면(위임이 끊김) · 받지 못했으면 가지 않고 알린다 —
 *   확인 못 한 채널로 바꾸면 useActiveChannel이 내 채널로 풀고, 채널 화면(라이브 · 보관함 · 다시보기)은 지금 채널 기준이라
 *   엉뚱한 채널의 것이 뜬다(PR #481 3 · 4차).
 */
export function useGoToNotification() {
  const router = useRouter();
  const me = useMe().data;
  const active = useActiveChannel();
  const switchTo = useSwitchChannel();
  const queryClient = useQueryClient();
  const { toast } = useToast();
  return async (n: AppNotification, href: string) => {
    if (!me) {
      router.push(href);
      return;
    }
    const store = useChannelStore.getState();
    // 이 회원으로 저장된 남의 채널 선택(없으면 null) — 내 채널로 풀려 보여도 남아 있을 수 있다. 끊긴 것으로 판정된
    // 선택(gone)은 ChannelScope가 곧 지우며 알리므로 남은 선택으로 치지 않는다
    const gone = active.ready && active.gone;
    const saved =
      store.userId === me.id && store.selected !== me.id && !gone ? store.selected : null;
    const current = active.ready ? active.streamerId : (saved ?? me.id);
    const go = { destination: href, onSwitched: () => router.push(href) };
    if (n.channel.id === me.id) {
      if (current === me.id && saved === null) router.push(href);
      else switchTo({ streamerId: me.id, name: me.name, relation: 'OWNER' }, go);
      return;
    }
    if (n.channel.id === current) {
      router.push(href);
      return;
    }
    const find = (list: StreamerDelegation[] | null | undefined) =>
      list?.find((d) => d.streamerId === n.channel.id);
    let target = find(
      queryClient.getQueryData<StreamerDelegation[]>(delegationsAsEditorQueryOptions.queryKey),
    );
    if (!target) {
      const isSameSession = captureSession();
      const fresh = await queryClient
        .fetchQuery({ ...delegationsAsEditorQueryOptions, staleTime: 0 })
        .catch(() => null);
      // 기다리는 사이 계정이 바뀌었으면 앞 계정의 알림으로 움직이지 않는다
      if (!isSameSession()) return;
      if (fresh === null) {
        toast({
          tone: 'error',
          title: '채널 정보를 확인하지 못했어요',
          description: '잠시 뒤 다시 눌러 주세요',
          dedupeKey: 'notification-channel-unknown',
        });
        return;
      }
      target = find(fresh);
      if (!target) {
        toast({
          tone: 'warning',
          title: `${n.channel.name} 채널의 편집 권한이 없어요`,
          description: '초대가 취소됐거나 편집자에서 빠졌어요',
          dedupeKey: `notification-gone-${n.channel.id}`,
        });
        return;
      }
    }
    switchTo(
      {
        streamerId: n.channel.id,
        name: target.streamerName,
        relation: 'EDITOR',
        photo: target.streamerProfileImageUrl,
      },
      go,
    );
  };
}

const TOAST_TONE: Record<NotificationTone, ToastTone> = {
  danger: 'error',
  warning: 'warning',
  success: 'success',
  info: 'info',
  neutral: 'info',
};

/** 한 번에 이보다 많이 오면 하나씩 띄우지 않고 「새 알림 n개」 하나로 */
const MAX_SINGLE_TOASTS = 2;

// 새 알림 토스트가 떠 있는 채 계정이 바뀌면(로그아웃 · 다른 탭 로그인) 앞 계정의 알림(채널 이름 · 단추)이 다음 사람 화면에
// 남는다 — 「중요」 할 일은 닫을 때까지 남는다(PR #481 3차). 띄운 토스트를 적어 두었다가 세션이 바뀌면 닫는다. 종이 없는
// 화면에서 바뀌어도 닫히게 모듈에 둔다: 토스트 묶음(ToastProvider)은 앱 전체에 살아 있어 마지막 종이 넘긴 dismiss를 그대로 쓴다
const raisedToasts = new Set<string>();
let dismissRaised: ((id: string) => void) | null = null;
onSessionAdvance(() => {
  raisedToasts.forEach((id) => dismissRaised?.(id));
  raisedToasts.clear();
});

/**
 * 새로 온 알림을 오른쪽 아래 토스트로 알린다. 처음 읽은 목록은 알리지 않는다(창을 열 때마다 쌓인 걸 다시 울리지 않게).
 * 쌓여 있던 것(held)이 풀리면(방송이 끝남) 하나씩이 아니라 「방송 중 알림 n개」 하나로 — 디자인 1n ②.
 * 「중요」로 받은 할 일(연동 만료 · 초대)은 닫을 때까지 남긴다.
 *
 * 토스트 단추는 이 훅을 부른 종의 함수를 쥐지 않고 우편함(notificationRequests)에 부탁만 넣는다: 화면을 옮겨 종이 바뀐 뒤에
 * 눌러도 지금 떠 있는 종이 받아 처리한다. 계정이 바뀌면 띄운 토스트를 닫고, 단추도 띄운 세션에서만 움직인다.
 */
export function useArrivalToasts(state: NotificationsState) {
  const { toast: showToast, dismiss } = useToast();
  const sendRequest = useNotificationRequests((s) => s.send);
  const router = useRouter();
  // 받을 종이 없는 화면(전체 화면 편집기)에서 누르면 종이 있는 홈으로 옮긴다 — 부탁은 남아 홈의 종이 처리한다
  const send = useCallback(
    (request: Parameters<typeof sendRequest>[0]) => {
      sendRequest(request);
      if (useNotificationRequests.getState().bells === 0) router.push('/home');
    },
    [sendRequest, router],
  );
  const known = useRef<Map<string, boolean> | null>(null);

  useEffect(() => {
    dismissRaised = dismiss;
  }, [dismiss]);
  // 계정이 바뀌면 처음 읽는 목록부터 다시: 앞 계정 id 맵과 대조하면 새 계정의 기존 알림이 모두 「새 알림」으로 울린다
  // (종은 그대로 마운트된 채 Providers가 캐시만 비운다). 바뀐 순간 들고 있던 목록(앞 계정 것)은 기준으로도 쓰지 않는다 —
  // 캐시가 비기 전에 다시 그려지면 그 목록이 기준이 되어 새 계정 목록 전체가 새 알림이 된다
  const lastItems = useRef<AppNotification[] | null>(null);
  const staleItems = useRef<AppNotification[] | null>(null);
  useEffect(
    () =>
      onSessionAdvance(() => {
        known.current = null;
        staleItems.current = lastItems.current;
      }),
    [],
  );

  // 판정은 자르기 전 목록으로: 보이는 목록으로 하면 상한 밖에 있던 옛 알림이 자리가 나서 들어올 때 「새 알림」으로 울린다
  const { allItems: items, enabled } = state;
  const loaded = enabled && !state.isPending && !state.isError;
  useEffect(() => {
    lastItems.current = items;
    if (!loaded || items === staleItems.current) return;
    staleItems.current = null;
    const isSameSession = captureSession();
    const toast = (options: ToastOptions) => {
      raisedToasts.add(showToast(options));
    };
    const openPanel = () => {
      if (isSameSession()) send({ kind: 'panel' });
    };
    const seen = known.current;
    if (seen === null) {
      known.current = new Map(items.map((n) => [n.id, n.held]));
      return;
    }
    const fresh: AppNotification[] = [];
    let released = 0;
    for (const n of items) {
      const wasHeld = seen.get(n.id);
      if (wasHeld === undefined) {
        if (!n.held && n.readAt === null) fresh.push(n);
      } else if (wasHeld && !n.held && n.readAt === null) {
        released += 1;
      }
      seen.set(n.id, n.held);
    }
    if (released > 0) {
      toast({
        tone: 'info',
        title: `방송 중 알림 ${released}개`,
        description: '방송하는 동안 쌓아 둔 알림이에요',
        dedupeKey: 'notifications-released',
        action: { label: '모아 보기', onClick: openPanel },
      });
    }
    if (fresh.length > MAX_SINGLE_TOASTS) {
      toast({
        tone: 'info',
        title: `새 알림 ${fresh.length}개`,
        dedupeKey: 'notifications-many',
        action: { label: '알림 보기', onClick: openPanel },
      });
      return;
    }
    const now = Date.now();
    for (const n of fresh) {
      const view = describeNotification(n, now);
      const sticky = n.important && isOpenTodo(n);
      toast({
        tone: TOAST_TONE[view.tone],
        title: view.title,
        description: view.detail,
        dedupeKey: `notification-${n.id}`,
        ...(sticky ? { duration: 0 } : {}),
        action: view.goLabel
          ? {
              label: view.goLabel,
              onClick: () => {
                if (isSameSession()) send({ kind: 'item', notification: n });
              },
            }
          : { label: '알림 보기', onClick: openPanel },
      });
    }
  }, [items, loaded, showToast, send]);
}
