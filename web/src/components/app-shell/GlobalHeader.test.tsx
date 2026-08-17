import { fireEvent, screen, waitFor } from '@testing-library/react';
import { axe } from 'jest-axe';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GlobalHeader } from '@/components/app-shell/GlobalHeader';
import { useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';

const { replace } = vi.hoisted(() => ({ replace: vi.fn() }));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace }),
}));

const ME = { id: 1, email: 'raccoon@example.com', name: '게임하는너구리', profileImageUrl: '' };

function stubAuthedFetch() {
  return stubFetch((url) => {
    if (url === '/api/auth/me') return jsonResponse(200, ME);
    if (url === '/api/auth/logout') return new Response(null, { status: 204 });
    return jsonResponse(404);
  });
}

beforeEach(() => {
  window.localStorage.clear();
  replace.mockReset();
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('GlobalHeader', () => {
  it('워드마크·알림 버튼·계정 메뉴를 렌더하고 me의 이름 이니셜을 채운다', async () => {
    stubAuthedFetch();
    renderWithProviders(<GlobalHeader />);

    expect(screen.getByText('PokeClip')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '알림' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '계정 메뉴' })).toBeInTheDocument();
    // Avatar는 name 이니셜 폴백 — profileImageUrl이 비어 있을 때의 표시
    expect(await screen.findByText('게임')).toBeInTheDocument();
  });

  it('로그아웃은 서버 폐기를 부르고 세션을 접은 뒤 /login으로 보낸다', async () => {
    const spy = stubAuthedFetch();
    renderWithProviders(<GlobalHeader />);

    fireEvent.click(screen.getByRole('button', { name: '계정 메뉴' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: '로그아웃' }));

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/login'));
    expect(spy.mock.calls.some(([url]) => url === '/api/auth/logout')).toBe(true);
    expect(useAuthStore.getState().refreshToken).toBeNull();
    expect(window.localStorage.getItem('pc-auth')).toBeNull();
  });

  it('접근성 위반이 없다', async () => {
    stubAuthedFetch();
    const { container } = renderWithProviders(<GlobalHeader />);
    await screen.findByText('게임'); // me 쿼리 반영을 먼저 기다린다 — act 경고 없이 검사
    expect(await axe(container)).toHaveNoViolations();
  });
});
