import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AccountDeletedScreen } from '@/features/account/AccountDeletedScreen';
import { markWithdrawn } from '@/features/account/withdrawHandoff';
import { useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';

const nav = vi.hoisted(() => ({ replace: vi.fn() }));

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: nav.replace }) }));

beforeEach(() => {
  nav.replace.mockReset();
  window.sessionStorage.clear();
  window.localStorage.clear();
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('AccountDeletedScreen — 표시', () => {
  it('탈퇴 완료 안내와 다시 가입할 수 있다는 사실을 알린다', () => {
    renderWithProviders(<AccountDeletedScreen />);

    expect(screen.getByRole('heading', { name: '탈퇴가 완료되었어요' })).toBeInTheDocument();
    expect(
      screen.getByText(
        '계정과 보관함 데이터가 삭제되었습니다. 같은 Google 계정으로 다시 가입할 수 있어요.',
      ),
    ).toBeInTheDocument();
  });

  it('로그인 화면으로가 뒤로 돌아갈 수 없게 replace로 보낸다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AccountDeletedScreen />);

    await user.click(screen.getByRole('button', { name: '로그인 화면으로' }));

    // push면 뒤로 가기로 탈퇴 완료 화면에 되돌아온다
    expect(nav.replace).toHaveBeenCalledWith('/login');
  });

  it('접근성 위반이 없다', async () => {
    const { container } = renderWithProviders(<AccountDeletedScreen />);
    expect(await axe(container)).toHaveNoViolations();
  });
});

// 세션 정리가 여기 있는 이유: 탈퇴 화면에서 접으면 그 리렌더로 깨어난 AuthGuard가
// /login으로 보내 완료 화면이 뜨지 못한다. 가드 밖인 여기가 접을 수 있는 첫 지점이다.
describe('AccountDeletedScreen — 세션 정리', () => {
  it('탈퇴로 들어오면 서버 세션까지 폐기하고 로컬 토큰을 지운다', async () => {
    const fetchSpy = stubFetch(() => jsonResponse(204));
    markWithdrawn();

    renderWithProviders(<AccountDeletedScreen />);

    // 서버 refresh 세션 폐기 — 안 하면 탈퇴가 일반 로그아웃보다 덜 정리하는 꼴이 된다
    await waitFor(() => {
      const called = fetchSpy.mock.calls.map(([url]) => String(url));
      expect(called).toContain('/api/auth/logout');
    });
    expect(useAuthStore.getState().refreshToken).toBeNull();
    // 표식은 1회용 — 새로고침으로 두 번 접히지 않는다
    expect(window.sessionStorage.getItem('pc-withdrawn')).toBeNull();
  });

  it('주소창으로 직접 들어오면 남의 세션을 건드리지 않고 돌려보낸다', () => {
    const fetchSpy = stubFetch(() => jsonResponse(204));

    renderWithProviders(<AccountDeletedScreen />);

    expect(fetchSpy).not.toHaveBeenCalled();
    expect(useAuthStore.getState().refreshToken).toBe('refresh-1');
    expect(nav.replace).toHaveBeenCalledWith('/home');
  });

  // /goodbye는 (dock) 밖이라 가드가 하이드레이션을 걸어 주지 않는다. 화면이 스스로 걸지
  // 않으면 스토어가 초기값(refreshToken: null)에 머물러 로그인한 사람도 로그아웃으로
  // 판정된다 — 이전 테스트는 hydrated를 손으로 세팅해 그 사실을 가리고 있었다.
  it('콜드 로드에서도 저장된 세션을 읽어 판정한다 — 스스로 하이드레이션한다', async () => {
    stubFetch(() => jsonResponse(204));
    window.localStorage.setItem('pc-auth', JSON.stringify({ refreshToken: 'stored-1' }));
    useAuthStore.setState({ accessToken: null, refreshToken: null, hydrated: false });

    renderWithProviders(<AccountDeletedScreen />);

    await waitFor(() => expect(nav.replace).toHaveBeenCalledWith('/home'));
  });

  it('로그인 화면으로는 의도적 종료 표식을 걷고 나간다', async () => {
    const user = userEvent.setup();
    markWithdrawn();
    stubFetch(() => jsonResponse(204));
    renderWithProviders(<AccountDeletedScreen />);
    await waitFor(() => expect(useAuthStore.getState().refreshToken).toBeNull());
    expect(window.sessionStorage.getItem('pc-auth-logout')).toBe('1');

    await user.click(screen.getByRole('button', { name: '로그인 화면으로' }));

    // 남겨 두면 다음에 비자발적으로 끊겼을 때 가드가 의도적 종료로 오해해
    // 복원 경로를 저장하지 않는다
    expect(window.sessionStorage.getItem('pc-auth-logout')).toBeNull();
    expect(nav.replace).toHaveBeenCalledWith('/login');
  });
});
