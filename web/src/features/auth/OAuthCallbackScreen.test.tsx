import { StrictMode, type AnchorHTMLAttributes, type ReactElement, type ReactNode } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { axe } from 'jest-axe';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { OAuthCallbackScreen } from '@/features/auth/OAuthCallbackScreen';
import { useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { createTestQueryClient } from '@/test/testProviders';

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

// next/link의 replace는 DOM에 흔적을 안 남긴다. 이 화면이 replace로 가는 것이
// 이 화면의 요점이라(소모된 콜백 URL로 뒤로 가기 방지) 속성으로 드러내 고정한다.
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

const STATE_KEY = 'pc-oauth-state';
const VERIFIER = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';

/** 로그인 버튼이 남기는 시도 기록 — state와 PKCE verifier가 한 항목이다 (POK-483). */
function storeAttempt(returnTo: string | null) {
  window.sessionStorage.setItem(
    STATE_KEY,
    JSON.stringify({ state: 'state-1', returnTo, codeVerifier: VERIFIER }),
  );
}

/** verifier는 1회용이다 — 어느 갈래로 끝나든 탭 저장소 어디에도 남으면 안 된다. */
function verifierLeftInStorage(): boolean {
  return Object.keys(window.sessionStorage).some((key) =>
    (window.sessionStorage.getItem(key) ?? '').includes(VERIFIER),
  );
}

/** 화면이 useQueryClient를 쓰므로 항상 QueryClientProvider로 감싼다. */
function renderWithClient(ui: ReactElement) {
  const queryClient = createTestQueryClient();
  render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
  return queryClient;
}

beforeEach(() => {
  window.sessionStorage.clear();
  window.localStorage.clear();
  replace.mockReset();
  track.mockReset();
  useAuthStore.setState({ accessToken: null, refreshToken: null, hydrated: false });
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/'); // 주소를 지우는지 보는 시험이 바꾼 주소를 되돌린다
});

describe('OAuthCallbackScreen', () => {
  it('정상 code는 StrictMode 이중 마운트에도 한 번만 교환하고 가려던 곳으로 복원한다', async () => {
    storeAttempt('/settings/plugin');
    searchRef.current = new URLSearchParams('code=code-1&state=state-1');
    const spy = stubFetch((url) =>
      url === '/api/auth/google'
        ? jsonResponse(200, { accessToken: 'access-1', refreshToken: 'refresh-1' })
        : jsonResponse(404),
    );

    // 실제 dev 환경과 같은 StrictMode 이중 이펙트 — code는 1회용이라 POST가 두 번이면 안 된다
    const queryClient = renderWithClient(
      <StrictMode>
        <OAuthCallbackScreen />
      </StrictMode>,
    );
    // 이전 계정이 남긴 캐시 재현 — 로그인 성공이 이것을 비워야 한다 (리뷰 #72)
    queryClient.setQueryData(['auth', 'me'], { name: '이전 사용자' });

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/plugin'));
    const exchanges = spy.mock.calls.filter(([url]) => url === '/api/auth/google');
    expect(exchanges).toHaveLength(1);
    // PKCE — 동의 URL에 실은 challenge의 원문을 교환 본문으로 보낸다 (POK-483)
    expect(JSON.parse(String(exchanges[0]?.[1]?.body))).toEqual({
      code: 'code-1',
      codeVerifier: VERIFIER,
    });
    expect(verifierLeftInStorage()).toBe(false);
    expect(useAuthStore.getState().accessToken).toBe('access-1');
    expect(queryClient.getQueryData(['auth', 'me'])).toBeUndefined();
    // 사용 통계 — 이중 마운트에도 한 번, 속성 없음(POK-357)
    expect(track.mock.calls).toEqual([['login_completed', {}]]);
  });

  it.each([
    'https://evil.example/phish',
    '//evil.example',
    '/\\evil.example',
    '/\n/evil.example',
    '/\t/evil.example',
    // 같은 오리진 절대 URL + "//" 경로 — pathname이 프로토콜 상대 URL로 재해석되는 우회
    `${window.location.origin}//evil.example`,
  ])(
    '심긴 외부 URL returnTo(%s)는 무시하고 홈으로 보낸다 — 오픈 리다이렉트 방지',
    async (planted) => {
      storeAttempt(planted);
      searchRef.current = new URLSearchParams('code=code-1&state=state-1');
      stubFetch((url) =>
        url === '/api/auth/google'
          ? jsonResponse(200, { accessToken: 'access-1', refreshToken: 'refresh-1' })
          : jsonResponse(404),
      );

      renderWithClient(<OAuthCallbackScreen />);

      await waitFor(() => expect(replace).toHaveBeenCalledWith('/home'));
    },
  );

  it('교환을 기다리는 동안 문장 한 줄을 status 하나로 알린다(POK-442)', async () => {
    storeAttempt(null);
    searchRef.current = new URLSearchParams('code=code-1&state=state-1');
    stubFetch(() => new Promise<Response>(() => {}));
    const { container } = render(
      <QueryClientProvider client={createTestQueryClient()}>
        <OAuthCallbackScreen />
      </QueryClientProvider>,
    );

    // 스피너는 문장 옆이라 aria-hidden: 낭독기엔 문장만 한 번 들린다
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getByRole('status')).toHaveTextContent('로그인하는 중…');
    expect(await axe(container)).toHaveNoViolations();
  });

  it('state가 어긋나면 교환하지 않고 에러 안내를 띄운다', () => {
    storeAttempt(null);
    searchRef.current = new URLSearchParams('code=code-1&state=state-other');
    const spy = stubFetch(() => jsonResponse(404));

    renderWithClient(<OAuthCallbackScreen />);

    expect(screen.getByText('로그인을 확인할 수 없어요')).toBeInTheDocument();
    expect(spy).not.toHaveBeenCalled();
    expect(verifierLeftInStorage()).toBe(false);
  });

  it('verifier 없는 시도 기록(배포 전에 시작한 로그인)은 교환하지 않고 처음부터 다시 하게 한다', () => {
    window.sessionStorage.setItem(STATE_KEY, JSON.stringify({ state: 'state-1', returnTo: null }));
    searchRef.current = new URLSearchParams('code=code-1&state=state-1');
    const spy = stubFetch(() => jsonResponse(404));

    renderWithClient(<OAuthCallbackScreen />);

    // web은 PKCE 없이 교환하지 않는다 — 처음부터 다시 하면 새 verifier로 시작한다
    expect(screen.getByText('로그인을 확인할 수 없어요')).toBeInTheDocument();
    expect(spy).not.toHaveBeenCalled();
  });

  it('?error=(동의 취소)는 취소 안내를 띄우고 verifier를 지운다', () => {
    storeAttempt(null);
    searchRef.current = new URLSearchParams('error=access_denied');
    stubFetch(() => jsonResponse(404));

    renderWithClient(<OAuthCallbackScreen />);

    expect(screen.getByText('로그인이 취소되었어요')).toBeInTheDocument();
    expect(verifierLeftInStorage()).toBe(false);
  });

  it('교환 401이면 실패 안내를 띄우고 로그인 화면으로 가는 링크를 준다', async () => {
    storeAttempt(null);
    searchRef.current = new URLSearchParams('code=code-1&state=state-1');
    stubFetch(() => jsonResponse(401, { message: '인증 실패' }));

    renderWithClient(<OAuthCallbackScreen />);

    expect(await screen.findByText('로그인에 실패했어요')).toBeInTheDocument();
    expect(track).not.toHaveBeenCalled(); // 실패는 세지 않는다
    expect(verifierLeftInStorage()).toBe(false); // 실패한 시도의 verifier로 다시 교환할 길을 남기지 않는다
    // 버튼이 아니라 링크여야 한다 — 가운데 클릭·새 탭이 살아 있고, JS 없이도 간다.
    const link = screen.getByRole('link', { name: '로그인 화면으로' });
    expect(link).toHaveAttribute('href', '/login');
    // 히스토리를 남기면 뒤로 가기가 소모된 state의 콜백 URL로 돌아간다.
    expect(link).toHaveAttribute('data-replace');
  });

  it('구글 이메일 인증을 안 마친 계정(403)은 서버 문구로 할 일을 알려 준다', async () => {
    storeAttempt(null);
    searchRef.current = new URLSearchParams('code=code-1&state=state-1');
    stubFetch(() =>
      jsonResponse(403, {
        message: '구글 계정의 이메일 인증을 마친 뒤 다시 로그인해 주세요',
        reason: 'GOOGLE_EMAIL_UNVERIFIED',
      }),
    );

    renderWithClient(<OAuthCallbackScreen />);

    expect(await screen.findByText('로그인할 수 없는 계정이에요')).toBeInTheDocument();
    expect(
      screen.getByText('구글 계정의 이메일 인증을 마친 뒤 다시 로그인해 주세요'),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '로그인 화면으로' })).toHaveAttribute('href', '/login');
  });

  it('실패 안내로 머물러도 주소창에 code·state가 남지 않는다 (POK-482)', async () => {
    storeAttempt(null);
    window.history.replaceState(null, '', '/auth/callback?code=code-1&state=state-1');
    searchRef.current = new URLSearchParams('code=code-1&state=state-1');
    const spy = stubFetch(() => jsonResponse(401, { message: '인증 실패' }));

    renderWithClient(<OAuthCallbackScreen />);

    expect(await screen.findByText('로그인에 실패했어요')).toBeInTheDocument();
    expect(spy).toHaveBeenCalledTimes(1); // 지우기 전에 읽은 code로 교환은 그대로 나간다
    expect(window.location.pathname).toBe('/auth/callback');
    expect(window.location.search).toBe('');
  });
});
