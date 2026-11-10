import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '@/stores/auth';
import { useChannelStore } from '@/stores/channel';
import { useSwitchCurtain } from '@/features/channel/switchCurtain';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';
import { NotificationBell } from './NotificationBell';
import { useNotificationRequests } from './notificationRequests';

const { push, track } = vi.hoisted(() => ({ push: vi.fn(), track: vi.fn() }));

vi.mock('@/lib/analytics', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/analytics')>()),
  track,
}));

/** 사용 통계로 나간 그 이벤트의 속성들 */
const tracked = (name: string) =>
  track.mock.calls.filter(([n]) => n === name).map(([, props]) => props as Record<string, unknown>);

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace: vi.fn() }),
  usePathname: () => '/home',
}));

// 알림 서버가 열린 뒤의 동작을 본다 — 실제 상수는 서버가 생길 때까지 false다
vi.mock('@/api/notifications', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/notifications')>()),
  NOTIFICATIONS_SERVER_READY: true,
}));

const ME = { id: 1, email: 'poki@example.com', name: '포키왕', profileImageUrl: '' };
const MIN = 60_000;

interface Wire {
  id: string;
  kind: string;
  createdAt: string;
  readAt: string | null;
  resolvedAt: string | null;
  important: boolean;
  held: boolean;
  channelId: number;
  channelName: string;
  data: Record<string, unknown>;
}

function wire(
  id: string,
  minutesAgo: number,
  kind: string,
  data: Record<string, unknown>,
  extra: Partial<Wire> = {},
): Wire {
  return {
    id,
    kind,
    createdAt: new Date(Date.now() - minutesAgo * MIN).toISOString(),
    readAt: null,
    resolvedAt: null,
    important: false,
    held: false,
    channelId: ME.id,
    channelName: ME.name,
    data,
    ...extra,
  };
}

let items: Wire[] = [];

function seed() {
  const read = { readAt: new Date().toISOString() };
  items = [
    wire('n-upload', 2, 'CLIP_UPLOADED', { clipTitle: '펜타킬 각', count: 1, privacy: 'PRIVATE' }),
    wire(
      'n-youtube',
      3,
      'YOUTUBE_LINK_EXPIRED',
      { youtubeChannelName: '포키왕 TV' },
      { important: true },
    ),
    wire('n-editor', 4, 'EDITOR_UPLOADED', {
      editorName: '민트초코',
      clipTitle: '한타 대참사',
      privacy: 'PUBLIC',
    }),
    wire(
      'n-ended',
      6,
      'BROADCAST_ENDED',
      { streamId: 's1', broadcastTitle: '어제 방송', durationSec: 3600, cardCount: 9 },
      read,
    ),
    wire(
      'n-vod',
      7,
      'VOD_EXPIRING',
      {
        streamId: 's0',
        broadcastTitle: '솔랭',
        deletesAt: new Date(Date.now() + 3 * 86_400_000).toISOString(),
      },
      read,
    ),
  ];
}

/** 켜 두면 참여 채널 목록(as-editor)이 끝내 안 온다 — 새로고침 직후 채널이 아직 안 선 상태 */
let asEditorPending = false;
/** 참여 채널 목록(as-editor)이 지금 돌려주는 값(서버 꼴) */
let asEditor: unknown[] = [];
/** 켜 두면 참여 채널 목록(as-editor) 읽기가 실패한다 */
let asEditorFails = false;
/** 켜 두면 참여 채널 목록 요청을 붙잡아 두었다가 releaseAsEditor()에 그때의 asEditor · asEditorFails로 답한다 */
let holdAsEditor = false;
let heldAsEditor: (() => void)[] = [];
function releaseAsEditor() {
  holdAsEditor = false;
  heldAsEditor.splice(0).forEach((answer) => answer());
}
/** 켜 두면 다음 목록 요청 하나를 붙잡아, 요청이 나간 때의(옛) 값으로 releaseList()에 늦게 답한다 */
let holdList = false;
let releaseList: (() => void) | null = null;
/** 켜 두면 다음 읽음 요청 하나를 붙잡아 releaseRead()에 처리하고 답한다 */
let holdRead = false;
let releaseRead: (() => void) | null = null;
/** 초대 답(수락 · 거절)에 줄 상태 — 204가 아니면 처리하지 않고 그 상태로 실패한다(일시 장애 흉내) */
let answerStatus = 204;

/** 서버 흉내: 읽음 · 모두 읽음 · 초대 답을 받으면 목록 상태를 실제처럼 바꾼다(다시 읽으면 바뀐 값이 온다) */
function stub() {
  return stubFetch((url, init) => {
    if (url === '/api/auth/me') return jsonResponse(200, ME);
    if (url === '/api/editor-delegations/as-editor' && holdAsEditor)
      return new Promise<Response>((resolve) => {
        heldAsEditor.push(() =>
          resolve(asEditorFails ? jsonResponse(500, {}) : jsonResponse(200, asEditor)),
        );
      });
    if (url === '/api/editor-delegations/as-editor')
      return asEditorPending
        ? new Promise<Response>(() => {})
        : asEditorFails
          ? jsonResponse(500, {})
          : jsonResponse(200, asEditor);
    if (url === '/api/notifications' && holdList) {
      holdList = false;
      const then = items;
      return new Promise<Response>((resolve) => {
        releaseList = () => resolve(jsonResponse(200, { items: then }));
      });
    }
    if (url === '/api/notifications') return jsonResponse(200, { items });
    if (init?.method === 'POST' && holdRead && url.endsWith('/read')) {
      holdRead = false;
      const id = /^\/api\/notifications\/([^/]+)\/read$/.exec(url)?.[1];
      return new Promise<Response>((resolve) => {
        releaseRead = () => {
          const at = new Date().toISOString();
          items = items.map((w) => (w.id === id ? { ...w, readAt: w.readAt ?? at } : w));
          resolve(new Response(null, { status: 204 }));
        };
      });
    }
    if (init?.method === 'POST') {
      const at = new Date().toISOString();
      const read = /^\/api\/notifications\/([^/]+)\/read$/.exec(url);
      if (read) items = items.map((w) => (w.id === read[1] ? { ...w, readAt: w.readAt ?? at } : w));
      if (url === '/api/notifications/read-all')
        items = items.map((w) => (w.held ? w : { ...w, readAt: w.readAt ?? at }));
      const answered = /^\/api\/editor-invitations\/(\d+)\/(accept|decline)$/.exec(url);
      if (answered && answerStatus !== 204) return jsonResponse(answerStatus, {});
      if (answered)
        items = items.map((w) =>
          w.kind === 'EDITOR_INVITED' && w.data.invitationId === Number(answered[1])
            ? { ...w, resolvedAt: at }
            : w,
        );
      return new Response(null, { status: 204 });
    }
    return jsonResponse(404);
  });
}

