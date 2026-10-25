import { StrictMode, type AnchorHTMLAttributes, type ReactElement, type ReactNode } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { axe } from 'jest-axe';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChzzkCallbackScreen } from '@/features/settings/channels/ChzzkCallbackScreen';
import { chzzkLinkQueryOptions } from '@/api/chzzkLink';
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

/** POST /api/chzzk-link 호출만 센다 — 1회용 code가 두 번 나가지 않는지가 요점이다. */
const exchangeCalls = (spy: ReturnType<typeof stubFetch>) =>
  spy.mock.calls.filter(([url, init]) => url === '/api/chzzk-link' && init?.method === 'POST');

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
  window.history.replaceState(null, '', '/'); // 주소를 지우는지 보는 시험이 바꾼 주소를 되돌린다
});

describe('ChzzkCallbackScreen', () => {
  it('교환을 기다리는 동안 문장 한 줄을 status 하나로 알린다(POK-442)', async () => {
    stubFetch(() => new Promise<Response>(() => {}));
    renderScreen(<ChzzkCallbackScreen />);

    // 스피너는 문장 옆이라 aria-hidden: 낭독기엔 문장만 한 번 들린다
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getByRole('status')).toHaveTextContent('치지직 채널을 연동하는 중…');
    expect(await axe(document.body)).toHaveNoViolations();
  });

  it('교환에 성공하면 캐시를 심고 성공 토스트를 띄운 뒤 채널 화면으로 되돌아간다', async () => {
    stubFetch(() =>
      jsonResponse(201, {
        channelId: 'chan-secret-1',
        channelName: '게임하는너구리',
        linkedAt: '2026-08-21T03:00:00Z',
      }),
    );
    const queryClient = renderScreen(<ChzzkCallbackScreen />);

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
    expect(await screen.findByText('치지직 채널을 연동했어요')).toBeInTheDocument();

    // 캐시에 계정 데이터를 심지 않는다 — 다른 탭이 계정을 바꾼 뒤 늦게 도착한 응답이
    // 이전 계정 채널을 되살리지 않게, 비워서 목적지가 처음부터 읽게 한다
    expect(queryClient.getQueryData(chzzkLinkQueryOptions.queryKey)).toBeUndefined();
    expect(document.body.innerHTML).not.toContain('chan-secret-1');
    // 사용 통계 — 플랫폼만, 채널 ID·이름은 싣지 않는다(POK-357)
    expect(track.mock.calls).toEqual([['platform_linked', { platform: 'chzzk' }]]);
  });

  it('StrictMode 이중 마운트에도 교환은 정확히 한 번이다 — code는 1회용이다', async () => {
    const spy = stubFetch(() =>
      jsonResponse(201, {
        channelId: 'chan-1',
        channelName: '게임하는너구리',
        linkedAt: '2026-08-21T03:00:00Z',
      }),
    );
    renderScreen(
      <StrictMode>
        <ChzzkCallbackScreen />
      </StrictMode>,
    );

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
    expect(exchangeCalls(spy)).toHaveLength(1);
  });

  it.each([
    [400, 'INVALID_STATE', '연동 요청을 확인할 수 없어요'],
    [400, 'INVALID_CODE', '연동을 마치지 못했어요'],
    [409, 'CHANNEL_ALREADY_LINKED', '이미 다른 계정에 연동된 채널이에요'],
    [502, 'CHZZK_UNAVAILABLE', '치지직과 연결하지 못했어요'],
  ])('%i %s는 전용 문구로 알리고 채널 화면으로 되돌려보낸다', async (status, reason, title) => {
    stubFetch(() => jsonResponse(status, { reason }));
    renderScreen(<ChzzkCallbackScreen />);

    expect(await screen.findByText(title)).toBeInTheDocument();
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
    // 서버 reason 원문이 화면에 새지 않는다
    expect(document.body.textContent).not.toContain(reason);
    expect(track).not.toHaveBeenCalled(); // 실패는 세지 않는다
  });

  it('5xx는 도달 실패 문구로 — 연동을 다시 시작하라고 알린다 (POK-217)', async () => {
    stubFetch(() => jsonResponse(500, { reason: 'BOOM' }));
    renderScreen(<ChzzkCallbackScreen />);

    expect(await screen.findByText('서버와 연결이 원활하지 않아요')).toBeInTheDocument();
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
  });

  it('모르는 4xx 실패는 폴백 문구로 알린다', async () => {
    stubFetch(() => jsonResponse(400, { reason: 'BOOM' }));
    renderScreen(<ChzzkCallbackScreen />);

    expect(await screen.findByText('연동에 실패했어요')).toBeInTheDocument();
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
  });

  it('code·state가 없으면 교환하지 않고 취소로 안내한다', async () => {
    searchRef.current = new URLSearchParams('error=access_denied');
    const spy = stubFetch(() => jsonResponse(500));
    renderScreen(<ChzzkCallbackScreen />);

    expect(await screen.findByText('연동이 취소됐어요')).toBeInTheDocument();
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
    expect(exchangeCalls(spy)).toHaveLength(0);
  });

  it('회전이 일시적으로 실패하면 로그아웃으로 읽지 않는다 — 로그인 화면은 막다른 길이다', async () => {
    // 하드 내비게이션으로 도착해 access 토큰이 비어 있고, 마침 auth 서버가 잠깐 5xx인 경우.
    // doRefresh는 토큰을 보존한 채 실패하고 apiFetch는 이를 503으로 던진다(POK-217).
    // 이것을 로그아웃으로 읽으면 LoginScreen 역가드가 살아있는 refreshToken을 보고
    // /home으로 튕겨, 로그인하라는 안내를 받고도 로그인 화면에 갈 수 없게 된다.
    useAuthStore.setState({ accessToken: null, refreshToken: 'refresh-1', hydrated: true });
    window.localStorage.setItem('pc-auth', JSON.stringify({ v: 1, refreshToken: 'refresh-1' })); // 회전 직전 정본 동기화가 읽는 값
    stubFetch((url) => (url === '/api/auth/refresh' ? jsonResponse(500, {}) : jsonResponse(401)));
    renderScreen(<ChzzkCallbackScreen />);

    expect(await screen.findByText('서버와 연결이 원활하지 않아요')).toBeInTheDocument();
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
    expect(screen.queryByRole('link', { name: '로그인 화면으로' })).not.toBeInTheDocument();
    // 세션은 그대로다 — 사용자는 채널 화면에서 다시 연동을 누르면 된다
    expect(useAuthStore.getState().refreshToken).toBe('refresh-1');
  });

  it('회전이 거부돼 세션이 실제로 접히면 로그인 화면으로 보낸다', async () => {
    useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
    stubFetch(() => jsonResponse(401));
    renderScreen(<ChzzkCallbackScreen />);

    expect(await screen.findByRole('link', { name: '로그인 화면으로' })).toBeInTheDocument();
    // apiFetch가 clearTokens까지 돈 상태 — 이때만 로그인 화면이 막다른 길이 아니다
    expect(useAuthStore.getState().refreshToken).toBeNull();
  });

  it('세션이 없으면 교환하지 않고 로그인 화면으로 안내한다 — 되돌아올 자리를 남긴다', async () => {
    useAuthStore.setState({ accessToken: null, refreshToken: null, hydrated: true });
    const spy = stubFetch(() => jsonResponse(500));
    renderScreen(<ChzzkCallbackScreen />);

    const link = await screen.findByRole('link', { name: '로그인 화면으로' });
    expect(link).toHaveAttribute('href', '/login');
    expect(link).toHaveAttribute('data-replace');
    expect(exchangeCalls(spy)).toHaveLength(0);
    expect(replace).not.toHaveBeenCalled();
    expect(window.sessionStorage.getItem('pc-auth-return')).toBe('/settings/channels');
  });

  it('로그인 안내로 머물러도 주소창에 code·state가 남지 않는다 (POK-482)', async () => {
    window.history.replaceState(null, '', '/oauth/chzzk/callback?code=code-1&state=state-1');
    useAuthStore.setState({ accessToken: null, refreshToken: null, hydrated: true });
    stubFetch(() => jsonResponse(500));
    renderScreen(<ChzzkCallbackScreen />);

    await screen.findByRole('link', { name: '로그인 화면으로' });
    expect(window.location.pathname).toBe('/oauth/chzzk/callback');
    expect(window.location.search).toBe('');
  });
});

