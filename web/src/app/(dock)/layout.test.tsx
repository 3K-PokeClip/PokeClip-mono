import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '@/stores/auth';
import { useChannelStore } from '@/stores/channel';
import { useSidebarStore } from '@/stores/sidebar';
import { useSwitchCurtain } from '@/features/channel/switchCurtain';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';
import DockLayout from './layout';
import BroadcastLayout from './broadcast/layout';
import HomePage from './home/page';
import SettingsLayout from './settings/layout';

// 새로고침 직후 빈 화면을 막는다(POK-442): 채널 범위(ChannelScope)는 독 레이아웃이 아니라 화면 그룹이 본문만 감싼다.
// me가 오기 전에도 사이드바·헤더·독은 서고, 본문만 뼈대로 기다린다. 범위를 다시 독 레이아웃으로 올리면 여기서 깨진다.

// AuthGuard는 세션 스토어·useMe를 타므로 여기선 통과만 시킨다((fullscreen)/layout.test.tsx와 같다)
vi.mock('@/components/app-shell/AuthGuard', () => ({
  AuthGuard: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

const nav = vi.hoisted(() => ({ pathname: '/settings/notifications', replace: vi.fn() }));
vi.mock('next/navigation', () => ({
  usePathname: () => nav.pathname,
  useRouter: () => ({ replace: nav.replace, push: vi.fn() }),
  useSearchParams: () => new URLSearchParams(''),
}));

const ME_URL = '/api/auth/me';
const AS_EDITOR_URL = '/api/editor-delegations/as-editor';
const STORAGE_KEY = 'pc-channel';
const ME = { id: 7, email: 'me@example.com', name: '나', profileImageUrl: null };
const BYEOLHA = {
  id: 21,
  counterpartId: 3,
  counterpartName: '별하',
  grantedAt: '2026-10-01T00:00:00Z',
};

/** me 응답을 붙잡아 둔다: 새로고침 직후 me를 기다리는 동안을 재현한다. release로 놓는다 */
function holdMe() {
  let release: () => void = () => {};
  const held = new Promise<void>((r) => (release = r));
  stubFetch((url) => {
    if (url === ME_URL) return held.then(() => jsonResponse(200, ME));
    if (url === AS_EDITOR_URL) return jsonResponse(200, [BYEOLHA]);
    if (url === '/api/editor-invitations/received') return jsonResponse(200, []);
    // 본문 자리엔 화면 대신 글 한 줄만 둔다: 화면 창구는 이 시험의 몫이 아니다
    throw new Error(`unexpected fetch: ${url}`);
  });
  return () => act(() => release());
}

function renderDock(children: ReactNode) {
  return renderWithProviders(<DockLayout>{children}</DockLayout>);
}

beforeEach(() => {
  nav.pathname = '/settings/notifications';
  nav.replace.mockReset();
  window.localStorage.clear();
  window.history.pushState({}, '', '/');
  useSidebarStore.setState({ collapsed: false });
  useChannelStore.setState({ userId: null, selected: null });
  useSwitchCurtain.getState().reset();
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('(dock) 레이아웃: me를 기다리는 동안', () => {
  it('설정: 사이드바와 독은 바로 서고 본문은 「화면을 불러오는 중」 status 하나로 기다린다', async () => {
    const release = holdMe();
    const { container } = renderDock(
      <SettingsLayout>
        <p>설정 본문</p>
      </SettingsLayout>,
    );

    expect(screen.getByRole('navigation', { name: '채널 · 협업자' })).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: '주요 탐색' })).toBeInTheDocument();
    const main = screen.getByRole('main');
    expect(within(main).getAllByRole('status')).toHaveLength(1);
    expect(within(main).getByRole('status')).toHaveTextContent('화면을 불러오는 중');
    expect(screen.queryByText('설정 본문')).not.toBeInTheDocument();
    // 설정은 레이아웃의 <main>이 패딩을 준다: 뼈대가 <main>을 하나 더 세우지 않는다
    expect(screen.getAllByRole('main')).toHaveLength(1);
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });

    release();
    expect(await screen.findByText('설정 본문')).toBeInTheDocument();
    expect(within(screen.getByRole('main')).queryByRole('status')).not.toBeInTheDocument();
  });

  it('방송: 사이드바는 서고 본문 뼈대가 화면 대신 <main>을 세운다', async () => {
    nav.pathname = '/broadcast/vod';
    const release = holdMe();
    renderDock(
      <BroadcastLayout>
        <p>방송 본문</p>
      </BroadcastLayout>,
    );

    expect(screen.getByRole('navigation', { name: '방송' })).toBeInTheDocument();
    // 경로로 화면을 아니 그 화면의 첫 로딩 뼈대를 그대로 그린다: 채널이 선 뒤 뼈대가 한 번 더 바뀌지 않는다(PR #377 2차 리뷰)
    expect(within(screen.getByRole('main')).getByRole('status')).toHaveTextContent(
      '지난 방송을 불러오는 중',
    );
    expect(screen.getByRole('heading', { level: 1, name: '지난 방송' })).toBeInTheDocument();
    expect(screen.queryByText('방송 본문')).not.toBeInTheDocument();

    release();
    expect(await screen.findByText('방송 본문')).toBeInTheDocument();
    expect(screen.queryByRole('main')).not.toBeInTheDocument();
  });

  it('홈: 헤더는 바로 서고 본문은 큰 칸 뼈대로 기다린다', () => {
    nav.pathname = '/home';
    holdMe();
    renderDock(<HomePage />);

    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '계정 메뉴' })).toBeInTheDocument();
    expect(screen.getByRole('main').querySelector('[aria-busy="true"]')).not.toBeNull();
  });
});

