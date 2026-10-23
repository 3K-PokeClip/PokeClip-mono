import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { chzzkLinkQueryOptions } from '@/api/chzzkLink';
import { soopLinkQueryOptions } from '@/api/soopLink';
import { youtubeLinkQueryOptions } from '@/api/youtubeLink';
import { ChannelSettingsScreen } from '@/features/settings/channels/ChannelSettingsScreen';
import { onCrossTabSessionChange, useAuthStore } from '@/stores/auth';
import { useOnboardingStore } from '@/stores/onboarding';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';

const { goToChzzkConsent, goToYoutubeConsent, goToSoopConsent } = vi.hoisted(() => ({
  goToChzzkConsent: vi.fn(),
  goToYoutubeConsent: vi.fn(),
  goToSoopConsent: vi.fn(),
}));

vi.mock('@/features/settings/channels/chzzkOAuth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/settings/channels/chzzkOAuth')>()),
  goToChzzkConsent,
}));
vi.mock('@/features/settings/channels/youtubeOAuth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/settings/channels/youtubeOAuth')>()),
  goToYoutubeConsent,
}));
vi.mock('@/features/settings/channels/soopOAuth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/settings/channels/soopOAuth')>()),
  goToSoopConsent,
}));

// 시작·해제 중 세션 변경 (POK-222): 요청을 기다리는 사이 다른 탭이 계정을 바꾸면, 늦게 온 응답이
// 다음 사람을 앞 계정의 동의 화면으로 보내거나, 캐시에 「미연동」을 심거나, 앞 계정의 결과를
// 토스트로 알리지 않는다. 세 플랫폼이 같은 규칙이어야 해서 한 표로 돌린다. 같은 세션의 회전은
// 계정이 그대로라 평소처럼 진행한다.

const PLATFORMS = [
  {
    name: '치지직',
    url: '/api/chzzk-link',
    goToConsent: goToChzzkConsent,
    authorizeUrl: `https://chzzk.naver.com/account-interlock?clientId=cid&redirectUri=${encodeURIComponent(
      'http://localhost:3000/oauth/chzzk/callback',
    )}&state=signed`,
    queryKey: chzzkLinkQueryOptions.queryKey,
    linkedBody: {
      linked: true,
      status: 'ACTIVE',
      channelId: 'chan-1',
      channelName: '게임하는너구리',
      linkedAt: '2026-08-21T03:00:00Z',
    },
    done: '치지직 연동을 해제했어요',
  },
  {
    name: '유튜브',
    url: '/api/youtube-link',
    goToConsent: goToYoutubeConsent,
    authorizeUrl: `https://accounts.google.com/o/oauth2/v2/auth?client_id=cid&redirect_uri=${encodeURIComponent(
      'http://localhost:3000/oauth/youtube/callback',
    )}&state=signed`,
    queryKey: youtubeLinkQueryOptions.queryKey,
    linkedBody: {
      linked: true,
      status: 'ACTIVE',
      channelId: 'yt-1',
      channelName: '포켓클립 게임채널',
      linkedAt: '2026-08-26T03:00:00Z',
    },
    done: '유튜브 연동을 해제했어요',
  },
  {
    name: 'SOOP',
    url: '/api/soop-link',
    goToConsent: goToSoopConsent,
    authorizeUrl: 'https://openapi.sooplive.com/auth/code?client_id=cid&state=soop-signed',
    queryKey: soopLinkQueryOptions.queryKey,
    linkedBody: {
      linked: true,
      status: 'ACTIVE',
      soopUserId: 'soop-user-a1',
      nickname: '숲너구리',
      linkedAt: '2026-10-23T03:00:00Z',
    },
    done: 'SOOP 연동을 해제했어요',
  },
] as const;

type Platform = (typeof PLATFORMS)[number];

/**
 * 이 플랫폼만 연동된 화면. DELETE 응답은 붙잡아 두었다가 시험이 정한 때에 푼다.
 * 계정이 바뀐 뒤의 상태 조회는 실패한다: 카드가 짚은 최악(심은 값이 그대로 굳는 경우)이다.
 * 조회가 성공하면 심은 값을 곧바로 덮어 시험이 오염을 못 본다.
 */