// 왕복 중 세션 변경 (POK-222): 교환을 기다리는 사이 다른 탭이 계정을 바꾸면, 늦게 온 응답의
// 결과(채널명·성공·실패)를 다음 사람에게 알리지 않고 채널 화면으로만 보낸다. 같은 세션의
// 회전은 계정이 그대로라 평소처럼 알린다.
describe('ChzzkCallbackScreen: 왕복 중 세션 변경 (POK-222)', () => {
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
    renderScreen(<ChzzkCallbackScreen />);
    await waitFor(() => expect(exchangeCalls(spy)).toHaveLength(1));

    fromOtherTab({
      v: 1,
      type: 'login',
      pair: { accessToken: 'b-access', refreshToken: 'b-refresh' },
    });
    release(
      jsonResponse(201, {
        channelId: 'chan-1',
        channelName: '게임하는너구리',
        linkedAt: '2026-08-21T03:00:00Z',
      }),
    );

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
    expect(screen.queryByText('치지직 채널을 연동했어요')).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain('게임하는너구리');
    expect(track).not.toHaveBeenCalled(); // 집계도 지금 로그인한 사람에게 붙는다
  });

  it('다른 탭이 로그아웃하면 늦게 온 실패도 알리지 않는다', async () => {
    const { spy, release } = holdExchange();
    renderScreen(<ChzzkCallbackScreen />);
    await waitFor(() => expect(exchangeCalls(spy)).toHaveLength(1));

    fromOtherTab({ v: 1, type: 'logout' });
    release(jsonResponse(502, { reason: 'CHZZK_UNAVAILABLE' }));

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/channels'));
    expect(screen.queryByText('치지직과 연결하지 못했어요')).not.toBeInTheDocument();
  });

  it('같은 세션의 회전은 계정이 그대로라 성공을 평소처럼 알린다', async () => {
    const { spy, release } = holdExchange();
    renderScreen(<ChzzkCallbackScreen />);
    await waitFor(() => expect(exchangeCalls(spy)).toHaveLength(1));

    fromOtherTab({
      v: 1,
      type: 'rotate',
      prev: 'refresh-1',
      pair: { accessToken: 'access-2', refreshToken: 'refresh-2' },
    });
    expect(useAuthStore.getState().refreshToken).toBe('refresh-2'); // 회전을 실제로 이어받았다
    release(
      jsonResponse(201, {
        channelId: 'chan-1',
        channelName: '게임하는너구리',
        linkedAt: '2026-08-21T03:00:00Z',
      }),
    );

    expect(await screen.findByText('치지직 채널을 연동했어요')).toBeInTheDocument();
    expect(replace).toHaveBeenCalledWith('/settings/channels');
    expect(track.mock.calls).toEqual([['platform_linked', { platform: 'chzzk' }]]);
  });
});
