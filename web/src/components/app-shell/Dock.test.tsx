import { screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Dock } from '@/components/app-shell/Dock';
import styles from '@/components/app-shell/Dock.module.css';
import { useAuthStore } from '@/stores/auth';
import { useChannelStore } from '@/stores/channel';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';

vi.mock('next/navigation', () => ({
  usePathname: () => '/broadcast/livenow',
}));

describe('Dock', () => {
  it('독 4개 링크를 렌더하고 현재 경로를 활성으로 표시한다', () => {
    renderWithProviders(<Dock />);

    const links = screen.getAllByRole('link');
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      '/home',
      '/broadcast',
      '/clips',
      '/settings',
    ]);

    expect(screen.getByRole('link', { name: /방송/ })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: /홈/ })).not.toHaveAttribute('aria-current');
  });

  it('활성 항목에만 알약을 렌더한다', () => {
    // 알약이 둘이면 브라우저가 옛/새 한 쌍으로 보지 못해 이동 대신 각자 페이드된다
    const { container } = renderWithProviders(<Dock />);

    expect(container.querySelectorAll(`.${styles.pill}`)).toHaveLength(1);
    expect(
      screen.getByRole('link', { name: /방송/ }).querySelector(`.${styles.pill}`),
    ).not.toBeNull();
  });
});

describe('Dock: 관계는 해석된 값으로(로컬 리뷰 1라운드 지적 2)', () => {
  const ME = { id: 7, email: 'me@example.com', name: '나', profileImageUrl: null };
  const BYEOLHA = {
    id: 21,
    counterpartId: 3,
    counterpartName: '별하',
    grantedAt: '2026-10-01T00:00:00Z',
  };

  beforeEach(() => {
    window.localStorage.clear();
    useChannelStore.setState({ userId: null, selected: null });
    useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function serve(asEditor: () => Response) {
    stubFetch((url) => {
      if (url === '/api/auth/me') return jsonResponse(200, ME);
      if (url === '/api/editor-delegations/as-editor') return asEditor();
      return jsonResponse(404);
    });
  }

  it('저장값 3이 as-editor에 있으면 설정 탭은 알림 설정이다', async () => {
    window.localStorage.setItem('pc-channel', JSON.stringify({ '7': 3 }));
    serve(() => jsonResponse(200, [BYEOLHA]));
    renderWithProviders(<Dock />);

    await waitFor(() =>
      expect(screen.getByRole('link', { name: /설정/ })).toHaveAttribute(
        'href',
        '/settings/notifications',
      ),
    );
  });

  it('저장값 3이 있어도 as-editor를 처음부터 못 읽으면 내 채널로 보이므로 설정 탭은 /settings다', async () => {
    // 저장소에는 남의 번호가 그대로 있다(못 읽었을 때 저장값을 지우지 않는다): 원시값으로 가르면 알림 설정으로 간다
    window.localStorage.setItem('pc-channel', JSON.stringify({ '7': 3 }));
    useChannelStore.setState({ userId: 7, selected: 3 });
    const spy = stubFetch((url) => {
      if (url === '/api/auth/me') return jsonResponse(200, ME);
      if (url === '/api/editor-delegations/as-editor') return jsonResponse(500, { error: 'boom' });
      return jsonResponse(404);
    });
    renderWithProviders(<Dock />);

    await new Promise((r) => setTimeout(r, 100));
    expect(screen.getByRole('link', { name: /설정/ })).toHaveAttribute('href', '/settings');
    expect(spy.mock.calls.some(([url]) => url === '/api/editor-delegations/as-editor')).toBe(true);
    expect(useChannelStore.getState().selected).toBe(3);
  });
});

describe('Dock: 채널이 아직 안 섰을 때(PR #323 codex 2차)', () => {
  const ME = { id: 7, email: 'me@example.com', name: '나', profileImageUrl: null };

  beforeEach(() => {
    window.localStorage.clear();
    useChannelStore.setState({ userId: null, selected: null });
    useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('as-editor를 기다리는 동안 저장된 남의 채널이 있으면 설정 탭은 알림 설정이다: /settings면 막힌 플러그인으로 간다', async () => {
    window.localStorage.setItem('pc-channel', JSON.stringify({ '7': 3 }));
    stubFetch((url) => {
      if (url === '/api/auth/me') return jsonResponse(200, ME);
      if (url === '/api/editor-delegations/as-editor') return new Promise<Response>(() => {});
      return jsonResponse(404);
    });
    renderWithProviders(<Dock />);

    await waitFor(() =>
      expect(useChannelStore.getState()).toMatchObject({ userId: 7, selected: 3 }),
    );
    expect(screen.getByRole('link', { name: /설정/ })).toHaveAttribute(
      'href',
      '/settings/notifications',
    );
  });
});
