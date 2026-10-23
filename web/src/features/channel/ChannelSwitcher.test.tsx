import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '@/stores/auth';
import { useChannelStore } from '@/stores/channel';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';
import { ChannelSwitcher } from './ChannelSwitcher';
import { useSwitchCurtain } from './switchCurtain';
import { useActiveChannel } from './useActiveChannel';

const nav = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: nav.replace, push: vi.fn() }),
  usePathname: () => '/home',
}));

const ME = { id: 7, email: 'me@example.com', name: '나', profileImageUrl: null };
const BYEOLHA = {
  id: 21,
  counterpartId: 3,
  counterpartName: '별하',
  grantedAt: '2026-10-01T00:00:00Z',
};
const KONGSTAR = {
  id: 22,
  counterpartId: 4,
  counterpartName: '콩스타',
  grantedAt: '2026-10-02T00:00:00Z',
};
const GAMJA = {
  id: 23,
  counterpartId: 9,
  counterpartName: '감자대장',
  grantedAt: '2026-10-23T00:00:00Z',
};

function invitation(hoursLeft: number) {
  return {
    id: 11,
    streamerId: 9,
    streamerName: '감자대장',
    expiresAt: new Date(Date.now() + hoursLeft * 3_600_000 + 60_000).toISOString(),
    createdAt: '2026-10-22T00:00:00Z',
  };
}

interface Server {
  delegations: Array<{ id: number }>;
  invitations: unknown[];
  asEditorFails?: boolean;
  receivedFails?: boolean;
  /** 참여 채널 목록을 읽을 때마다 부른다(읽는 순서를 재는 시험용) */
  onAsEditor?: () => void;
  /** 거절·나가기 응답을 이 약속이 풀릴 때까지 붙잡는다(요청 중 상태를 재는 시험용) */
  hold?: Promise<void>;
}

function stub(server: Server) {
  return stubFetch((url, init) => {
    const method = init?.method ?? 'GET';
    if (url === '/api/auth/me') return jsonResponse(200, ME);
    const leave = /^\/api\/editor-delegations\/(\d+)$/.exec(url);
    if (leave && method === 'DELETE') {
      return (server.hold ?? Promise.resolve()).then(() => {
        server.delegations = server.delegations.filter((d) => d.id !== Number(leave[1]));
        return new Response(null, { status: 204 });
      });
    }
    if (url === '/api/editor-delegations/as-editor') {
      server.onAsEditor?.();
      return server.asEditorFails
        ? jsonResponse(500, { error: 'boom' })
        : jsonResponse(200, server.delegations);
    }
    if (url === '/api/editor-invitations/received') {
      return server.receivedFails
        ? jsonResponse(500, { error: 'boom' })
        : jsonResponse(200, server.invitations);
    }
    if (url === '/api/editor-invitations/11/accept' && method === 'POST') {
      server.invitations = [];
      server.delegations = [...server.delegations, GAMJA];
      return new Response(null, { status: 204 });
    }
    if (url === '/api/editor-invitations/11/decline' && method === 'POST') {
      return (server.hold ?? Promise.resolve()).then(() => {
        server.invitations = [];
        return new Response(null, { status: 204 });
      });
    }
    if (url === '/api/auth/logout') return new Response(null, { status: 204 });
    throw new Error(`unexpected fetch: ${method} ${url}`);
  });
}

const posts = (spy: ReturnType<typeof stubFetch>) =>
  spy.mock.calls.filter(([, init]) => init?.method === 'POST').map(([url]) => String(url));
const deletes = (spy: ReturnType<typeof stubFetch>) =>
  spy.mock.calls.filter(([, init]) => init?.method === 'DELETE').map(([url]) => String(url));

async function open(user: ReturnType<typeof userEvent.setup>) {
  return (await openWithClient(user)).panel;
}

async function openWithClient(user: ReturnType<typeof userEvent.setup>) {
  const { queryClient } = renderWithProviders(
    <ChannelSwitcher side="top" align="start">
      <button type="button">열기</button>
    </ChannelSwitcher>,
  );
  await user.click(screen.getByRole('button', { name: '열기' }));
  const panel = await screen.findByRole('dialog', { name: '계정 전환' });
  return { panel, queryClient };
}

