import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { streamKeyStatusQueryOptions } from '@/api/streamKeys';
import { PluginSettingsScreen } from '@/features/settings/plugin/PluginSettingsScreen';
import { onCrossTabSessionChange, useAuthStore } from '@/stores/auth';
import { useOnboardingStore } from '@/stores/onboarding';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';

// 코드 발급 중 세션 변경 (POK-424): 발급을 기다리는 사이 다른 탭이 계정을 바꾸면, 늦게 온 앞 계정의
// 페어링 코드를 다음 사람 화면에 띄우지 않는다. 그 코드로 플러그인을 짝지으면 앞 계정의 스트림 키를
// 받는다. 캐시에 「발급됨」을 심거나 다음 사람의 온보딩을 채우지도 않는다. 같은 세션의 회전은 그대로다.

const CODE = 'KQ4M-7X2P';

/** 키가 없는 화면. 코드 발급 응답은 붙잡아 두었다가 시험이 정한 때에 푼다. */
function holdIssue() {
  let release!: (res: Response) => void;
  const pending = new Promise<Response>((resolve) => {
    release = resolve;
  });
  const spy = stubFetch((url, init) => {
    if (url === '/api/stream-keys/pairing-codes' && init?.method === 'POST') return pending;
    if (url === '/api/stream-keys') return jsonResponse(200, { issued: false });
    return jsonResponse(404);
  });
  const issueCalls = () =>
    spy.mock.calls.filter(([url]) => url === '/api/stream-keys/pairing-codes');
  const issued = () =>
    jsonResponse(201, { code: CODE, expiresAt: new Date(Date.now() + 600_000).toISOString() });
  return { release, issueCalls, issued };
}

/** 다른 탭이 보낸 세션 메시지: 실제 수신 경로(stores/auth의 채널)를 그대로 탄다. */
const fromOtherTab = (msg: unknown) => new BroadcastChannel('pc-auth').postMessage(msg);

const offs: Array<() => void> = [];

/** 화면을 그리고 Providers처럼 세션 변경에 캐시를 비우게 한다. */
function renderScreen() {
  const { queryClient } = renderWithProviders(<PluginSettingsScreen />);
  offs.push(onCrossTabSessionChange(() => queryClient.clear()));
  return queryClient;
}

/** 늦게 온 응답과 그 콜백이 다 처리될 때까지 흘려 보낸다 */
async function settle() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 50));
  });
}

async function clickIssue() {
  fireEvent.click(await screen.findByRole('button', { name: '코드 발급' }));
}

beforeEach(() => {
  window.localStorage.clear();
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
  // 실제 앱에서는 AuthGuard의 hydrate가 다른 탭 수신을 켠다
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

describe('코드 발급 중 세션 변경 (POK-424)', () => {
  it('다른 계정으로 바뀐 뒤 온 페어링 코드는 띄우지 않는다: 그 코드는 앞 계정의 키를 준다', async () => {
    const { release, issueCalls, issued } = holdIssue();
    const queryClient = renderScreen();
    await clickIssue();
    await waitFor(() => expect(issueCalls()).toHaveLength(1));

    fromOtherTab({
      v: 1,
      type: 'login',
      pair: { accessToken: 'b-access', refreshToken: 'b-refresh' },
    });
    const setQueryData = vi.spyOn(queryClient, 'setQueryData');
    release(issued());

    await settle();
    expect(screen.queryByText(CODE)).not.toBeInTheDocument();
    expect(setQueryData).not.toHaveBeenCalledWith(
      streamKeyStatusQueryOptions.queryKey,
      expect.anything(),
    );
    expect(useOnboardingStore.getState().pluginLinked).toBe(false);
  });

  it('다른 탭이 로그아웃한 뒤 온 실패도 알리지 않는다', async () => {
    const { release, issueCalls } = holdIssue();
    renderScreen();
    await clickIssue();
    await waitFor(() => expect(issueCalls()).toHaveLength(1));

    fromOtherTab({ v: 1, type: 'logout' });
    release(jsonResponse(500, { reason: 'X' }));

    await settle();
    expect(screen.queryByText('코드 발급에 실패했어요')).not.toBeInTheDocument();
  });

  it('같은 세션의 회전은 계정이 그대로라 코드를 띄운다', async () => {
    const { release, issueCalls, issued } = holdIssue();
    renderScreen();
    await clickIssue();
    await waitFor(() => expect(issueCalls()).toHaveLength(1));

    fromOtherTab({
      v: 1,
      type: 'rotate',
      prev: 'refresh-1',
      pair: { accessToken: 'access-2', refreshToken: 'refresh-2' },
    });
    expect(useAuthStore.getState().refreshToken).toBe('refresh-2'); // 회전을 실제로 이어받았다
    release(issued());

    expect(await screen.findByText(CODE)).toBeInTheDocument();
    expect(useOnboardingStore.getState().pluginLinked).toBe(true);
  });
});