function posts(spy: ReturnType<typeof stub>) {
  return spy.mock.calls
    .filter(([, init]) => (init as RequestInit | undefined)?.method === 'POST')
    .map(([url]) => String(url));
}

function renderBell() {
  const spy = stub();
  const result = renderWithProviders(<NotificationBell place="header" />);
  const user = userEvent.setup();
  return { ...result, spy, user };
}

async function openPanel(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: /^알림/ }));
  return screen.findByRole('dialog', { name: '알림' });
}

beforeEach(() => {
  seed();
  asEditorPending = false;
  asEditor = [];
  asEditorFails = false;
  holdAsEditor = false;
  heldAsEditor = [];
  holdList = false;
  releaseList = null;
  holdRead = false;
  releaseRead = null;
  answerStatus = 204;
  useSwitchCurtain.getState().reset();
  // 앞 시험에서 남은 부탁이 다음 시험의 종에 닿지 않게(종 수는 시험이 끝나며 떼어져 0이다)
  useNotificationRequests.setState({ seq: 0, request: null, pending: false });
  push.mockReset();
  track.mockReset();
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
  // 앞 시험이 채널을 바꾸며 남긴 선택(pc-channel)이 다음 시험의 hydrate에 읽히지 않게
  window.localStorage.removeItem('pc-channel');
  useChannelStore.setState({ userId: null, selected: null });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('NotificationBell', () => {
  it('받은 것 중 안 읽은 수를 종에 숫자로 보이고, 쌓아 둔 것 · 읽은 것은 세지 않는다', async () => {
    items.push(wire('n-held', 1, 'CLIP_UPLOADED', { clipTitle: 'x', count: 1 }, { held: true }));
    renderBell();
    expect(await screen.findByRole('button', { name: '알림 · 안 읽음 3개' })).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
  });

  it('열면 「안 읽음」 탭부터 — 읽은 일반 알림은 없고, 할 일은 읽었어도 맨 위 「확인 필요」에 있다', async () => {
    const { user } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    const panel = await openPanel(user);

    expect(within(panel).getByRole('button', { name: '안 읽음 3개' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    const todos = within(panel).getByRole('region', { name: '확인 필요' });
    expect(within(todos).getByText('유튜브 연동이 끊겼어요')).toBeInTheDocument();
    expect(within(todos).getByText('다시보기가 3일 뒤 삭제돼요')).toBeInTheDocument();
    expect(within(panel).getByText('클립을 올렸어요')).toBeInTheDocument();
    expect(within(panel).queryByText('방송이 끝났어요 · 다시보기 준비됨')).toBeNull();

    await user.click(within(panel).getByRole('button', { name: '전체' }));
    expect(within(panel).getByText('방송이 끝났어요 · 다시보기 준비됨')).toBeInTheDocument();
  });

  it('줄을 누르면 읽음을 보내고 창을 닫은 뒤 그 화면으로 간다', async () => {
    const { user, spy } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    const panel = await openPanel(user);

    await user.click(within(panel).getByRole('button', { name: /^안 읽음 · 클립을 올렸어요/ }));

    await waitFor(() => expect(posts(spy)).toEqual(['/api/notifications/n-upload/read']));
    expect(push).toHaveBeenCalledWith('/clips/library');
    expect(screen.queryByRole('dialog', { name: '알림' })).toBeNull();
    expect(screen.getByRole('button', { name: '알림 · 안 읽음 2개' })).toBeInTheDocument();
  });

  it('줄 오른쪽 「읽음으로 표시」는 화면을 옮기지 않고 읽음만 한다 — 창도 그대로', async () => {
    const { user, spy } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    const panel = await openPanel(user);

    await user.click(within(panel).getByRole('button', { name: '읽음으로 표시: 클립을 올렸어요' }));
    await user.click(
      within(panel).getByRole('button', { name: '읽음으로 표시: 유튜브 연동이 끊겼어요' }),
    );

    await waitFor(() =>
      expect(posts(spy)).toEqual([
        '/api/notifications/n-upload/read',
        '/api/notifications/n-youtube/read',
      ]),
    );
    expect(push).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog', { name: '알림' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '알림 · 안 읽음 1개' })).toBeInTheDocument();
    // 읽은 줄은 닫기 전까지 그 자리에 흐리게 남고, 다시 누를 단추는 사라진다 · 할 일은 처리 전까지 남는다
    expect(within(panel).getByText('클립을 올렸어요')).toBeInTheDocument();
    expect(
      within(panel).queryByRole('button', { name: '읽음으로 표시: 클립을 올렸어요' }),
    ).toBeNull();
    expect(within(panel).getByText('유튜브 연동이 끊겼어요')).toBeInTheDocument();
  });

  it('읽음이 날아가는 사이 새로 나간 주기 · 포커스 재조회가 옛 값을 가져와도 읽음 표시를 되돌리지 않는다 (PR #481 5차)', async () => {
    const { user, queryClient } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    const panel = await openPanel(user);

    holdRead = true;
    await user.click(within(panel).getByRole('button', { name: '읽음으로 표시: 클립을 올렸어요' }));
    expect(screen.getByRole('button', { name: '알림 · 안 읽음 2개' })).toBeInTheDocument();
    // 읽음 POST가 아직 날아가는 중에 30초 주기가 돌았다 — 서버는 아직 readAt null을 준다. 화면 알림은 다음 틱이라 한 틱 기다린다
    await act(() => queryClient.refetchQueries({ queryKey: ['notifications'] }));
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    expect(screen.getByRole('button', { name: '알림 · 안 읽음 2개' })).toBeInTheDocument();

    act(() => releaseRead?.());
    await waitFor(() => expect(queryClient.isFetching()).toBe(0));
    await waitFor(() => expect(queryClient.isMutating()).toBe(0));
    expect(screen.getByRole('button', { name: '알림 · 안 읽음 2개' })).toBeInTheDocument();
  });

  it('읽음을 누르기 직전에 나간 재조회가 옛 값으로 늦게 와도 읽음 표시를 되돌리지 않는다', async () => {
    const { user, queryClient, spy } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    const panel = await openPanel(user);
    const listCalls = () => spy.mock.calls.filter(([url]) => url === '/api/notifications').length;

    // 주기 재조회가 나간 사이(아직 응답 전)에 읽음을 누르고, 읽음 응답도 붙잡아 둔다
    holdList = true;
    act(() => void queryClient.invalidateQueries({ queryKey: ['notifications'] }));
    await waitFor(() => expect(releaseList).not.toBeNull());
    holdRead = true;
    await user.click(within(panel).getByRole('button', { name: '읽음으로 표시: 클립을 올렸어요' }));
    await waitFor(() => expect(releaseRead).not.toBeNull());

    // 늦게 온 옛 응답(readAt null)이 방금 그린 읽음을 덮지 않는다 — 그 응답 처리가 다 끝난 뒤에 본다
    await act(async () => releaseList?.());
    await waitFor(() => expect(queryClient.isFetching({ queryKey: ['notifications'] })).toBe(0));
    await act(async () => {
      await new Promise((r) => setTimeout(r, 20));
    });
    expect(screen.getByRole('button', { name: '알림 · 안 읽음 2개' })).toBeInTheDocument();

    // 읽음이 끝나면 서버 값으로 다시 맞춘다 — 그 값도 읽음이다
    const before = listCalls();
    await act(async () => releaseRead?.());
    await waitFor(() => expect(listCalls()).toBe(before + 1));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '알림 · 안 읽음 2개' })).toBeInTheDocument(),
    );
    expect(
      within(panel).queryByRole('button', { name: '읽음으로 표시: 클립을 올렸어요' }),
    ).toBeNull();
  });

  it('50개 상한 밖에 있던 옛 알림이 자리가 나서 창에 들어와도 「새 알림」 토스트로 울리지 않는다', async () => {
    // 20일 된 풀리지 않은 할 일 1개 + 최근 알림 50개 → 보이는 것은 할 일 + 최근 49개(가장 옛 「옛 49」는 밖)
    items = [
      ...Array.from({ length: 50 }, (_, i) =>
        wire(`old-${i}`, 2 + i, 'CLIP_UPLOADED', {
          clipTitle: `옛 ${i}`,
          count: 1,
          privacy: 'PRIVATE',
        }),
      ),
      wire('todo-old', 20 * 24 * 60, 'CHAT_LINK_BROKEN', { platform: 'CHZZK' }),
    ];
    const { queryClient } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 50개' });

    // 할 일이 풀린다 → 기간 밖이라 빠지고 자리가 나 「옛 49」가 들어온다
    items = items.map((w) =>
      w.id === 'todo-old' ? { ...w, resolvedAt: new Date().toISOString() } : w,
    );
    await act(() => queryClient.invalidateQueries({ queryKey: ['notifications'] }));
    await waitFor(() => expect(queryClient.isFetching({ queryKey: ['notifications'] })).toBe(0));
    // 받은 목록이 그려지고 새 알림 판정 효과가 돈 뒤에 본다
    await act(async () => {
      await new Promise((r) => setTimeout(r, 50));
    });

    expect(screen.queryByText('「옛 49」 · 비공개')).toBeNull();
    expect(screen.queryByText(/^새 알림/)).toBeNull();
  });

  it('읽음 둘이 겹쳐도 먼저 끝난 쪽의 다시 읽기가 아직 날아가는 쪽의 읽음을 되돌리지 않는다 (PR #481 2차)', async () => {
    const { user, queryClient, spy } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    const panel = await openPanel(user);

    // A의 읽음 요청은 붙잡아 두고, 바로 이어 B를 누른다(B는 곧장 끝난다)
    holdRead = true;
    await user.click(within(panel).getByRole('button', { name: '읽음으로 표시: 클립을 올렸어요' }));
    await waitFor(() => expect(releaseRead).not.toBeNull());
    await user.click(
      within(panel).getByRole('button', {
        name: '읽음으로 표시: 민트초코 님이 내 채널에 올렸어요',
      }),
    );
    await waitFor(() => expect(posts(spy)).toContain('/api/notifications/n-editor/read'));
    await waitFor(() => expect(queryClient.isFetching({ queryKey: ['notifications'] })).toBe(0));
    await act(async () => {
      await new Promise((r) => setTimeout(r, 50));
    });

    // B가 끝났어도 A는 아직 날아가는 중이라 다시 읽지 않는다 — A의 읽음이 되살아나지 않는다
    expect(screen.getByRole('button', { name: '알림 · 안 읽음 1개' })).toBeInTheDocument();

    // A가 끝나면 마지막이라 한 번 다시 읽고, 서버 값도 둘 다 읽음이다
    await act(async () => releaseRead?.());
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '알림 · 안 읽음 1개' })).toBeInTheDocument(),
    );
    await act(() => queryClient.refetchQueries({ queryKey: ['notifications'] }));
    expect(screen.getByRole('button', { name: '알림 · 안 읽음 1개' })).toBeInTheDocument();
  });

  it('14일이 지난 알림은 안 보이고, 풀리지 않은 할 일은 기간이 지나도 남는다', async () => {
    items.push(
      wire('n-old', 15 * 24 * 60, 'CLIP_UPLOADED', { clipTitle: '옛날 클립', count: 1 }),
      wire('n-old-todo', 40 * 24 * 60, 'CHAT_LINK_BROKEN', { platform: 'SOOP' }),
    );
    const { user } = renderBell();
    // 지난 클립은 세지 않고, 풀리지 않은 채팅 끊김은 센다: 3 + 1
    await screen.findByRole('button', { name: '알림 · 안 읽음 4개' });
    const panel = await openPanel(user);
    await user.click(within(panel).getByRole('button', { name: '전체' }));

    expect(within(panel).queryByText(/옛날 클립/)).toBeNull();
    expect(within(panel).getByText('SOOP 채팅을 못 모으고 있어요')).toBeInTheDocument();
    expect(within(panel).getByText('최근 14일 · 최대 50개까지 보여요')).toBeInTheDocument();
  });

  it('창을 열고 닫는 것만으로는 읽음이 되지 않는다', async () => {
    const { user, spy } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    await openPanel(user);
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '알림' })).toBeNull());
    await openPanel(user);
    await user.click(screen.getByRole('button', { name: /^알림 · 안 읽음/ }));

    expect(posts(spy)).toEqual([]);
    expect(screen.getByRole('button', { name: '알림 · 안 읽음 3개' })).toBeInTheDocument();
  });

  it('「모두 읽음」 뒤 읽은 줄은 닫기 전까지 「안 읽음」 탭에 남고, 다시 열면 빠진다', async () => {
    const { user, spy } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    let panel = await openPanel(user);

    await user.click(within(panel).getByRole('button', { name: '모두 읽음' }));
    await waitFor(() => expect(posts(spy)).toEqual(['/api/notifications/read-all']));
    expect(screen.getByRole('button', { name: '알림' })).toBeInTheDocument();
    expect(within(panel).getByText('클립을 올렸어요')).toBeInTheDocument();

    items = items.map((w) => ({ ...w, readAt: w.readAt ?? new Date().toISOString() }));
    await user.click(screen.getByRole('button', { name: '알림' }));
    panel = await openPanel(user);
    expect(within(panel).queryByText('클립을 올렸어요')).toBeNull();
    expect(within(panel).getByText('새 알림이 없어요')).toBeInTheDocument();
    // 할 일은 읽어도 남는다
    expect(within(panel).getByText('유튜브 연동이 끊겼어요')).toBeInTheDocument();
  });

  it('채널이 아직 안 섰으면 저장된 선택으로 판단해, 남의 채널을 고른 상태면 내 채널로 바꾼 뒤 간다', async () => {
    // 새로고침 직후: 이 회원으로 읽힌 저장값은 남의 채널(9), 참여 목록은 아직 안 왔다
    asEditorPending = true;
    useChannelStore.setState({ userId: ME.id, selected: 9 });
    const { user } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    const panel = await openPanel(user);

    await user.click(within(panel).getByRole('button', { name: '다시 연결' }));

    // 바로 가지 않고, 갈 주소를 실은 채 내 채널로 바꾸는 덮개를 세운다
    expect(push).not.toHaveBeenCalled();
    expect(useSwitchCurtain.getState().curtain).toMatchObject({
      name: '포키왕',
      relation: 'OWNER',
      destination: '/settings/channels',
    });
    await waitFor(() => expect(push).toHaveBeenCalledWith('/settings/channels'));
    expect(useChannelStore.getState().selected).toBeNull();
  });

  it('받아 둔 참여 목록에 없는 채널이면 새로 받아 보고, 막 수락한 채널이면 그 채널로 바꾼 뒤 간다 (PR #481 3차)', async () => {
    items.push(
      wire(
        'n-live',
        1,
        'MANAGED_BROADCAST_STARTED',
        { broadcastTitle: '솔랭' },
        { channelId: 9, channelName: '라면왕' },
      ),
    );
    const { user } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 4개' });
    const panel = await openPanel(user);
    // 이 탭이 받아 둔 목록은 빈 채로, 다른 기기에서 막 수락했다
    asEditor = [
      {
        id: 1,
        counterpartId: 9,
        counterpartName: '라면왕',
        counterpartProfileImageUrl: null,
        grantedAt: new Date().toISOString(),
      },
    ];

    await user.click(
      within(panel).getByRole('button', { name: /^안 읽음 · 라면왕 채널이 방송을 시작했어요/ }),
    );

    await waitFor(() =>
      expect(useSwitchCurtain.getState().curtain).toMatchObject({
        name: '라면왕',
        relation: 'EDITOR',
        destination: '/broadcast/livenow',
      }),
    );
    await waitFor(() => expect(push).toHaveBeenCalledWith('/broadcast/livenow'));
  });

  it('새로 받아도 참여 목록에 없는 채널이면 지금 채널 화면으로 가지 않고 알린다 (PR #481 3차)', async () => {
    items.push(
      wire(
        'n-live',
        1,
        'MANAGED_BROADCAST_STARTED',
        { broadcastTitle: '솔랭' },
        { channelId: 9, channelName: '라면왕' },
      ),
    );
    const { user } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 4개' });
    const panel = await openPanel(user);

    await user.click(
      within(panel).getByRole('button', { name: /^안 읽음 · 라면왕 채널이 방송을 시작했어요/ }),
    );

    expect(await screen.findByText('라면왕 채널의 편집 권한이 없어요')).toBeInTheDocument();
    expect(push).not.toHaveBeenCalled();
    expect(useSwitchCurtain.getState().curtain).toBeNull();
  });

  it('참여 목록을 다시 받지 못하면 확인 못 한 채널로 바꾸지 않고 알린다 — 내 채널 라이브가 뜨지 않게 (PR #481 4차)', async () => {
    items.push(
      wire(
        'n-live',
        1,
        'MANAGED_BROADCAST_STARTED',
        { broadcastTitle: '솔랭' },
        { channelId: 9, channelName: '라면왕' },
      ),
    );
    const { user } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 4개' });
    const panel = await openPanel(user);
    asEditorFails = true;

    await user.click(
      within(panel).getByRole('button', { name: /^안 읽음 · 라면왕 채널이 방송을 시작했어요/ }),
    );

    expect(await screen.findByText('채널 정보를 확인하지 못했어요')).toBeInTheDocument();
    expect(push).not.toHaveBeenCalled();
    expect(useSwitchCurtain.getState().curtain).toBeNull();
  });

  it('참여 목록 첫 읽기가 실패해 내 채널로 풀려 있어도, 남의 채널 선택이 남았으면 내 채널로 바꾼 뒤 간다 (PR #481 4차)', async () => {
    asEditorFails = true;
    useChannelStore.setState({ userId: ME.id, selected: 9 });
    const { user, queryClient } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    await waitFor(() =>
      expect(queryClient.getQueryState(['editor-delegations', 'as-editor'])?.status).toBe('error'),
    );
    const panel = await openPanel(user);

    await user.click(within(panel).getByRole('button', { name: '다시 연결' }));

    expect(push).not.toHaveBeenCalled();
    expect(useSwitchCurtain.getState().curtain).toMatchObject({
      relation: 'OWNER',
      destination: '/settings/channels',
    });
    await waitFor(() => expect(push).toHaveBeenCalledWith('/settings/channels'));
    expect(useChannelStore.getState().selected).toBeNull();
  });

  it('새로고침 직후 저장된 남의 채널 알림은 확인 전엔 가지 않고, 확인이 실패하면 알린다 (PR #481 5차)', async () => {
    items.push(
      wire(
        'n-live',
        1,
        'MANAGED_BROADCAST_STARTED',
        { broadcastTitle: '솔랭' },
        { channelId: 9, channelName: '라면왕' },
      ),
    );
    holdAsEditor = true;
    useChannelStore.setState({ userId: ME.id, selected: 9 });
    const { user } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 4개' });
    const panel = await openPanel(user);

    await user.click(
      within(panel).getByRole('button', { name: /^안 읽음 · 라면왕 채널이 방송을 시작했어요/ }),
    );
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    expect(push).not.toHaveBeenCalled();

    asEditorFails = true;
    act(() => releaseAsEditor());
    expect(await screen.findByText('채널 정보를 확인하지 못했어요')).toBeInTheDocument();
    expect(push).not.toHaveBeenCalled();
  });

  it('새로고침 직후 저장된 남의 채널 알림은 확인되면 전환 화면 없이 간다 (PR #481 5차)', async () => {
    items.push(
      wire(
        'n-live',
        1,
        'MANAGED_BROADCAST_STARTED',
        { broadcastTitle: '솔랭' },
        { channelId: 9, channelName: '라면왕' },
      ),
    );
    holdAsEditor = true;
    asEditor = [
      {
        id: 1,
        counterpartId: 9,
        counterpartName: '라면왕',
        counterpartProfileImageUrl: null,
        grantedAt: new Date().toISOString(),
      },
    ];
    useChannelStore.setState({ userId: ME.id, selected: 9 });
    const { user } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 4개' });
    const panel = await openPanel(user);

    await user.click(
      within(panel).getByRole('button', { name: /^안 읽음 · 라면왕 채널이 방송을 시작했어요/ }),
    );
    act(() => releaseAsEditor());
    await waitFor(() => expect(push).toHaveBeenCalledWith('/broadcast/livenow'));
    expect(useSwitchCurtain.getState().curtain).toBeNull();
  });

  it('창을 다시 열면 「전체」를 보다 닫았어도 「안 읽음」 탭부터 보인다', async () => {
    const { user } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    let panel = await openPanel(user);
    await user.click(within(panel).getByRole('button', { name: '전체' }));
    expect(within(panel).getByRole('button', { name: '전체' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '알림' })).toBeNull());
    panel = await openPanel(user);

    expect(within(panel).getByRole('button', { name: '안 읽음 3개' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(within(panel).queryByText('방송이 끝났어요 · 다시보기 준비됨')).toBeNull();
  });

  it('키보드로 「읽음으로 표시」 · 「모두 읽음」을 눌러도 포커스가 창 안 그 자리에 남는다', async () => {
    const { user } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    const panel = await openPanel(user);

    within(panel).getByRole('button', { name: '읽음으로 표시: 클립을 올렸어요' }).focus();
    await user.keyboard('{Enter}');
    // 단추가 사라진 자리 대신 그 알림 줄로 옮긴다
    expect(document.activeElement).toBe(
      within(panel).getByRole('button', { name: /^클립을 올렸어요/ }),
    );

    const readAll = within(panel).getByRole('button', { name: '모두 읽음' });
    readAll.focus();
    await user.keyboard('{Enter}');
    await waitFor(() => expect(readAll).toHaveAttribute('aria-disabled', 'true'));
    expect(document.activeElement).toBe(readAll);
  });

  it('창이 열린 동안 온 알림도 읽었을 때 닫기 전까지 「안 읽음」 탭에 남는다', async () => {
    const { user, queryClient } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    const panel = await openPanel(user);

    items = [
      wire('n-new', 0, 'EDITOR_UPLOADED', {
        editorName: '새봄',
        clipTitle: '드래곤 스틸',
        privacy: 'PRIVATE',
      }),
      ...items,
    ];
    await act(() => queryClient.invalidateQueries({ queryKey: ['notifications'] }));
    await user.click(
      await within(panel).findByRole('button', {
        name: '읽음으로 표시: 새봄 님이 내 채널에 올렸어요',
      }),
    );

    // 방금 온 줄이 읽은 뒤에도 흐리게 남는다(점 단추만 사라짐)
    await waitFor(() =>
      expect(
        within(panel).queryByRole('button', {
          name: '읽음으로 표시: 새봄 님이 내 채널에 올렸어요',
        }),
      ).toBeNull(),
    );
    expect(within(panel).getByText('새봄 님이 내 채널에 올렸어요')).toBeInTheDocument();
  });

  it('확인 필요 카드의 버튼은 그 일을 하는 화면으로 간다', async () => {
    const { user } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    const panel = await openPanel(user);

    await user.click(within(panel).getByRole('button', { name: '다시 연결' }));
    expect(push).toHaveBeenCalledWith('/settings/channels');
  });

  it('새로 온 알림은 토스트로 알리고, 처음 읽은 목록은 알리지 않는다', async () => {
    const { queryClient } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    expect(screen.queryByText('「펜타킬 각」 · 비공개')).toBeNull();

    items = [
      wire('n-new', 0, 'CLIP_UPLOADED', {
        clipTitle: '드래곤 스틸',
        count: 1,
        privacy: 'PRIVATE',
      }),
      ...items,
    ];
    await act(() => queryClient.invalidateQueries({ queryKey: ['notifications'] }));

    expect(await screen.findByText('「드래곤 스틸」 · 비공개')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '알림 · 안 읽음 4개' })).toBeInTheDocument();
  });

  it('방송 중 쌓아 둔 알림은 접힌 묶음으로만 보이고, 풀리면 「방송 중 알림 n개」 하나로 알린다', async () => {
    items.push(
      wire('n-held-1', 1, 'CLIP_UPLOADED', { clipTitle: 'a', count: 1 }, { held: true }),
      wire('n-held-2', 1, 'CLIP_FAILED', { clipTitle: 'b', stage: 'RENDER' }, { held: true }),
    );
    const { user, queryClient } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    const panel = await openPanel(user);

    expect(within(panel).getByText('방송 중이라 「중요」 알림만 띄워요')).toBeInTheDocument();
    const held = within(panel).getByRole('button', { name: /방송이 끝나면 알려 드릴 알림/ });
    expect(held).toHaveAttribute('aria-expanded', 'false');
    expect(within(panel).queryByText('클립을 만들지 못했어요')).toBeNull();
    await user.click(held);
    expect(within(panel).getByText('클립을 만들지 못했어요')).toBeInTheDocument();

    await user.keyboard('{Escape}');
    items = items.map((w) => ({ ...w, held: false }));
    await act(() => queryClient.invalidateQueries({ queryKey: ['notifications'] }));

    expect(await screen.findByText('방송 중 알림 2개')).toBeInTheDocument();
    expect(screen.queryByText('「a」 · 비공개')).toBeNull();
    expect(screen.getByRole('button', { name: '알림 · 안 읽음 5개' })).toBeInTheDocument();
  });

  it('쌓아 둔 알림을 펴서 읽으면 띠 · 묶음의 「알려 드릴」 수도 줄고, 방송이 끝날 때 토스트 수와 맞는다 (PR #481 4차)', async () => {
    items.push(
      wire('n-held-1', 1, 'CLIP_UPLOADED', { clipTitle: 'a', count: 1 }, { held: true }),
      wire('n-held-2', 1, 'CLIP_FAILED', { clipTitle: 'b', stage: 'RENDER' }, { held: true }),
    );
    const { user, queryClient } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    const panel = await openPanel(user);
    expect(
      within(panel).getByText('나머지 2개는 방송이 끝나면 한 번에 알려 드려요'),
    ).toBeInTheDocument();

    await user.click(within(panel).getByRole('button', { name: /방송이 끝나면 알려 드릴 알림/ }));
    await user.click(
      within(panel).getByRole('button', { name: '읽음으로 표시: 클립을 만들지 못했어요' }),
    );
    expect(
      await within(panel).findByText('나머지 1개는 방송이 끝나면 한 번에 알려 드려요'),
    ).toBeInTheDocument();
    expect(
      within(panel).getByRole('button', { name: /방송이 끝나면 알려 드릴 알림 1개/ }),
    ).toBeInTheDocument();

    await user.keyboard('{Escape}');
    await waitFor(() => expect(queryClient.isFetching()).toBe(0));
    items = items.map((w) => ({ ...w, held: false }));
    await act(() => queryClient.invalidateQueries({ queryKey: ['notifications'] }));
    expect(await screen.findByText('방송 중 알림 1개')).toBeInTheDocument();
  });

  it('화면을 옮겨 종이 바뀐 뒤에도 토스트의 「알림 보기」는 지금 떠 있는 종의 창을 연다', async () => {
    // 홈 헤더 종 ↔ 사이드바 종: 토스트는 앱 전체에 남고 종만 바뀐다
    function Swap() {
      const [sidebar, setSidebar] = useState(false);
      return (
        <>
          {sidebar ? (
            <NotificationBell key="sidebar" place="sidebar" />
          ) : (
            <NotificationBell key="header" place="header" />
          )}
          <button type="button" onClick={() => setSidebar(true)}>
            화면 옮기기
          </button>
        </>
      );
    }
    stub();
    const { queryClient } = renderWithProviders(<Swap />);
    const user = userEvent.setup();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });

    // 닫을 때까지 남는 초대 토스트가 헤더 종에서 뜬다
    items = [
      wire(
        'n-invite',
        0,
        'EDITOR_INVITED',
        { invitationId: 7 },
        { channelId: 9, channelName: '라면왕', important: true },
      ),
      ...items,
    ];
    await act(() => queryClient.invalidateQueries({ queryKey: ['notifications'] }));
    expect(await screen.findByText('라면왕 채널이 편집자로 초대했어요')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '화면 옮기기' }));
    await screen.findByRole('button', { name: '알림 · 안 읽음 4개' });
    await user.click(screen.getByRole('button', { name: '알림 보기' }));

    expect(await screen.findByRole('dialog', { name: '알림' })).toBeInTheDocument();
  });

  it('종이 없는 화면(전체 화면 편집기)에서 토스트를 누르면 홈으로 옮기고, 다음에 뜬 종이 그 부탁을 받는다 (PR #481 2차)', async () => {
    function Screens() {
      const [screenName, setScreen] = useState<'home' | 'editor' | 'back'>('home');
      return (
        <>
          {screenName === 'home' ? <NotificationBell key="header" place="header" /> : null}
          {screenName === 'back' ? <NotificationBell key="header-2" place="header" /> : null}
          <button type="button" onClick={() => setScreen('editor')}>
            편집기로
          </button>
          <button type="button" onClick={() => setScreen('back')}>
            홈 도착
          </button>
        </>
      );
    }
    stub();
    const { queryClient } = renderWithProviders(<Screens />);
    const user = userEvent.setup();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });

    items = [
      wire(
        'n-invite',
        0,
        'EDITOR_INVITED',
        { invitationId: 7 },
        { channelId: 9, channelName: '라면왕', important: true },
      ),
      ...items,
    ];
    await act(() => queryClient.invalidateQueries({ queryKey: ['notifications'] }));
    expect(await screen.findByText('라면왕 채널이 편집자로 초대했어요')).toBeInTheDocument();

    // 종이 없는 화면에서 누른다 → 아무도 받지 않으니 홈으로 옮긴다
    await user.click(screen.getByRole('button', { name: '편집기로' }));
    await user.click(screen.getByRole('button', { name: '알림 보기' }));
    expect(push).toHaveBeenCalledWith('/home');
    expect(screen.queryByRole('dialog', { name: '알림' })).toBeNull();

    // 홈에 닿아 종이 뜨면 남은 부탁을 받아 창을 연다
    await user.click(screen.getByRole('button', { name: '홈 도착' }));
    expect(await screen.findByRole('dialog', { name: '알림' })).toBeInTheDocument();
  });

  it('계정이 바뀌면 앞 계정의 토스트를 닫고, 새 계정의 기존 알림은 새 알림으로 울리지 않는다 (PR #481 3차)', async () => {
    const { queryClient } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    // 앞 계정에 「중요」 할 일이 와서 닫을 때까지 남는 토스트가 뜬다
    items = [
      wire('n-chat', 0, 'CHAT_LINK_BROKEN', { platform: 'SOOP' }, { important: true }),
      ...items,
    ];
    await act(() => queryClient.invalidateQueries({ queryKey: ['notifications'] }));
    expect(await screen.findByText('SOOP 채팅을 못 모으고 있어요')).toBeInTheDocument();

    // 다른 탭에서 다른 계정으로 로그인: 세션이 바뀌고, 종은 그대로인 채 목록을 새 계정 것으로 다시 읽는다
    act(() =>
      useAuthStore.getState().setTokens({ accessToken: 'access-2', refreshToken: 'refresh-2' }),
    );
    await waitFor(() => expect(screen.queryByText('SOOP 채팅을 못 모으고 있어요')).toBeNull());
    items = [
      wire('m-1', 1, 'CLIP_UPLOADED', { clipTitle: '새 계정 클립', count: 1, privacy: 'PUBLIC' }),
      wire('m-2', 2, 'CLIP_UPLOADED', { clipTitle: '새 계정 둘째', count: 1, privacy: 'PUBLIC' }),
    ];
    await act(() => queryClient.resetQueries());
    await screen.findByRole('button', { name: '알림 · 안 읽음 2개' });
    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));
    expect(screen.queryByText('「새 계정 클립」 · 공개')).toBeNull();
    expect(screen.queryByText('「새 계정 둘째」 · 공개')).toBeNull();
  });

  it('종이 없는 화면에서 계정이 바뀌어도 앞 계정의 토스트를 닫고, 남은 부탁은 다음 종이 처리하지 않는다 (PR #481 3차)', async () => {
    function Screens() {
      const [screenName, setScreen] = useState<'home' | 'editor' | 'back'>('home');
      return (
        <>
          {screenName === 'home' ? <NotificationBell key="header" place="header" /> : null}
          {screenName === 'back' ? <NotificationBell key="header-2" place="header" /> : null}
          <button type="button" onClick={() => setScreen('editor')}>
            편집기로
          </button>
          <button type="button" onClick={() => setScreen('back')}>
            홈 도착
          </button>
        </>
      );
    }
    stub();
    const { queryClient } = renderWithProviders(<Screens />);
    const user = userEvent.setup();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    items = [
      wire(
        'n-invite',
        0,
        'EDITOR_INVITED',
        { invitationId: 7 },
        { channelId: 9, channelName: '라면왕', important: true },
      ),
      wire('n-chat', 0, 'CHAT_LINK_BROKEN', { platform: 'SOOP' }, { important: true }),
      ...items,
    ];
    await act(() => queryClient.invalidateQueries({ queryKey: ['notifications'] }));
    expect(await screen.findByText('SOOP 채팅을 못 모으고 있어요')).toBeInTheDocument();

    // 종이 없는 화면에서 초대 토스트의 「알림 보기」를 눌러 부탁이 남은 채 다른 계정으로 바뀐다
    await user.click(screen.getByRole('button', { name: '편집기로' }));
    await user.click(screen.getByRole('button', { name: '알림 보기' }));
    act(() =>
      useAuthStore.getState().setTokens({ accessToken: 'access-2', refreshToken: 'refresh-2' }),
    );
    await waitFor(() => expect(screen.queryByText('SOOP 채팅을 못 모으고 있어요')).toBeNull());

    await user.click(screen.getByRole('button', { name: '홈 도착' }));
    await screen.findByRole('button', { name: /^알림/ });
    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));
    expect(screen.queryByRole('dialog', { name: '알림' })).toBeNull();
  });

  it('편집자 초대는 수락을 바로 보내고, 거절은 카드 안에서 한 번 더 묻는다', async () => {
    items.push(
      wire(
        'n-invite',
        1,
        'EDITOR_INVITED',
        { invitationId: 7 },
        { channelId: 9, channelName: '라면왕', important: true },
      ),
    );
    const { user, spy } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 4개' });
    const panel = await openPanel(user);

    await user.click(within(panel).getByRole('button', { name: '거절' }));
    expect(
      within(panel).getByText('거절하면 라면왕 채널이 다시 초대해야 참여할 수 있어요'),
    ).toBeInTheDocument();
    await user.click(within(panel).getByRole('button', { name: '취소' }));
    expect(posts(spy)).toEqual([]);

    await user.click(within(panel).getByRole('button', { name: '수락' }));
    // 읽음은 재조회를 먼저 멈추고 보내서 두 요청의 순서는 정해져 있지 않다
    await waitFor(() =>
      expect(posts(spy).sort()).toEqual([
        '/api/editor-invitations/7/accept',
        '/api/notifications/n-invite/read',
      ]),
    );
    expect(await screen.findByText('초대를 수락했어요')).toBeInTheDocument();
    expect(tracked('editor_invitation_responded')).toEqual([{ outcome: 'accepted' }]);
  });

  it('키보드로 거절을 누르면 확인의 「취소」로, 취소하면 「거절」로 포커스가 간다 (PR #481 3차)', async () => {
    items.push(
      wire(
        'n-invite',
        1,
        'EDITOR_INVITED',
        { invitationId: 7 },
        { channelId: 9, channelName: '라면왕', important: true },
      ),
    );
    const { user } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 4개' });
    const panel = await openPanel(user);

    within(panel).getByRole('button', { name: '거절' }).focus();
    await user.keyboard('{Enter}');
    expect(within(panel).getByRole('button', { name: '취소' })).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(within(panel).getByRole('button', { name: '거절' })).toHaveFocus();
  });

  it('토스트의 「알림 보기」로 연 창을 Esc로 닫으면 포커스가 종으로 돌아간다 (PR #481 4차)', async () => {
    const { user, queryClient } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    items = [
      wire(
        'n-invite',
        0,
        'EDITOR_INVITED',
        { invitationId: 7 },
        { channelId: 9, channelName: '라면왕', important: true },
      ),
      ...items,
    ];
    await act(() => queryClient.invalidateQueries({ queryKey: ['notifications'] }));
    await screen.findByText('라면왕 채널이 편집자로 초대했어요');

    screen.getByRole('button', { name: '알림 보기' }).focus();
    await user.keyboard('{Enter}');
    expect(await screen.findByRole('dialog', { name: '알림' })).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '알림' })).toBeNull());
    expect(screen.getByRole('button', { name: /^알림 · 안 읽음/ })).toHaveFocus();
  });

  it('키보드로 초대를 수락해 「확인 필요」 카드가 줄로 바뀌면 포커스가 그 줄로 간다 (PR #481 4차)', async () => {
    items.push(
      wire(
        'n-invite',
        1,
        'EDITOR_INVITED',
        { invitationId: 7 },
        { channelId: 9, channelName: '라면왕', important: true },
      ),
    );
    const { user, queryClient } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 4개' });
    const panel = await openPanel(user);

    within(panel).getByRole('button', { name: '수락' }).focus();
    await user.keyboard('{Enter}');
    expect(await screen.findByText('초대를 수락했어요')).toBeInTheDocument();
    await waitFor(() => expect(within(panel).queryByRole('button', { name: '수락' })).toBeNull());
    await waitFor(() => expect(queryClient.isFetching()).toBe(0));
    expect(
      within(panel).getByRole('button', { name: /^라면왕 채널이 편집자로 초대했어요/ }),
    ).toHaveFocus();
  });

  it('포커스를 줄 밖으로 옮긴 뒤 잃은 포커스는 다음 다시 그리기 때 줄로 끌어오지 않는다 (PR #481 5차)', async () => {
    const { user, queryClient } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    const panel = await openPanel(user);

    within(panel)
      .getByRole('button', { name: /^안 읽음 · 클립을 올렸어요/ })
      .focus();
    // 탭 단추로 옮겼다가(줄 밖) 포커스를 잃는다 — 사라진 단추 때문이 아니니 되돌릴 일이 아니다
    within(panel).getByRole('button', { name: '전체' }).focus();
    (document.activeElement as HTMLElement).blur();
    expect(document.activeElement).toBe(document.body);

    // 30초 재조회로 새 알림이 와서 창이 다시 그려진다
    items = [
      wire('n-new', 0, 'CLIP_UPLOADED', { clipTitle: '드래곤 스틸', count: 1, privacy: 'PRIVATE' }),
      ...items,
    ];
    await act(() => queryClient.invalidateQueries({ queryKey: ['notifications'] }));
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    expect(document.activeElement).toBe(document.body);
  });

  it('초대 답이 먼저 끝나도 아직 날아가는 읽음을 다시 읽기가 되돌리지 않는다 (PR #481 4차)', async () => {
    items.push(
      wire(
        'n-invite',
        1,
        'EDITOR_INVITED',
        { invitationId: 7 },
        { channelId: 9, channelName: '라면왕', important: true },
      ),
    );
    const { user, queryClient } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 4개' });
    const panel = await openPanel(user);

    holdRead = true;
    await user.click(within(panel).getByRole('button', { name: '수락' }));
    expect(await screen.findByText('초대를 수락했어요')).toBeInTheDocument();
    await waitFor(() => expect(queryClient.isFetching()).toBe(0));
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    // 서버엔 아직 읽음이 안 닿았지만 화면의 읽음 표시는 그대로다
    expect(screen.getByRole('button', { name: '알림 · 안 읽음 3개' })).toBeInTheDocument();

    act(() => releaseRead?.());
    await waitFor(() => expect(queryClient.isFetching()).toBe(0));
    expect(screen.getByRole('button', { name: '알림 · 안 읽음 3개' })).toBeInTheDocument();
  });

  it('거절이 일시 장애로 실패하면 확인을 남겨 「거절하기」만 다시 누르게 한다 (PR #481 4차)', async () => {
    items.push(
      wire(
        'n-invite',
        1,
        'EDITOR_INVITED',
        { invitationId: 7 },
        { channelId: 9, channelName: '라면왕', important: true },
      ),
    );
    answerStatus = 503;
    const { user } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 4개' });
    const panel = await openPanel(user);

    await user.click(within(panel).getByRole('button', { name: '거절' }));
    await user.click(within(panel).getByRole('button', { name: '거절하기' }));
    await waitFor(() =>
      expect(within(panel).getByRole('button', { name: '거절하기' })).toBeEnabled(),
    );
    expect(
      within(panel).getByText('거절하면 라면왕 채널이 다시 초대해야 참여할 수 있어요'),
    ).toBeInTheDocument();
  });

  it('초대를 거절해도 읽음을 같이 보낸다 — 「처리됨」 줄이 안 읽음으로 남지 않는다', async () => {
    items.push(
      wire(
        'n-invite',
        1,
        'EDITOR_INVITED',
        { invitationId: 7 },
        { channelId: 9, channelName: '라면왕', important: true },
      ),
    );
    const { user, spy } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 4개' });
    const panel = await openPanel(user);

    await user.click(within(panel).getByRole('button', { name: '거절' }));
    await user.click(within(panel).getByRole('button', { name: '거절하기' }));

    await waitFor(() =>
      expect(posts(spy).sort()).toEqual([
        '/api/editor-invitations/7/decline',
        '/api/notifications/n-invite/read',
      ]),
    );
    expect(await screen.findByText('초대를 거절했어요')).toBeInTheDocument();
    expect(tracked('editor_invitation_responded')).toEqual([{ outcome: 'declined' }]);
    // 서버가 다시 준 목록에서도 그 알림은 읽음 · 처리됨이라 종 숫자에 안 센다
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '알림 · 안 읽음 3개' })).toBeInTheDocument(),
    );
  });

  it('열린 창은 접근성 위반이 없다', async () => {
    const { user } = renderBell();
    await screen.findByRole('button', { name: '알림 · 안 읽음 3개' });
    const panel = await openPanel(user);
    expect(await axe(panel)).toHaveNoViolations();
  });
});
