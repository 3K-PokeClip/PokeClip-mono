import { StrictMode, type AnchorHTMLAttributes, type ReactElement, type ReactNode } from 'react';
import { act, render, screen, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { axe } from 'jest-axe';
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
  it('교환을 기다리는 동안 문장 한 줄을 status 하나로 알린다(POK-442)', async () => {
    stubFetch(() => new Promise<Response>(() => {}));
    renderScreen(<SoopCallbackScreen />);

    // 스피너는 문장 옆이라 aria-hidden: 낭독기엔 문장만 한 번 들린다
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getByRole('status')).toHaveTextContent('SOOP 방송국을 연동하는 중…');
    expect(await axe(document.body)).toHaveNoViolations();
  });

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
    [422, 'IDENTITY_UNAVAILABLE', 'SOOP 방송국을 확인하지 못했어요'],
  ])('%i %s는 전용 문구로 알리고 채널 화면으로 되돌려보낸다', async (status, reason, title) => {
    stubFetch(() => jsonResponse(status, { reason }));
    renderScreen(<SoopCallbackScreen />);

    expect(await screen.findByText(title)).toBeInTheDocument();
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
    expect(document.body.textContent).not.toContain(reason);
    expect(track).not.toHaveBeenCalled();
  });

  it('422 IDENTITY_UNAVAILABLE이면 방송을 켠 상태에서 다시 연동하라는 설명까지 보인다', async () => {
    stubFetch(() => jsonResponse(422, { reason: 'IDENTITY_UNAVAILABLE' }));
    renderScreen(<SoopCallbackScreen />);

    expect(await screen.findByText('방송을 켠 상태에서 다시 연동해 주세요')).toBeInTheDocument();
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
  });

  it('422인데 reason이 다르면 폴백 문구다: 짝이 맞아야 그 실패로 읽는다', async () => {
    stubFetch(() => jsonResponse(422, { reason: 'SOMETHING_ELSE' }));
    renderScreen(<SoopCallbackScreen />);

    expect(await screen.findByText('연동에 실패했어요')).toBeInTheDocument();
    expect(screen.queryByText('SOOP 방송국을 확인하지 못했어요')).not.toBeInTheDocument();
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
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

// 왕복 중 세션 변경 (POK-222): 교환을 기다리는 사이 다른 탭이 계정을 바꾸면, 늦게 온 응답의
// 결과(채널명·성공·실패)를 다음 사람에게 알리지 않고 채널 화면으로만 보낸다. 같은 세션의
// 회전은 계정이 그대로라 평소처럼 알린다.
describe('SoopCallbackScreen: 왕복 중 세션 변경 (POK-222)', () => {
  /** 교환 응답을 붙잡아 두었다가 시험이 정한 때에 푼다. */
  function holdExchange() {
    let release!: (res: Response) => void;
    const pending = new Promise<Response>((resolve) => {
      release = resolve;
    });
    const spy = stubFetch(() => pending);
    return { spy, release };
  }

  /** 다른 탭이 보낸 세션 메시지: 실제 수신 경로(stores/auth의 채널)를 그대로 탄다. */
  const fromOtherTab = (msg: unknown) => new BroadcastChannel('pc-auth').postMessage(msg);

  it('다른 탭이 다른 계정으로 로그인하면 성공을 알리지 않는다: 앞 계정의 채널명이 안 보인다', async () => {
    const { spy, release } = holdExchange();
    renderScreen(<SoopCallbackScreen />);
    await waitFor(() => expect(exchangeCalls(spy)).toHaveLength(1));

    fromOtherTab({
      v: 1,
      type: 'login',
      pair: { accessToken: 'b-access', refreshToken: 'b-refresh' },
    });
    release(jsonResponse(201, linkedBody));

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
    expect(screen.queryByText('SOOP 방송국을 연동했어요')).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain('숲너구리');
    expect(track).not.toHaveBeenCalled(); // 집계도 지금 로그인한 사람에게 붙는다
  });

  it('다른 탭이 로그아웃하면 늦게 온 실패도 알리지 않는다', async () => {
    const { spy, release } = holdExchange();
    renderScreen(<SoopCallbackScreen />);
    await waitFor(() => expect(exchangeCalls(spy)).toHaveLength(1));

    fromOtherTab({ v: 1, type: 'logout' });
    release(jsonResponse(502, { reason: 'SOOP_UNAVAILABLE' }));

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
    expect(screen.queryByText('SOOP과 연결하지 못했어요')).not.toBeInTheDocument();
  });

  it('같은 세션의 회전은 계정이 그대로라 성공을 평소처럼 알린다', async () => {
    const { spy, release } = holdExchange();
    renderScreen(<SoopCallbackScreen />);
    await waitFor(() => expect(exchangeCalls(spy)).toHaveLength(1));

    fromOtherTab({
      v: 1,
      type: 'rotate',
      prev: 'refresh-1',
      pair: { accessToken: 'access-2', refreshToken: 'refresh-2' },
    });
    expect(useAuthStore.getState().refreshToken).toBe('refresh-2'); // 회전을 실제로 이어받았다
    release(jsonResponse(201, linkedBody));

    expect(await screen.findByText('SOOP 방송국을 연동했어요')).toBeInTheDocument();
    expect(replace).toHaveBeenCalledWith('/settings/channels');
    expect(track.mock.calls).toEqual([['platform_linked', { platform: 'soop' }]]);
  });
});
