import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Side } from '@/components/app-shell/Side';
import { useAuthStore } from '@/stores/auth';
import { useChannelStore } from '@/stores/channel';
import { useSidebarStore } from '@/stores/sidebar';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';

const nav = vi.hoisted(() => ({ pathname: '/settings/plugin' }));

vi.mock('next/navigation', () => ({
  usePathname: () => nav.pathname,
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}));

// 접힘은 모듈 전역 스토어다 — 테스트 사이에 새지 않게 되돌린다
beforeEach(() => {
  useSidebarStore.setState({ collapsed: false });
});

/** 메뉴 항목 링크만 — 맨 위 로고의 홈 링크(POK-611)는 메뉴가 아니라 뺀다 */
function menuLinks() {
  return screen.getAllByRole('navigation').flatMap((nav) => within(nav).queryAllByRole('link'));
}

describe('Side — 설정', () => {
  it('설정 메뉴 7개 중 구독 · 결제만 빼고 링크이고 현재 경로를 활성으로 표시한다', () => {
    nav.pathname = '/settings/plugin';
    renderWithProviders(<Side menu="settings" />);

    const links = menuLinks();
    expect(links).toHaveLength(6);
    expect(links[0]).toHaveAttribute('href', '/settings/channels');
    expect(links[0]).not.toHaveAttribute('aria-current');
    expect(links[1]).toHaveAttribute('href', '/settings/editors');
    expect(links[1]).not.toHaveAttribute('aria-current');
    expect(links[2]).toHaveAttribute('href', '/settings/plugin');
    expect(links[2]).toHaveAttribute('aria-current', 'page');
    expect(links[3]).toHaveAttribute('href', '/settings/notifications');
    expect(links[3]).not.toHaveAttribute('aria-current');
    expect(links[4]).toHaveAttribute('href', '/settings/account');
    expect(links[4]).not.toHaveAttribute('aria-current');
    expect(links[5]).toHaveAttribute('href', '/settings/help');
    expect(links[5]).not.toHaveAttribute('aria-current');

    // 구독 · 결제는 비활성 (라우트가 생기면 링크로 전환)
    expect(screen.getByText('구독 · 결제').closest('[aria-disabled="true"]')).not.toBeNull();
  });

  it('알림 설정 화면에서 알림 설정이 활성이다', () => {
    nav.pathname = '/settings/notifications';
    renderWithProviders(<Side menu="settings" />);

    expect(screen.getByRole('link', { name: /알림 설정/ })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: /플러그인/ })).not.toHaveAttribute('aria-current');
  });

  it('토글 버튼이 접힘 상태를 전환한다', async () => {
    nav.pathname = '/settings/plugin';
    const user = userEvent.setup();
    renderWithProviders(<Side menu="settings" />);

    const toggle = screen.getByRole('button', { name: '사이드바 접기' });
    expect(toggle).toHaveAttribute('aria-expanded', 'true');

    await user.click(toggle);
    expect(screen.getByRole('button', { name: '사이드바 펼치기' })).toHaveAttribute(
      'aria-expanded',
      'false',
    );
  });
});

describe('Side: 로고는 홈으로 가는 링크 (POK-611)', () => {
  const homeLink = () => screen.getByRole('link', { name: 'PokeClip 홈으로' });

  it('펼친 사이드바에서는 심볼과 워드마크가 한 링크로 홈에 간다', () => {
    nav.pathname = '/settings/plugin';
    renderWithProviders(<Side menu="settings" />);

    const link = homeLink();
    expect(link).toHaveAttribute('href', '/home');
    // 심볼(그림)과 「PokeClip」 글자가 같은 링크 안이다 — 둘 중 어디를 눌러도 간다
    expect(link.querySelector('svg')).not.toBeNull();
    expect(within(link).getByText('PokeClip')).toBeInTheDocument();
    // 탭으로 닿고, 눌렀을 때 이동을 막지 않는다
    expect(link).not.toHaveAttribute('tabindex');
    expect(link).not.toHaveAttribute('aria-disabled');
    expect(fireEvent.click(link)).toBe(true);
  });

  it('접힌 사이드바에서는 링크가 꺼진다 — 그 자리는 펼치기 버튼이다', async () => {
    nav.pathname = '/settings/plugin';
    const user = userEvent.setup();
    renderWithProviders(<Side menu="settings" />);
    const expanded = homeLink();

    await user.click(screen.getByRole('button', { name: '사이드바 접기' }));

    const link = homeLink();
    // 요소는 그대로다 — 갈아 끼우면 심볼·워드마크가 다시 붙어 접히는 움직임이 끊긴다
    expect(link).toBe(expanded);
    // 탭 순서에서 빠지고(초점이 가면 로고가 햄버거로 바뀐다), 눌러도 가지 않는다
    expect(link).toHaveAttribute('tabindex', '-1');
    expect(link).toHaveAttribute('aria-disabled', 'true');
    expect(fireEvent.click(link)).toBe(false);

    // 다시 펼치면 돌아온다
    await user.click(screen.getByRole('button', { name: '사이드바 펼치기' }));
    expect(homeLink()).not.toHaveAttribute('tabindex');
    expect(fireEvent.click(homeLink())).toBe(true);
  });

  it('방송·클립 사이드바에도 있다 — 메뉴와 상관없이 같은 껍데기다', () => {
    nav.pathname = '/broadcast/livenow';
    const { unmount } = renderWithProviders(<Side menu="broadcast" />);
    expect(homeLink()).toHaveAttribute('href', '/home');
    unmount();

    nav.pathname = '/clips/library';
    renderWithProviders(<Side menu="clips" />);
    expect(homeLink()).toHaveAttribute('href', '/home');
  });
});