describe('(dock) 레이아웃: 채널이 바뀔 때', () => {
  it('사이드바는 다시 서지 않고, 열린 계정 전환 창은 닫힌다(다른 탭에서 고름)', async () => {
    const user = userEvent.setup();
    const release = holdMe();
    release();
    renderDock(
      <SettingsLayout>
        <p>설정 본문</p>
      </SettingsLayout>,
    );
    await screen.findByText('설정 본문');
    const aside = screen.getByRole('complementary');

    await user.click(screen.getByRole('button', { name: /^계정 전환/ }));
    expect(await screen.findByRole('dialog', { name: '계정 전환' })).toBeInTheDocument();

    // 다른 탭이 별하 채널을 골랐다
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ '7': 3 }));
    act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key: STORAGE_KEY }));
    });

    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: '계정 전환' })).not.toBeInTheDocument(),
    );
    expect(await screen.findByText('별하 채널')).toBeInTheDocument();
    expect(screen.getByRole('complementary')).toBe(aside);
  });

  it('me 전에 연 계정 전환 창은 me가 와서 저장된 채널을 처음 읽어도 닫히지 않는다', async () => {
    // 사이드바가 me보다 먼저 서므로 그 전에 열 수 있다. 처음 읽힘(null → 저장값 3)은 채널을 바꾼 것이 아니다
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ '7': 3 }));
    const user = userEvent.setup();
    const release = holdMe();
    renderDock(
      <SettingsLayout>
        <p>설정 본문</p>
      </SettingsLayout>,
    );

    await user.click(screen.getByRole('button', { name: /^계정 전환/ }));
    const dialog = await screen.findByRole('dialog', { name: '계정 전환' });
    release();

    expect(await screen.findByText('설정 본문')).toBeInTheDocument();
    expect(useChannelStore.getState().selected).toBe(3);
    expect(dialog).toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: '계정 전환' })).toBe(dialog);
  });

  it('지난 방송 상세에서 바꾸면 사이드바는 둔 채 홈으로 보낸다: 가는 동안 본문은 뼈대다', async () => {
    nav.pathname = '/broadcast/vod/s-1';
    const release = holdMe();
    release();
    renderDock(
      <BroadcastLayout>
        <p>방송 상세</p>
      </BroadcastLayout>,
    );
    await screen.findByText('방송 상세');
    expect(nav.replace).not.toHaveBeenCalled();

    act(() => useChannelStore.getState().select(7, 3));

    await waitFor(() => expect(nav.replace).toHaveBeenCalledWith('/home'));
    expect(screen.queryByText('방송 상세')).not.toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: '방송' })).toBeInTheDocument();
    // 지난 방송 상세는 라이브 대시보드와 같은 화면이라 그 뼈대다
    expect(within(screen.getByRole('main')).getByRole('status')).toHaveTextContent(
      '라이브 대시보드를 불러오는 중',
    );
  });
});