beforeEach(() => {
  nav.replace.mockReset();
  window.localStorage.clear();
  useSwitchCurtain.getState().reset();
  useChannelStore.setState({ userId: null, selected: null });
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ChannelSwitcher: 펼친 패널', () => {
  it('내 채널 한 줄(지금 채널 표시) · 편집 참여 중 2 · 받은 초대 1 카드가 나온다', async () => {
    stub({ delegations: [BYEOLHA, KONGSTAR], invitations: [invitation(18)] });
    const user = userEvent.setup();
    const panel = within(await open(user));

    const mine = await panel.findByRole('button', { name: /나 채널/ });
    expect(mine).toHaveAttribute('aria-current', 'true');
    expect(within(mine).getByText('스트리머')).toBeInTheDocument();
    expect(await panel.findByText('편집 참여 중 · 2')).toBeInTheDocument();
    const byeolha = panel.getByRole('button', { name: /별하 채널/ });
    expect(byeolha).not.toHaveAttribute('aria-current');
    expect(within(byeolha).getByText('편집자')).toBeInTheDocument();
    expect(panel.getByRole('button', { name: /콩스타 채널/ })).toBeInTheDocument();

    const invite = within(await panel.findByRole('group', { name: '받은 초대 1' }));
    expect(invite.getByText('감자대장 채널의 편집자 초대')).toBeInTheDocument();
    expect(invite.getByText('18시간 후 만료')).toBeInTheDocument();
  });

  it('남의 채널에 있으면 그 줄이 지금 채널이다', async () => {
    window.localStorage.setItem('pc-channel', JSON.stringify({ '7': 3 }));
    stub({ delegations: [BYEOLHA], invitations: [] });
    const user = userEvent.setup();
    const panel = within(await open(user));

    await waitFor(() =>
      expect(panel.getByRole('button', { name: /별하 채널/ })).toHaveAttribute(
        'aria-current',
        'true',
      ),
    );
    expect(panel.getByRole('button', { name: /나 채널/ })).not.toHaveAttribute('aria-current');
    // 남의 채널 줄만 point로 칠할 표식을 단다: 내 채널(accent)과 색으로 갈린다
    expect(panel.getByRole('button', { name: /별하 채널/ })).toHaveAttribute('data-others-channel');
    expect(panel.getByRole('button', { name: /나 채널/ })).not.toHaveAttribute(
      'data-others-channel',
    );
  });

  it('별하를 누르면 전환 화면이 서고 패널이 닫히고, 다 덮은 뒤 그 채널로 바뀐다', async () => {
    stub({ delegations: [BYEOLHA], invitations: [] });
    const user = userEvent.setup();
    const panel = within(await open(user));

    await user.click(await panel.findByRole('button', { name: /별하 채널/ }));

    expect(useSwitchCurtain.getState().curtain).toMatchObject({ name: '별하', relation: 'EDITOR' });
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: '계정 전환' })).not.toBeInTheDocument(),
    );
    await waitFor(() =>
      expect(useChannelStore.getState()).toMatchObject({ userId: 7, selected: 3 }),
    );
  });

  it('초대를 수락하면 수락을 보내고 참여 채널을 다시 읽어 한 줄 늘어난다', async () => {
    const spy = stub({ delegations: [BYEOLHA, KONGSTAR], invitations: [invitation(18)] });
    const user = userEvent.setup();
    const panel = within(await open(user));

    await user.click(await panel.findByRole('button', { name: '수락' }));

    expect(posts(spy)).toEqual(['/api/editor-invitations/11/accept']);
    expect(await panel.findByText('편집 참여 중 · 3')).toBeInTheDocument();
    expect(panel.getByRole('button', { name: /감자대장 채널/ })).toBeInTheDocument();
    expect(panel.queryByRole('group', { name: /받은 초대/ })).not.toBeInTheDocument();
  });

  it('거절은 재확인 뒤에만 보낸다: 취소면 아무것도 안 보낸다', async () => {
    const spy = stub({ delegations: [], invitations: [invitation(18)] });
    const user = userEvent.setup();
    const panel = within(await open(user));

    const declineButton = await panel.findByRole('button', { name: '거절' });
    await user.click(declineButton);
    // 확인은 화면 가운데 모달이 아니라 창 안 덮개다(시안 1u ③). 실수로 Enter를 눌러도 거절되지 않게 취소에 포커스
    const confirm = within(
      await panel.findByRole('alertdialog', { name: '감자대장님의 초대를 거절할까요?' }),
    );
    expect(confirm.getByRole('button', { name: '취소' })).toHaveFocus();
    await user.click(confirm.getByRole('button', { name: '취소' }));
    expect(posts(spy)).toEqual([]);
    expect(panel.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(declineButton).toHaveFocus();

    await user.click(panel.getByRole('button', { name: '거절' }));
    const again = within(await panel.findByRole('alertdialog'));
    await user.click(again.getByRole('button', { name: '거절하기' }));

    await waitFor(() => expect(posts(spy)).toEqual(['/api/editor-invitations/11/decline']));
    await waitFor(() =>
      expect(panel.queryByRole('group', { name: /받은 초대/ })).not.toBeInTheDocument(),
    );
    expect(panel.queryByRole('alertdialog')).not.toBeInTheDocument();
  });

  it('거절 요청이 나가 있는 동안엔 Esc·바깥 클릭으로도 창이 닫히지 않는다: 덮개가 결과까지 남는다', async () => {
    let release: () => void = () => {};
    const spy = stub({
      delegations: [],
      invitations: [invitation(18)],
      hold: new Promise<void>((r) => {
        release = r;
      }),
    });
    const user = userEvent.setup();
    const panel = within(await open(user));

    await user.click(await panel.findByRole('button', { name: '거절' }));
    await user.click(
      within(await panel.findByRole('alertdialog')).getByRole('button', { name: '거절하기' }),
    );
    await waitFor(() => expect(posts(spy)).toEqual(['/api/editor-invitations/11/decline']));

    await user.keyboard('{Escape}');
    await user.click(document.body);
    expect(screen.getByRole('dialog', { name: '계정 전환' })).toBeInTheDocument();
    expect(panel.getByRole('alertdialog')).toBeInTheDocument();

    await act(async () => release());
    await waitFor(() => expect(panel.queryByRole('alertdialog')).not.toBeInTheDocument());
    // 끝난 뒤에는 다시 닫힌다
    await user.keyboard('{Escape}');
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: '계정 전환' })).not.toBeInTheDocument(),
    );
  });

  it('거절 확인에서 Esc는 덮개만 닫는다: 창은 그대로 열려 있다', async () => {
    const spy = stub({ delegations: [], invitations: [invitation(18)] });
    const user = userEvent.setup();
    const panel = within(await open(user));

    await user.click(await panel.findByRole('button', { name: '거절' }));
    await panel.findByRole('alertdialog');
    await user.keyboard('{Escape}');

    expect(panel.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: '계정 전환' })).toBeInTheDocument();
    expect(posts(spy)).toEqual([]);

    // 덮개가 없을 때의 Esc는 그대로 창을 닫는다
    await user.keyboard('{Escape}');
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: '계정 전환' })).not.toBeInTheDocument(),
    );
  });

  it('초대가 없으면 받은 초대 구역 자체가 없다', async () => {
    stub({ delegations: [BYEOLHA], invitations: [] });
    const user = userEvent.setup();
    const panel = within(await open(user));

    await panel.findByRole('button', { name: /별하 채널/ });
    expect(panel.queryByText(/받은 초대/)).not.toBeInTheDocument();
  });

  it('참여 채널을 못 읽으면 다시 시도를 보인다', async () => {
    const server: Server = { delegations: [BYEOLHA], invitations: [], asEditorFails: true };
    stub(server);
    const user = userEvent.setup();
    const panel = within(await open(user));

    const retry = await panel.findByRole('button', { name: '다시 시도' });
    server.asEditorFails = false;
    await user.click(retry);

    expect(await panel.findByRole('button', { name: /별하 채널/ })).toBeInTheDocument();
  });

  it('아래 줄: 이메일 · 계정 설정 링크 · 로그아웃', async () => {
    const spy = stub({ delegations: [], invitations: [] });
    const user = userEvent.setup();
    const panel = within(await open(user));

    expect(await panel.findByText('me@example.com')).toBeInTheDocument();
    expect(panel.getByRole('link', { name: '계정 설정' })).toHaveAttribute(
      'href',
      '/settings/account',
    );

    await user.click(panel.getByRole('button', { name: '로그아웃' }));

    expect(posts(spy)).toContain('/api/auth/logout');
    expect(nav.replace).toHaveBeenCalledWith('/login');
  });
});