describe('Side — 방송', () => {
  it('두 화면 모두 링크이고 지금 보는 쪽만 활성이다', () => {
    nav.pathname = '/broadcast/livenow';
    renderWithProviders(<Side menu="broadcast" />);

    const live = screen.getByRole('link', { name: /라이브 대시보드/ });
    expect(live).toHaveAttribute('href', '/broadcast/livenow');
    expect(live).toHaveAttribute('aria-current', 'page');

    // 지난 방송 목록(POK-226)이 생겨 비활성 자리가 링크가 됐다
    const vod = screen.getByRole('link', { name: /지난 방송/ });
    expect(vod).toHaveAttribute('href', '/broadcast/vod');
    expect(vod).not.toHaveAttribute('aria-current');
  });

  it('접힘이 그룹을 넘어 유지된다 — 설정에서 접으면 방송에서도 접혀 있다', async () => {
    // 두 그룹이 각자 자기 레이아웃에서 Side를 마운트한다. 접힘이 컴포넌트 지역 상태면
    // 탭을 옮길 때마다 접어둔 사이드바가 혼자 펼쳐진다.
    const user = userEvent.setup();
    nav.pathname = '/settings/plugin';
    const settings = renderWithProviders(<Side menu="settings" />);
    await user.click(screen.getByRole('button', { name: '사이드바 접기' }));
    settings.unmount();

    nav.pathname = '/broadcast/livenow';
    renderWithProviders(<Side menu="broadcast" />);
    expect(screen.getByRole('button', { name: '사이드바 펼치기' })).toHaveAttribute(
      'aria-expanded',
      'false',
    );
  });

  it('그룹 안 다른 화면에 있으면 라이브 대시보드가 활성이 아니다', () => {
    // 그룹 루트가 화면을 겸했다면 하위 경로 매칭으로 여기서도 활성이 됐을 자리다
    nav.pathname = '/broadcast/vod';
    renderWithProviders(<Side menu="broadcast" />);

    expect(screen.getByRole('link', { name: /라이브 대시보드/ })).not.toHaveAttribute(
      'aria-current',
    );
  });
});

describe('Side — 클립', () => {
  it('보관함만 링크이고 /clips/library에서 활성이다 — 템플릿 관리·업로드 상태는 비활성, 승인 대기함은 없다(POK-254)', () => {
    nav.pathname = '/clips/library';
    renderWithProviders(<Side menu="clips" />);

    const links = menuLinks();
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAttribute('href', '/clips/library');
    expect(links[0]).toHaveAttribute('aria-current', 'page');

    // 나머지 2개는 비활성 — 템플릿 관리(1h)·업로드 상태(1i)
    for (const label of ['템플릿 관리', '업로드 상태']) {
      expect(screen.getByText(label).closest('[aria-disabled="true"]')).not.toBeNull();
    }
    // 승인 게이트가 없어 승인 대기함은 메뉴에서 뺐다(POK-236 폐기)
    expect(screen.queryByText('승인 대기함')).toBeNull();
  });

  it('두 그룹을 이름 붙은 nav로 낸다 — 하이라이트 · 클립 / 업로드', () => {
    nav.pathname = '/clips/library';
    renderWithProviders(<Side menu="clips" />);

    expect(screen.getByRole('navigation', { name: '하이라이트 · 클립' })).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: '업로드' })).toBeInTheDocument();
  });

  it('접힘이 클립 그룹에도 이어진다 — 방송에서 접으면 클립에서도 접혀 있다', async () => {
    const user = userEvent.setup();
    nav.pathname = '/broadcast/livenow';
    const broadcast = renderWithProviders(<Side menu="broadcast" />);
    await user.click(screen.getByRole('button', { name: '사이드바 접기' }));
    broadcast.unmount();

    nav.pathname = '/clips/library';
    renderWithProviders(<Side menu="clips" />);
    expect(screen.getByRole('button', { name: '사이드바 펼치기' })).toHaveAttribute(
      'aria-expanded',
      'false',
    );
  });

  it('badges로 준 수가 항목 옆에 붙는다', () => {
    nav.pathname = '/clips/library';
    renderWithProviders(<Side menu="clips" badges={{ upstatus: 3 }} />);

    const upstatus = screen.getByText('업로드 상태').closest('[aria-disabled="true"]');
    expect(upstatus).not.toBeNull();
    expect(upstatus).toHaveTextContent('업로드 상태3');
    // 값을 안 준 항목에는 배지가 없다
    expect(screen.getByText('보관함').closest('a')).toHaveTextContent(/^보관함$/);
  });
});