function holdUnlink(p: Platform) {
  let switched = false;
  let release!: (res: Response) => void;
  const pending = new Promise<Response>((resolve) => {
    release = resolve;
  });
  const spy = stubFetch((url, init) => {
    if (url === p.url && init?.method === 'DELETE') return pending;
    if (url === p.url) return switched ? jsonResponse(500) : jsonResponse(200, p.linkedBody);
    return jsonResponse(200, { linked: false });
  });
  const deleteCalls = () =>
    spy.mock.calls.filter(([url, init]) => url === p.url && init?.method === 'DELETE');
  const switchAccount = () => {
    switched = true;
    fromOtherTab({
      v: 1,
      type: 'login',
      pair: { accessToken: 'b-access', refreshToken: 'b-refresh' },
    });
  };
  return { release, deleteCalls, switchAccount };
}

/** 미연동 화면. 연동 시작(동의 주소 발급) 응답을 붙잡아 두었다가 시험이 정한 때에 푼다. */
function holdStart(p: Platform) {
  let release!: (res: Response) => void;
  const pending = new Promise<Response>((resolve) => {
    release = resolve;
  });
  const spy = stubFetch((url, init) => {
    if (url === `${p.url}/start` && init?.method === 'POST') return pending;
    return jsonResponse(200, { linked: false });
  });
  const startCalls = () =>
    spy.mock.calls.filter(([url, init]) => url === `${p.url}/start` && init?.method === 'POST');
  return { release, startCalls };
}

async function clickLink(p: Platform) {
  const user = userEvent.setup();
  const row = within(screen.getByRole('group', { name: p.name }));
  await user.click(await row.findByRole('button', { name: '연동' }));
}

/** 다른 탭이 보낸 세션 메시지: 실제 수신 경로(stores/auth의 채널)를 그대로 탄다. */
const fromOtherTab = (msg: unknown) => new BroadcastChannel('pc-auth').postMessage(msg);

/** 화면을 그리고 Providers처럼 세션 변경에 캐시를 비우게 한다. */
function renderScreen() {
  const { queryClient } = renderWithProviders(<ChannelSettingsScreen />);
  offs.push(onCrossTabSessionChange(() => queryClient.clear()));
  return queryClient;
}

async function confirmUnlink(p: Platform) {
  const user = userEvent.setup();
  const row = within(screen.getByRole('group', { name: p.name }));
  await user.click(await row.findByRole('button', { name: '연동 해제' }));
  await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '연동 해제' }));
}

const offs: Array<() => void> = [];

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  for (const p of PLATFORMS) p.goToConsent.mockReset();
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
  // 실제 앱에서는 AuthGuard의 hydrate가 다른 탭 수신을 켠다. 이 화면만 그리면 아무도 안 켠다.
  useAuthStore.getState().hydrate();
  useOnboardingStore.setState({
    welcomeSeen: false,
    tourDone: false,
    channelLinked: false,
    pluginLinked: false,
    hydrated: true,
    tourStep: null,
  });
});

afterEach(() => {
  offs.splice(0).forEach((off) => off());
  vi.unstubAllGlobals();
});