describe('ChannelSwitcher: 한쪽 목록만 실패할 때(로컬 리뷰 1라운드 지적 1)', () => {
  it('받은 초대를 다시 읽다 실패해도 받아 둔 초대 카드는 남는다', async () => {
    const server: Server = { delegations: [BYEOLHA], invitations: [invitation(18)] };
    const spy = stub(server);
    const user = userEvent.setup();
    const { panel, queryClient } = await openWithClient(user);
    const p = within(panel);
    await p.findByRole('group', { name: '받은 초대 1' });
    const before = spy.mock.calls.filter(
      ([url]) => url === '/api/editor-invitations/received',
    ).length;

    server.receivedFails = true;
    await act(() => queryClient.invalidateQueries({ queryKey: ['editor-invitations'] }));

    await waitFor(() =>
      expect(
        spy.mock.calls.filter(([url]) => url === '/api/editor-invitations/received').length,
      ).toBe(before + 1),
    );
    await waitFor(() =>
      expect(queryClient.getQueryState(['editor-invitations', 'received'])?.status).toBe('error'),
    );
    expect(p.getByRole('group', { name: '받은 초대 1' })).toBeInTheDocument();
    expect(p.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument();
  });

  it('참여 채널만 처음부터 못 읽어도 받은 초대는 보인다', async () => {
    stub({ delegations: [], invitations: [invitation(18)], asEditorFails: true });
    const user = userEvent.setup();
    const p = within(await open(user));

    expect(await p.findByRole('button', { name: '다시 시도' })).toBeInTheDocument();
    expect(await p.findByRole('group', { name: '받은 초대 1' })).toBeInTheDocument();
  });
});

describe('ChannelSwitcher: 저장된 남의 채널이 있는데 내 채널로 보일 때(PR #323 codex 2차)', () => {
  it('as-editor를 못 읽어 내 채널로 보일 때 「내 채널」을 누르면 저장값을 지운다: 나중에 읽혀도 내 채널이다', async () => {
    window.localStorage.setItem('pc-channel', JSON.stringify({ '7': 3 }));
    const server: Server = { delegations: [BYEOLHA], invitations: [], asEditorFails: true };
    const spy = stub(server);
    const user = userEvent.setup();
    const { panel, queryClient } = await openWithClient(user);
    const mine = await within(panel).findByRole('button', { name: /나 채널/ });
    await within(panel).findByRole('button', { name: '다시 시도' });
    expect(mine).toHaveAttribute('aria-current', 'true');

    await user.click(mine);

    expect(JSON.parse(window.localStorage.getItem('pc-channel')!)).toEqual({});
    expect(useChannelStore.getState().selected).toBeNull();
    // 같은 채널로 보였으니 전환 화면은 없다
    expect(useSwitchCurtain.getState().curtain).toBeNull();

    server.asEditorFails = false;
    const before = spy.mock.calls.filter(
      ([url]) => url === '/api/editor-delegations/as-editor',
    ).length;
    // 패널이 닫혀 이 목록을 보는 쪽이 없다: 무효화로는 안 읽히니 비활성까지 다시 읽힌다
    await act(() => queryClient.refetchQueries({ queryKey: ['editor-delegations'], type: 'all' }));
    await waitFor(() =>
      expect(
        spy.mock.calls.filter(([url]) => url === '/api/editor-delegations/as-editor').length,
      ).toBe(before + 1),
    );
    expect(queryClient.getQueryState(['editor-delegations', 'as-editor'])?.status).toBe('success');
    expect(useChannelStore.getState().selected).toBeNull();
  });
});

describe('ChannelSwitcher: 관리 모드에서 참여 채널 나가기', () => {
  /** 관리 중의 참여 채널 줄(전환 버튼). 「○○ 채널에서 나가기」 버튼과 이름이 겹쳐 글자로 고른다 */
  const rowOf = (panel: ReturnType<typeof within>, name: string) =>
    panel
      .getAllByRole('button')
      .find(
        (b: HTMLElement) =>
          b.textContent?.includes(`${name} 채널`) && !b.textContent.includes('나가기'),
      )!;

  it('관리를 누르면 참여 채널마다 나가기가 나오고, 관리 중엔 줄을 눌러도 바꾸지 않는다', async () => {
    stub({ delegations: [BYEOLHA, KONGSTAR], invitations: [] });
    const user = userEvent.setup();
    const panel = within(await open(user));
    await panel.findByText('편집 참여 중 · 2');
    expect(panel.queryByRole('button', { name: /채널에서 나가기/ })).not.toBeInTheDocument();

    // 이름이 상태를 말한다: 눌림 상태를 따로 두지 않는다(리뷰 7)
    expect(panel.getByRole('button', { name: '관리' })).not.toHaveAttribute('aria-pressed');
    await user.click(panel.getByRole('button', { name: '관리' }));

    expect(panel.getByRole('button', { name: '완료' })).not.toHaveAttribute('aria-pressed');
    expect(panel.getByRole('button', { name: '별하 채널에서 나가기' })).toBeInTheDocument();
    expect(panel.getByRole('button', { name: '콩스타 채널에서 나가기' })).toBeInTheDocument();
    // 누를 수 없는 줄은 Tab에서 빠진다: 「완료」 다음은 곧장 「나가기」들이다(리뷰 8). jsdom은 배치가 없어 Tab 이동을
    // 직접 재면 FocusScope가 창으로 돌린다 — 줄이 Tab 순서에서 빠졌는지로 본다
    expect(rowOf(panel, '별하')).toHaveAttribute('tabindex', '-1');
    expect(rowOf(panel, '콩스타')).toHaveAttribute('tabindex', '-1');
    await user.click(rowOf(panel, '별하'));
    expect(useSwitchCurtain.getState().curtain).toBeNull();
    expect(screen.getByRole('dialog', { name: '계정 전환' })).toBeInTheDocument();

    await user.click(panel.getByRole('button', { name: '완료' }));
    expect(panel.queryByRole('button', { name: /채널에서 나가기/ })).not.toBeInTheDocument();
    expect(rowOf(panel, '별하')).not.toHaveAttribute('tabindex');
  });

  it('나가기는 창 안 덮개로 확인한 뒤에만 보낸다: 취소면 안 보내고, 나가면 그 줄만 사라지고 창은 그대로다', async () => {
    const spy = stub({ delegations: [BYEOLHA, KONGSTAR], invitations: [] });
    const user = userEvent.setup();
    const panel = within(await open(user));
    await panel.findByText('편집 참여 중 · 2');
    await user.click(panel.getByRole('button', { name: '관리' }));

    await user.click(panel.getByRole('button', { name: '콩스타 채널에서 나가기' }));
    const confirm = within(
      await panel.findByRole('alertdialog', { name: '콩스타 채널의 편집자에서 나갈까요?' }),
    );
    expect(confirm.getByRole('button', { name: '취소' })).toHaveFocus();
    await user.click(confirm.getByRole('button', { name: '취소' }));
    expect(deletes(spy)).toEqual([]);

    await user.click(panel.getByRole('button', { name: '콩스타 채널에서 나가기' }));
    await user.click(
      within(await panel.findByRole('alertdialog')).getByRole('button', { name: '나가기' }),
    );

    await waitFor(() => expect(deletes(spy)).toEqual(['/api/editor-delegations/22']));
    expect(await screen.findByText('콩스타 채널에서 나왔어요')).toBeInTheDocument();
    await waitFor(() =>
      expect(
        panel.queryByRole('button', { name: '콩스타 채널에서 나가기' }),
      ).not.toBeInTheDocument(),
    );
    // 지금 채널이 아니니 바꾸지 않고 창도 그대로다
    expect(useSwitchCurtain.getState().curtain).toBeNull();
    expect(screen.getByRole('dialog', { name: '계정 전환' })).toBeInTheDocument();
    // 누른 줄은 사라졌다: 포커스는 남은 「완료」에 둔다(리뷰 6)
    expect(panel.getByRole('button', { name: '완료' })).toHaveFocus();
  });

  it('마지막 참여 채널에서 나가면 「관리」도 사라지니 포커스를 「내 채널」 줄에 둔다(PR #342 리뷰 6)', async () => {
    stub({ delegations: [KONGSTAR], invitations: [] });
    const user = userEvent.setup();
    const panel = within(await open(user));
    await panel.findByText('편집 참여 중 · 1');
    await user.click(panel.getByRole('button', { name: '관리' }));
    await user.click(panel.getByRole('button', { name: '콩스타 채널에서 나가기' }));
    await user.click(
      within(await panel.findByRole('alertdialog')).getByRole('button', { name: '나가기' }),
    );

    await waitFor(() => expect(panel.queryByText(/편집 참여 중/)).not.toBeInTheDocument());
    expect(rowOf(panel, '나')).toHaveFocus();
  });

  it('마지막 참여 채널에서 나가면 관리 모드도 끈다: 초대를 수락해 구역이 다시 서도 관리 중이 아니다(PR #342 2차 리뷰)', async () => {
    stub({ delegations: [KONGSTAR], invitations: [invitation(18)] });
    const user = userEvent.setup();
    const panel = within(await open(user));
    await panel.findByText('편집 참여 중 · 1');
    await user.click(panel.getByRole('button', { name: '관리' }));
    await user.click(panel.getByRole('button', { name: '콩스타 채널에서 나가기' }));
    await user.click(
      within(await panel.findByRole('alertdialog')).getByRole('button', { name: '나가기' }),
    );
    await waitFor(() => expect(panel.queryByText(/편집 참여 중/)).not.toBeInTheDocument());

    await user.click(panel.getByRole('button', { name: '수락' }));

    expect(await panel.findByText('편집 참여 중 · 1')).toBeInTheDocument();
    expect(panel.getByRole('button', { name: '관리' })).toBeInTheDocument();
    expect(panel.queryByRole('button', { name: /채널에서 나가기/ })).not.toBeInTheDocument();
    expect(rowOf(panel, '감자대장')).not.toHaveAttribute('tabindex');
  });

  it('지금 들어가 있는 채널에서 나가면 창을 닫고 내 채널로 돌아간다: 참여 목록은 바꾼 뒤에 다시 읽는다', async () => {
    window.localStorage.setItem('pc-channel', JSON.stringify({ '7': 3 }));
    // 목록을 읽는 순간의 고른 채널을 적는다: 나간 뒤 읽기가 바꾸기보다 먼저면 ChannelScope가 끊김으로 보고 경고를 띄운다
    const readsAfterLeave: Array<number | null> = [];
    let left = false;
    const server: Server = { delegations: [BYEOLHA], invitations: [] };
    server.onAsEditor = () => {
      if (left) readsAfterLeave.push(useChannelStore.getState().selected);
    };
    const spy = stub(server);
    const user = userEvent.setup();
    // 실제 앱에선 사이드바·ChannelScope가 참여 목록을 늘 지켜봐서 창이 닫혀도 다시 읽힌다. 그 자리를 하나 세운다
    function Watcher() {
      useActiveChannel();
      return null;
    }
    renderWithProviders(
      <>
        <Watcher />
        <ChannelSwitcher side="top" align="start">
          <button type="button">열기</button>
        </ChannelSwitcher>
      </>,
    );
    await user.click(screen.getByRole('button', { name: '열기' }));
    const panel = within(await screen.findByRole('dialog', { name: '계정 전환' }));
    await waitFor(() => expect(rowOf(panel, '별하')).toHaveAttribute('aria-current', 'true'));
    await user.click(panel.getByRole('button', { name: '관리' }));
    await user.click(panel.getByRole('button', { name: '별하 채널에서 나가기' }));
    left = true;
    await user.click(
      within(await panel.findByRole('alertdialog')).getByRole('button', { name: '나가기' }),
    );

    await waitFor(() => expect(deletes(spy)).toEqual(['/api/editor-delegations/21']));
    // 스스로 나간 채널을 적어 둔다: 덮는 동안 그 채널 404가 와도 ChannelScope가 끊김 경고를 띄우지 않는다
    expect(useSwitchCurtain.getState().curtain).toMatchObject({
      name: '나',
      relation: 'OWNER',
      leftStreamerId: 3,
    });
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: '계정 전환' })).not.toBeInTheDocument(),
    );
    await waitFor(() => expect(useChannelStore.getState().selected).toBeNull());
    await waitFor(() => expect(readsAfterLeave.length).toBeGreaterThan(0));
    expect(readsAfterLeave.every((selected) => selected === null)).toBe(true);
  });
});
