'use client';

// 알림 목록 · 읽음 처리 · 초대 답 · 누르면 그 화면으로(POK-540).
// 서버가 없으면(NOTIFICATIONS_SERVER_READY=false) 목록을 읽지 않고 빈 목록이다. 개발 서버의 ?mock=notifications는 가짜 저장소를 쓴다.

import { useEffect, useMemo, useRef } from 'react';
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
  isInvitationGone,
  respondFailureMessage,
  type StreamerDelegation,
} from '@/api/editors';
import { useMe } from '@/features/auth/useSession';
import { useActiveChannel, useSwitchChannel } from '@/features/channel/useActiveChannel';
import { track } from '@/lib/analytics';
import { StaleSessionError, useSessionMutation } from '@/lib/useSessionMutation';
import { useToast, type ToastTone } from '@/ui';
import {
  fetchMockNotifications,
  markAllMockRead,
  markMockRead,
  resolveMock,
  useNotificationsMockMode,
} from './mockNotifications';
import {
  describeNotification,
  isOpenTodo,
  unreadCount,
  visibleNotifications,
  type NotificationTone,
} from './notificationView';

/** 서버가 실시간 전달(SSE 등)을 열기 전까지는 탭이 보이는 동안 이 주기로 다시 읽는다(react-query가 숨은 탭에선 멈춘다) */
const POLL_MS = 30_000;
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
  const items = useMemo(() => visibleNotifications(data?.items ?? [], Date.now()), [data]);

  // 읽음은 화면에 바로 그리고(낙관적) 실패하면 다시 읽는다 — 읽음 하나 실패로 토스트를 띄울 일은 아니다
  const markRead = useSessionMutation({
    mutationFn: (id: string) => (mockMode !== null ? markMockRead(id) : markNotificationRead(id)),
    onMutate: (id: string) => {
      const at = new Date().toISOString();
      patchCache(queryClient, key, (n) =>
        n.id === id && n.readAt === null ? { ...n, readAt: at } : n,
      );
    },
    onError: () => void queryClient.invalidateQueries({ queryKey: key }),
  });

  const markAllRead = useSessionMutation({
    mutationFn: () => (mockMode !== null ? markAllMockRead() : markAllNotificationsRead()),
    onMutate: () => {
      const at = new Date().toISOString();
      patchCache(queryClient, key, (n) => (n.held || n.readAt !== null ? n : { ...n, readAt: at }));
    },
    onError: () => void queryClient.invalidateQueries({ queryKey: key }),
  });

  return {
    items,
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
 * 편집자 초대에 수락 · 거절로 답한다 — 채널 스위처의 초대 줄과 같은 API · 같은 문구(ChannelSwitcher useInvitationAction).
 * 끝나면 받은 초대 · 참여 채널 · 알림을 다시 읽는다(서버가 그 알림에 resolvedAt을 채운다).
 */
export function useRespondInvitation(state: NotificationsState) {
  const { toast } = useToast();
  const queryClient = useQueryClient();
  return useSessionMutation({
    mutationFn: async ({ n, answer }: { n: AppNotification; answer: 'accept' | 'decline' }) => {
      if (n.payload.kind !== 'EDITOR_INVITED') return;
      if (state.mockMode !== null) return resolveMock(n.id);
      await (answer === 'accept' ? acceptInvitation : declineInvitation)(n.payload.invitationId);
    },
    onSuccess: (_data, { answer }) => {
      // 채널 스위처에서 답한 것과 같은 이벤트 — 경로가 달라도 응답률 집계가 갈리지 않게(상대 이름 · 초대 번호는 싣지 않는다)
      track('editor_invitation_responded', {
        outcome: answer === 'accept' ? 'accepted' : 'declined',
      });
      toast({
        tone: 'success',
        title: answer === 'accept' ? '초대를 수락했어요' : '초대를 거절했어요',
      });
    },
    onError: (e: unknown) => toast({ tone: 'error', ...respondFailureMessage(e) }),
    onSettled: (_data, error) => {
      void queryClient.invalidateQueries({ queryKey: ['editor-invitations'] });
      void queryClient.invalidateQueries({ queryKey: ['editor-delegations'] });
      if (error === null || isInvitationGone(error) || error instanceof StaleSessionError)
        void queryClient.invalidateQueries({ queryKey: state.queryKey });
    },
  });
}

/**
 * 알림이 가리키는 화면으로 간다. 알림이 다른 채널 일이면(내 채널 알림을 남의 채널을 보던 중에 눌렀거나, 맡은 채널 방송 시작)
 * 그 채널로 바꾼 다음 간다 — 채널 연동 같은 화면은 내 채널에서만 열린다. 참여 목록에 없는 채널(위임이 끊김)이면 바꾸지 않고 간다.
 */
export function useGoToNotification() {
  const router = useRouter();
  const me = useMe().data;
  const active = useActiveChannel();
  const switchTo = useSwitchChannel();
  const queryClient = useQueryClient();
  return (n: AppNotification, href: string) => {
    const current = active.ready ? active.streamerId : me?.id;
    if (!me || n.channel.id === current) {
      router.push(href);
      return;
    }
    const go = { onSwitched: () => router.push(href) };
    if (n.channel.id === me.id) {
      switchTo({ streamerId: me.id, name: me.name, relation: 'OWNER' }, go);
      return;
    }
    const list = queryClient.getQueryData<StreamerDelegation[]>(
      delegationsAsEditorQueryOptions.queryKey,
    );
    const target = list?.find((d) => d.streamerId === n.channel.id);
    if (target) {
      switchTo(
        {
          streamerId: target.streamerId,
          name: target.streamerName,
          relation: 'EDITOR',
          photo: target.streamerProfileImageUrl,
        },
        go,
      );
      return;
    }
    router.push(href);
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

/**
 * 새로 온 알림을 오른쪽 아래 토스트로 알린다. 처음 읽은 목록은 알리지 않는다(창을 열 때마다 쌓인 걸 다시 울리지 않게).
 * 쌓여 있던 것(held)이 풀리면(방송이 끝남) 하나씩이 아니라 「방송 중 알림 n개」 하나로 — 디자인 1n ②.
 * 「중요」로 받은 할 일(연동 만료 · 초대)은 닫을 때까지 남긴다.
 */
export function useArrivalToasts(
  state: NotificationsState,
  onOpenItem: (n: AppNotification) => void,
  onOpenPanel: () => void,
) {
  const { toast } = useToast();
  const known = useRef<Map<string, boolean> | null>(null);
  const openItem = useRef(onOpenItem);
  const openPanel = useRef(onOpenPanel);
  useEffect(() => {
    openItem.current = onOpenItem;
    openPanel.current = onOpenPanel;
  });

  const { items, enabled } = state;
  const loaded = enabled && !state.isPending && !state.isError;
  useEffect(() => {
    if (!loaded) return;
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
        action: { label: '모아 보기', onClick: () => openPanel.current() },
      });
    }
    if (fresh.length > MAX_SINGLE_TOASTS) {
      toast({
        tone: 'info',
        title: `새 알림 ${fresh.length}개`,
        dedupeKey: 'notifications-many',
        action: { label: '알림 보기', onClick: () => openPanel.current() },
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
          ? { label: view.goLabel, onClick: () => openItem.current(n) }
          : { label: '알림 보기', onClick: () => openPanel.current() },
      });
    }
  }, [items, loaded, toast]);
}