describe('Side: 채널 스위처 트리거(POK-390)', () => {
  const ME = { id: 7, email: 'me@example.com', name: '나', profileImageUrl: null };
  const BYEOLHA = {
    id: 21,
    counterpartId: 3,
    counterpartName: '별하',
    grantedAt: '2026-10-01T00:00:00Z',
  };

  function stubChannel() {
    return stubFetch((url) => {
      if (url === '/api/auth/me') return jsonResponse(200, ME);
      if (url === '/api/editor-delegations/as-editor') return jsonResponse(200, [BYEOLHA]);
      if (url === '/api/editor-invitations/received') return jsonResponse(200, []);
      return jsonResponse(404);
    });
  }

  beforeEach(() => {
    window.localStorage.clear();
    useChannelStore.setState({ userId: null, selected: null });
    useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('프로필 자리에 지금 채널 이름과 「스트리머」 글자가 나오고 누르면 스위처가 열린다', async () => {
    nav.pathname = '/clips/library';
    stubChannel();
    const user = userEvent.setup();
    renderWithProviders(<Side menu="clips" />);

    const trigger = await screen.findByRole('button', { name: /계정 전환/ });
    expect(await screen.findByText('나 채널')).toBeInTheDocument();
    expect(within(trigger).getByText('스트리머')).toBeInTheDocument();
    expect(trigger).toHaveAccessibleName('계정 전환 · 나 채널 · 스트리머');

    await user.click(trigger);
    expect(await screen.findByRole('dialog', { name: '계정 전환' })).toBeInTheDocument();
  });

  it('알림 버튼은 트리거 밖이다: 버튼 안 버튼을 만들지 않는다', async () => {
    nav.pathname = '/clips/library';
    stubChannel();
    renderWithProviders(<Side menu="clips" />);

    const trigger = await screen.findByRole('button', { name: /계정 전환/ });
    expect(trigger).not.toContainElement(screen.getByRole('button', { name: '알림' }));
  });

  it('남의 채널이면 그 채널 이름과 「편집자」 글자다', async () => {
    window.localStorage.setItem('pc-channel', JSON.stringify({ '7': 3 }));
    nav.pathname = '/clips/library';
    stubChannel();
    renderWithProviders(<Side menu="clips" />);

    expect(await screen.findByText('별하 채널')).toBeInTheDocument();
    const trigger = screen.getByRole('button', { name: '계정 전환 · 별하 채널 · 편집자' });
    expect(within(trigger).getByText('편집자')).toBeInTheDocument();
  });
});

describe('Side: 남의 채널에서 막는 메뉴(POK-390)', () => {
  const ME = { id: 7, email: 'me@example.com', name: '나', profileImageUrl: null };
  const BYEOLHA = {
    id: 21,
    counterpartId: 3,
    counterpartName: '별하',
    grantedAt: '2026-10-01T00:00:00Z',
  };
  const OWNER_ONLY = ['채널 연동', '편집자 관리', '플러그인'];
  const PERSONAL = ['알림 설정', '계정'];

  beforeEach(() => {
    window.localStorage.clear();
    useChannelStore.setState({ userId: null, selected: null });
    useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
    stubFetch((url) => {
      if (url === '/api/auth/me') return jsonResponse(200, ME);
      if (url === '/api/editor-delegations/as-editor') return jsonResponse(200, [BYEOLHA]);
      return jsonResponse(404);
    });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('편집자 채널이면 채널 연동·편집자 관리·플러그인이 비활성이고 링크가 아니다: 알림 설정·계정은 링크', async () => {
    window.localStorage.setItem('pc-channel', JSON.stringify({ '7': 3 }));
    nav.pathname = '/settings/notifications';
    renderWithProviders(<Side menu="settings" />);
    await screen.findByText('별하 채널');

    for (const label of OWNER_ONLY) {
      expect(screen.queryByRole('link', { name: new RegExp(label) })).not.toBeInTheDocument();
      expect(screen.getByText(label).closest('[aria-disabled="true"]')).not.toBeNull();
    }
    for (const label of PERSONAL) {
      expect(screen.getByRole('link', { name: new RegExp(label) })).toBeInTheDocument();
    }
  });

  it('내 채널이면 그 셋도 링크다', async () => {
    nav.pathname = '/settings/notifications';
    renderWithProviders(<Side menu="settings" />);
    await screen.findByText('나 채널');

    for (const label of [...OWNER_ONLY, ...PERSONAL]) {
      expect(screen.getByRole('link', { name: new RegExp(label) })).toBeInTheDocument();
    }
  });
});
