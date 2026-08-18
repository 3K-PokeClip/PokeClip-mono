import { act } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { axe } from 'jest-axe';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LoginScreen } from '@/features/auth/LoginScreen';
import { useAuthStore } from '@/stores/auth';

const { replace, startGoogleLogin } = vi.hoisted(() => ({
  replace: vi.fn(),
  startGoogleLogin: vi.fn(),
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace }),
}));

// location.assign은 jsdom이 못 흉내 낸다 — OAuth 진입은 모킹하고 URL 조립은
// googleOAuth.test.ts가 순수 함수로 검증한다.
vi.mock('@/features/auth/googleOAuth', () => ({ startGoogleLogin }));

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  replace.mockReset();
  startGoogleLogin.mockReset();
  useAuthStore.setState({ accessToken: null, refreshToken: null, hydrated: false });
});

describe('LoginScreen', () => {
  it('구글 버튼이 가려던 경로를 실어 OAuth 진입을 시작한다', () => {
    // AuthGuard가 차단하며 남긴 복원 경로
    window.sessionStorage.setItem('pc-auth-return', '/settings/plugin');
    render(<LoginScreen />);

    expect(screen.getByRole('heading', { name: '클립 제작, 바로 시작하세요' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Google로 시작하기/ }));

    expect(startGoogleLogin).toHaveBeenCalledWith('/settings/plugin');
    // 복원 경로는 1회용 — 다음 로그인에 낡은 경로가 따라붙으면 안 된다
    expect(window.sessionStorage.getItem('pc-auth-return')).toBeNull();
  });

  it('복원 경로가 없으면 기본 목적지(undefined)로 진입한다', () => {
    render(<LoginScreen />);

    fireEvent.click(screen.getByRole('button', { name: /Google로 시작하기/ }));

    expect(startGoogleLogin).toHaveBeenCalledWith(undefined);
  });

  it('OAuth 진입이 실패하면 오류를 표면화하고 복원 경로를 되돌린다', () => {
    window.sessionStorage.setItem('pc-auth-return', '/settings/plugin');
    startGoogleLogin.mockImplementation(() => {
      throw new Error('NEXT_PUBLIC_GOOGLE_CLIENT_ID가 없다');
    });
    render(<LoginScreen />);

    fireEvent.click(screen.getByRole('button', { name: /Google로 시작하기/ }));

    // 콘솔에만 남으면 "버튼이 안 눌리는" 증상이 된다 — 화면에 문구가 떠야 한다
    expect(screen.getByRole('alert')).toBeInTheDocument();
    // 소모했던 복원 경로가 되돌아와야 다음 시도에 다시 실린다
    expect(window.sessionStorage.getItem('pc-auth-return')).toBe('/settings/plugin');
  });

  it('이미 세션이 있으면 /home으로 되돌린다 (역가드)', async () => {
    window.localStorage.setItem('pc-auth', JSON.stringify({ v: 1, refreshToken: 'refresh-1' }));

    render(<LoginScreen />);

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/home'));
  });

  it('접근성 위반이 없다', async () => {
    const { container } = render(<LoginScreen />);
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});
