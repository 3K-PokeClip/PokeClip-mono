import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Side } from '@/components/app-shell/Side';
import { useSidebarStore } from '@/stores/sidebar';
import { renderWithProviders } from '@/test/testProviders';

const nav = vi.hoisted(() => ({ pathname: '/settings/plugin' }));

vi.mock('next/navigation', () => ({
  usePathname: () => nav.pathname,
}));

// 접힘은 모듈 전역 스토어다 — 테스트 사이에 새지 않게 되돌린다
beforeEach(() => {
  useSidebarStore.setState({ collapsed: false });
});

describe('Side — 설정', () => {
  it('설정 메뉴 7개 중 채널 연동·편집자 관리·플러그인·알림 설정·계정만 링크이고 현재 경로를 활성으로 표시한다', () => {
    nav.pathname = '/settings/plugin';
    renderWithProviders(<Side menu="settings" />);

    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(5);
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

    // 나머지 2개는 비활성 (하위 티켓에서 라우트가 생기면 링크로 전환)
    for (const label of ['구독 · 결제', '도움말 · 문의']) {
      expect(screen.getByText(label).closest('[aria-disabled="true"]')).not.toBeNull();
    }
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