describe.each(PLATFORMS)('$name 해제 중 세션 변경 (POK-222)', (p) => {
  it('다른 계정으로 바뀐 뒤 온 성공은 캐시에 「미연동」을 심지 않고 알리지도 않는다', async () => {
    const { release, deleteCalls, switchAccount } = holdUnlink(p);
    const queryClient = renderScreen();
    const setQueryData = vi.spyOn(queryClient, 'setQueryData');
    await confirmUnlink(p);
    await waitFor(() => expect(deleteCalls()).toHaveLength(1));

    switchAccount();
    release(new Response(null, { status: 204 }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    // 심어졌다면 다음 사람의 재조회가 실패해도 그 값이 남아 살아 있는 연동을 미연동으로 그린다
    expect(setQueryData).not.toHaveBeenCalled();
    expect(queryClient.getQueryData(p.queryKey)).toBeUndefined();
    expect(screen.queryByText(p.done)).not.toBeInTheDocument();
  });

  it('다른 탭이 로그아웃한 뒤 온 실패도 알리지 않는다', async () => {
    const { release, deleteCalls } = holdUnlink(p);
    renderScreen();
    await confirmUnlink(p);
    await waitFor(() => expect(deleteCalls()).toHaveLength(1));

    fromOtherTab({ v: 1, type: 'logout' });
    release(jsonResponse(500, { reason: 'X' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.queryByText('연동 해제에 실패했어요')).not.toBeInTheDocument();
  });

  it('같은 세션의 회전은 계정이 그대로라 평소처럼 알리고 캐시를 갱신한다', async () => {
    const { release, deleteCalls } = holdUnlink(p);
    const queryClient = renderScreen();
    const setQueryData = vi.spyOn(queryClient, 'setQueryData');
    await confirmUnlink(p);
    await waitFor(() => expect(deleteCalls()).toHaveLength(1));

    fromOtherTab({
      v: 1,
      type: 'rotate',
      prev: 'refresh-1',
      pair: { accessToken: 'access-2', refreshToken: 'refresh-2' },
    });
    expect(useAuthStore.getState().refreshToken).toBe('refresh-2'); // 회전을 실제로 이어받았다
    release(new Response(null, { status: 204 }));

    expect(await screen.findByText(p.done)).toBeInTheDocument();
    expect(setQueryData).toHaveBeenCalledWith(p.queryKey, { linked: false });
  });
});

describe.each(PLATFORMS)('$name 연동 시작 중 세션 변경 (POK-222)', (p) => {
  it('다른 계정으로 바뀐 뒤 온 동의 주소로는 보내지 않는다: 그 주소는 앞 계정 몫이다', async () => {
    const { release, startCalls } = holdStart(p);
    renderScreen();
    await clickLink(p);
    await waitFor(() => expect(startCalls()).toHaveLength(1));

    fromOtherTab({
      v: 1,
      type: 'login',
      pair: { accessToken: 'b-access', refreshToken: 'b-refresh' },
    });
    release(jsonResponse(200, { authorizeUrl: p.authorizeUrl }));

    const row = within(screen.getByRole('group', { name: p.name }));
    await waitFor(() => expect(row.getByRole('button', { name: '연동' })).toBeEnabled());
    expect(p.goToConsent).not.toHaveBeenCalled();
  });

  it('다른 탭이 로그아웃한 뒤 온 실패도 알리지 않는다', async () => {
    const { release, startCalls } = holdStart(p);
    renderScreen();
    await clickLink(p);
    await waitFor(() => expect(startCalls()).toHaveLength(1));

    fromOtherTab({ v: 1, type: 'logout' });
    release(jsonResponse(500, { reason: 'X' }));

    const row = within(screen.getByRole('group', { name: p.name }));
    await waitFor(() => expect(row.getByRole('button', { name: '연동' })).toBeEnabled());
    expect(screen.queryByText('연동을 시작하지 못했어요')).not.toBeInTheDocument();
  });

  it('같은 세션의 회전은 계정이 그대로라 동의 화면으로 보낸다', async () => {
    const { release, startCalls } = holdStart(p);
    renderScreen();
    await clickLink(p);
    await waitFor(() => expect(startCalls()).toHaveLength(1));

    fromOtherTab({
      v: 1,
      type: 'rotate',
      prev: 'refresh-1',
      pair: { accessToken: 'access-2', refreshToken: 'refresh-2' },
    });
    expect(useAuthStore.getState().refreshToken).toBe('refresh-2'); // 회전을 실제로 이어받았다
    release(jsonResponse(200, { authorizeUrl: p.authorizeUrl }));

    await waitFor(() => expect(p.goToConsent).toHaveBeenCalledWith(p.authorizeUrl));
  });
});
