import { StrictMode, type AnchorHTMLAttributes, type ReactElement, type ReactNode } from 'react';
import { act, render, screen, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SoopCallbackScreen } from '@/features/settings/channels/SoopCallbackScreen';
import { soopLinkQueryOptions } from '@/api/soopLink';
import { useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { createTestQueryClient } from '@/test/testProviders';
import { ToastProvider } from '@/ui';

const { replace, searchRef, track } = vi.hoisted(() => ({
  replace: vi.fn(),
  searchRef: { current: new URLSearchParams() },
  track: vi.fn(),
}));

vi.mock('@/lib/analytics', () => ({ track, identifyUser: vi.fn() }));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace }),
  useSearchParams: () => searchRef.current,
}));

vi.mock('next/link', () => ({
  default: ({
    replace: isReplace,
    children,
    ...rest
  }: { replace?: boolean; children?: ReactNode } & AnchorHTMLAttributes<HTMLAnchorElement>) => (
    <a data-replace={isReplace ? '' : undefined} {...rest}>
      {children}
    </a>
  ),
}));

function renderScreen(ui: ReactElement) {
  const queryClient = createTestQueryClient();
  render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>{ui}</ToastProvider>
    </QueryClientProvider>,
  );
  return queryClient;
}

const exchangeCalls = (spy: ReturnType<typeof stubFetch>) =>
  spy.mock.calls.filter(([url, init]) => url === '/api/soop-link' && init?.method === 'POST');

const linkedBody = {
  soopUserId: 'soop-user-a1',
  nickname: '숲너구리',
  linkedAt: '2026-10-23T03:00:00Z',
};

beforeEach(() => {
  window.sessionStorage.clear();
  window.localStorage.clear();
  replace.mockReset();
  track.mockReset();
  searchRef.current = new URLSearchParams('code=code-1&state=state-1');
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('SoopCallbackScreen', () => {
  it('code·state로 완료를 부르고 채널 화면으로 replace한다: 캐시를 비우고 아이디를 그리지 않는다', async () => {
    const spy = stubFetch(() => jsonResponse(201, linkedBody));
    const queryClient = renderScreen(<SoopCallbackScreen />);

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
    expect(await screen.findByText('SOOP 방송국을 연동했어요')).toBeInTheDocument();
    expect(JSON.parse(String(exchangeCalls(spy)[0]?.[1]?.body))).toEqual({
      code: 'code-1',
      state: 'state-1',
    });
    expect(queryClient.getQueryData(soopLinkQueryOptions.queryKey)).toBeUndefined();
    expect(document.body.innerHTML).not.toContain('soop-user-a1');
    expect(track.mock.calls).toEqual([['platform_linked', { platform: 'soop' }]]);
  });

  it('쿼리에 state가 없으면 저장소에 옛 값이 있어도 쓰지 않는다: 교환하지 않고 확인 불가로 안내한다', async () => {
    searchRef.current = new URLSearchParams('code=code-1');
    // 지난 판(대체 경로)이 남겼을 수 있는 값. 이제는 읽지 않는다
    window.sessionStorage.setItem('pokeclip.soop.state', 'stored-state');
    const spy = stubFetch(() => jsonResponse(201, linkedBody));
    renderScreen(<SoopCallbackScreen />);

    expect(await screen.findByText('연동 요청을 확인할 수 없어요')).toBeInTheDocument();
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
    expect(exchangeCalls(spy)).toHaveLength(0);
  });

  it('StrictMode 이중 마운트에도 교환은 정확히 한 번이다: code는 1회용이다', async () => {
    const spy = stubFetch(() => jsonResponse(201, linkedBody));
    renderScreen(
      <StrictMode>
        <SoopCallbackScreen />
      </StrictMode>,
    );

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
    expect(exchangeCalls(spy)).toHaveLength(1);
  });

  it('교환 중에 토큰이 회전해 이펙트가 다시 돌아도 교환은 한 번이다', async () => {
    // 위 StrictMode 시험은 이 환경에서 이펙트를 두 번 돌리지 않아 ref 가드를 재지 못한다(주입으로 확인).
    // 의존값(refreshToken)을 바꿔 이펙트를 실제로 다시 돌린다.
    let release: (r: Response) => void = () => {};
    const spy = stubFetch(
      () =>
        new Promise<Response>((resolve) => {
          release = resolve;
        }),
    );
    renderScreen(<SoopCallbackScreen />);

    await waitFor(() => expect(exchangeCalls(spy)).toHaveLength(1));
    act(() => useAuthStore.setState({ refreshToken: 'refresh-2' }));
    release(jsonResponse(201, linkedBody));

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
    expect(exchangeCalls(spy)).toHaveLength(1);
  });

  it.each([
    [400, 'INVALID_STATE', '연동 요청을 확인할 수 없어요'],
    [400, 'INVALID_CODE', '연동을 마치지 못했어요'],
    [409, 'CHANNEL_ALREADY_LINKED', '이미 다른 계정에 연동된 방송국이에요'],
    [502, 'SOOP_UNAVAILABLE', 'SOOP과 연결하지 못했어요'],
  ])('%i %s는 전용 문구로 알리고 채널 화면으로 되돌려보낸다', async (status, reason, title) => {
    stubFetch(() => jsonResponse(status, { reason }));
    renderScreen(<SoopCallbackScreen />);

    expect(await screen.findByText(title)).toBeInTheDocument();
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
    expect(document.body.textContent).not.toContain(reason);
    expect(track).not.toHaveBeenCalled();
  });

  it('code가 없으면 교환하지 않고 취소로 안내한다', async () => {
    searchRef.current = new URLSearchParams('error=access_denied');
    const spy = stubFetch(() => jsonResponse(500));
    renderScreen(<SoopCallbackScreen />);

    expect(await screen.findByText('연동이 취소됐어요')).toBeInTheDocument();
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
    expect(exchangeCalls(spy)).toHaveLength(0);
  });

  it('code는 있는데 state가 쿼리에도 저장소에도 없으면 교환하지 않고 확인 불가로 안내한다', async () => {
    searchRef.current = new URLSearchParams('code=code-1');
    const spy = stubFetch(() => jsonResponse(500));
    renderScreen(<SoopCallbackScreen />);

    expect(await screen.findByText('연동 요청을 확인할 수 없어요')).toBeInTheDocument();
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
    expect(exchangeCalls(spy)).toHaveLength(0);
  });

  it('세션이 없으면 교환하지 않고 로그인 화면으로 안내한다: 되돌아올 자리를 남긴다', async () => {
    useAuthStore.setState({ accessToken: null, refreshToken: null, hydrated: true });
    const spy = stubFetch(() => jsonResponse(500));
    renderScreen(<SoopCallbackScreen />);

    const link = await screen.findByRole('link', { name: '로그인 화면으로' });
    expect(link).toHaveAttribute('href', '/login');
    expect(exchangeCalls(spy)).toHaveLength(0);
    expect(replace).not.toHaveBeenCalled();
    expect(window.sessionStorage.getItem('pc-auth-return')).toBe('/settings/channels');
  });
});
