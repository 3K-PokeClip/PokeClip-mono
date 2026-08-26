import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChannelSettingsScreen } from '@/features/settings/channels/ChannelSettingsScreen';
import { useAuthStore } from '@/stores/auth';
import { useOnboardingStore } from '@/stores/onboarding';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';

// 유튜브 구획 상태·해제 (POK-221) — 치지직 관행대로 화면 전체(ChannelSettingsScreen)를
// fetch 스텁으로 렌더한다. 치지직 쪽 단언은 ChannelSettingsScreen.test.tsx가 갖고,
// 여기는 유튜브 행·모달·토스트만 본다.

const { goToYoutubeConsent } = vi.hoisted(() => ({ goToYoutubeConsent: vi.fn() }));

vi.mock('@/features/settings/channels/youtubeOAuth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/settings/channels/youtubeOAuth')>()),
  goToYoutubeConsent,
}));

const YT_LINK_URL = '/api/youtube-link';
const YT_START_URL = '/api/youtube-link/start';
const YT_AUTHORIZE_URL = `https://accounts.google.com/o/oauth2/v2/auth?client_id=cid&redirect_uri=${encodeURIComponent(
  'http://localhost:3000/oauth/youtube/callback',
)}&state=signed`;

/** GET /api/youtube-link 응답만 갈아끼우는 스텁 — 치지직 행은 미연동으로 눕힌다. */
function stubYtStatus(body: unknown, status = 200) {
  return stubFetch((url, init) => {
    if (url === YT_START_URL && init?.method === 'POST') {
      return jsonResponse(200, { authorizeUrl: YT_AUTHORIZE_URL });
    }
    if (url === YT_LINK_URL) return jsonResponse(status, body);
    return jsonResponse(200, { linked: false });
  });
}

const ytLinked = (status: string, extra: Record<string, unknown> = {}) => ({
  linked: status === 'ACTIVE',
  status,
  channelId: 'yt-secret-1',
  channelName: '포켓클립 게임채널',
  linkedAt: '2026-08-26T03:00:00Z',
  ...extra,
});

const row = (name: string) => within(screen.getByRole('group', { name }));
const youtube = () => row('유튜브');

beforeEach(() => {
  window.localStorage.clear();
  goToYoutubeConsent.mockReset();
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
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
  vi.unstubAllGlobals();
});

describe('YoutubeChannelSection — 연동 상태 표시', () => {
  it('연동이 없으면 연동 버튼만 보인다 — 배지도 채널명도 없다', async () => {
    stubYtStatus({ linked: false });
    renderWithProviders(<ChannelSettingsScreen />);

    expect(await youtube().findByRole('button', { name: '연동' })).toBeEnabled();
    expect(youtube().queryByText('정상')).not.toBeInTheDocument();
    expect(screen.queryByText(/포켓클립 게임채널/)).not.toBeInTheDocument();
  });

  it('ACTIVE는 정상 배지와 채널명 한 줄을 보여준다 (1k)', async () => {
    stubYtStatus(ytLinked('ACTIVE'));
    renderWithProviders(<ChannelSettingsScreen />);

    expect(await youtube().findByText('정상')).toBeInTheDocument();
    // 1k의 보조설명은 채널명 한 줄이다 — 연동일·구독자를 덧붙이지 않는다
    expect(youtube().getByText('포켓클립 게임채널')).toBeInTheDocument();
    expect(youtube().getByRole('button', { name: '연동 해제' })).toBeInTheDocument();
  });

  it('BROKEN은 끊김을 알리고 다시 연동만 내준다 — 해제 버튼이 없다', async () => {
    stubYtStatus(ytLinked('BROKEN'));
    renderWithProviders(<ChannelSettingsScreen />);

    expect(await youtube().findByText('연동 끊김')).toBeInTheDocument();
    expect(youtube().getByText(/유튜브 연동이 끊겼어요/)).toBeInTheDocument();
    expect(youtube().getByRole('button', { name: '다시 연동' })).toBeInTheDocument();
    // BROKEN 행은 이미 닫힌 행이라 DELETE가 무동작이다 — 눌러도 안 되는 버튼을 두지 않는다
    expect(youtube().queryByRole('button', { name: '연동 해제' })).not.toBeInTheDocument();
  });

  it('BROKEN에서 다시 연동을 누르면 같은 시작 경로로 나간다 — 재연동은 별도 API가 아니다', async () => {
    const user = userEvent.setup();
    const spy = stubYtStatus(ytLinked('BROKEN'));
    renderWithProviders(<ChannelSettingsScreen />);

    await user.click(await youtube().findByRole('button', { name: '다시 연동' }));

    await waitFor(() => expect(goToYoutubeConsent).toHaveBeenCalledWith(YT_AUTHORIZE_URL));
    expect(
      spy.mock.calls.filter(([url, init]) => url === YT_START_URL && init?.method === 'POST'),
    ).toHaveLength(1);
  });

  it('UNLINKED는 미연동과 같게 그린다 — 해제한 채널 이름을 남기지 않는다', async () => {
    stubYtStatus(ytLinked('UNLINKED'));
    renderWithProviders(<ChannelSettingsScreen />);

    expect(await youtube().findByRole('button', { name: '연동' })).toBeInTheDocument();
    expect(screen.queryByText(/포켓클립 게임채널/)).not.toBeInTheDocument();
  });

  it('모르는 status는 none으로 접는다 — EXPIRED가 와도 「갱신 필요」 상태를 만들지 않는다', async () => {
    // 유튜브 계약에 EXPIRED는 없다(구글 access 만료는 갱신으로 해소되는 일상). 계약 밖의
    // 값이 섞여 들어와도 치지직의 「갱신 필요」류 상시 표기가 저절로 생기면 안 된다.
    stubYtStatus(ytLinked('EXPIRED'));
    renderWithProviders(<ChannelSettingsScreen />);

    expect(await youtube().findByRole('button', { name: '연동' })).toBeInTheDocument();
    expect(youtube().queryByText('갱신 필요')).not.toBeInTheDocument();
    expect(youtube().queryByText('연동 끊김')).not.toBeInTheDocument();
  });

  it('상태 조회에 실패하면 다시 시도를 내준다 — 미연동으로 오인시키지 않는다', async () => {
    stubYtStatus({ reason: 'BOOM' }, 500);
    renderWithProviders(<ChannelSettingsScreen />);

    expect(await youtube().findByRole('button', { name: '다시 시도' })).toBeInTheDocument();
    expect(youtube().queryByRole('button', { name: '연동' })).not.toBeInTheDocument();
  });

  it('channelId가 화면 어디에도 나오지 않는다 — API 경계에서 버린다', async () => {
    stubYtStatus(ytLinked('ACTIVE'));
    const { container } = renderWithProviders(<ChannelSettingsScreen />);

    await youtube().findByText('정상');
    expect(container.innerHTML).not.toContain('yt-secret-1');
  });

  it('유튜브 연동은 온보딩 플래그를 건드리지 않는다 — channelLinked는 방송 감지(치지직) 전용이다', async () => {
    stubYtStatus(ytLinked('ACTIVE'));
    renderWithProviders(<ChannelSettingsScreen />);

    await youtube().findByText('정상');
    // 치지직이 미연동(linked:false)이므로 유튜브 ACTIVE가 플래그를 세웠다면 여기서 잡힌다
    await waitFor(() => expect(useOnboardingStore.getState().channelLinked).toBe(false));
  });
});

describe('YoutubeChannelSection — 연동 시작', () => {
  it('연동을 누르면 서버가 준 동의 URL로 나간다', async () => {
    const user = userEvent.setup();
    const spy = stubYtStatus({ linked: false });
    renderWithProviders(<ChannelSettingsScreen />);

    await user.click(await youtube().findByRole('button', { name: '연동' }));

    await waitFor(() => expect(goToYoutubeConsent).toHaveBeenCalledWith(YT_AUTHORIZE_URL));
    expect(
      spy.mock.calls.filter(([url, init]) => url === YT_START_URL && init?.method === 'POST'),
    ).toHaveLength(1);
  });

  it('동의 URL 발급에 실패하면 오류를 알리고 이동하지 않는다', async () => {
    const user = userEvent.setup();
    stubFetch((url, init) => {
      if (url === YT_START_URL && init?.method === 'POST') {
        return jsonResponse(502, { reason: 'YOUTUBE_UNAVAILABLE' });
      }
      return jsonResponse(200, { linked: false });
    });
    renderWithProviders(<ChannelSettingsScreen />);

    await user.click(await youtube().findByRole('button', { name: '연동' }));

    expect(await screen.findByText('연동을 시작하지 못했어요')).toBeInTheDocument();
    expect(goToYoutubeConsent).not.toHaveBeenCalled();
  });
});

describe('YoutubeChannelSection — 연동 해제', () => {
  /** GET은 unlinked 플래그를 따라가고, DELETE 응답은 케이스가 정한다. */
  function stubUnlink(deleteStatus: number) {
    let unlinked = false;
    return stubFetch((url, init) => {
      if (url === YT_LINK_URL && init?.method === 'DELETE') {
        if (deleteStatus === 204) unlinked = true;
        return jsonResponse(deleteStatus, deleteStatus === 204 ? undefined : { reason: 'X' });
      }
      if (url === YT_LINK_URL) {
        return jsonResponse(200, unlinked ? { linked: false } : ytLinked('ACTIVE'));
      }
      return jsonResponse(200, { linked: false });
    });
  }
  const deleteCalls = (spy: ReturnType<typeof stubFetch>) =>
    spy.mock.calls.filter(([url, init]) => url === YT_LINK_URL && init?.method === 'DELETE');

  it('바로 해제하지 않는다 — 모달이 결과 3줄을 먼저 보여주고, 취소하면 요청이 안 나간다', async () => {
    const user = userEvent.setup();
    const spy = stubUnlink(204);
    renderWithProviders(<ChannelSettingsScreen />);

    await user.click(await youtube().findByRole('button', { name: '연동 해제' }));

    const dialog = within(screen.getByRole('dialog'));
    expect(dialog.getByText('유튜브 연동을 해제할까요?')).toBeInTheDocument();
    expect(
      dialog.getByText('해제하면 하이라이트 클립을 유튜브에 업로드할 수 없어요.'),
    ).toBeInTheDocument();
    expect(
      dialog.getByText('이미 업로드된 영상과 보관함 클립은 그대로 남아요.'),
    ).toBeInTheDocument();
    // 우리는 구글에 revoke를 보내지 않는다(ADR-052) — 그 사실과 지우는 곳을 결정 전에 알린다
    expect(
      dialog.getByText(/구글 계정에 준 접근 권한은 자동으로 취소되지 않아요/),
    ).toBeInTheDocument();
    expect(dialog.getByText(/myaccount\.google\.com\/permissions/)).toBeInTheDocument();
    expect(dialog.getByText('해제 후에도 언제든 다시 연동할 수 있어요.')).toBeInTheDocument();
    expect(deleteCalls(spy)).toHaveLength(0);

    await user.click(dialog.getByRole('button', { name: '취소' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(deleteCalls(spy)).toHaveLength(0);
    expect(youtube().getByText('정상')).toBeInTheDocument();
  });

  it('확인하면 해제하고 토스트로 결과와 구글 권한 안내를 준다 — 되돌리기는 없다', async () => {
    const user = userEvent.setup();
    const open = vi.spyOn(window, 'open').mockImplementation(() => null);
    const spy = stubUnlink(204);
    renderWithProviders(<ChannelSettingsScreen />);

    await user.click(await youtube().findByRole('button', { name: '연동 해제' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '연동 해제' }));

    expect(await screen.findByText('유튜브 연동을 해제했어요')).toBeInTheDocument();
    expect(screen.getByText('구글 계정에 준 권한은 남아 있어요.')).toBeInTheDocument();
    expect(deleteCalls(spy)).toHaveLength(1);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(youtube().queryByText('정상')).not.toBeInTheDocument());
    // 파괴적 결과라 되돌리기는 없고(ADR-044), 대신 구글 쪽 권한을 지우는 길을 액션으로 잇는다
    expect(screen.queryByRole('button', { name: '되돌리기' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '구글 권한 관리' }));
    expect(open).toHaveBeenCalledWith(
      'https://myaccount.google.com/permissions',
      '_blank',
      'noopener',
    );
    open.mockRestore();
  });

  it('해제에 실패하면 오류를 알리고 연동 상태를 그대로 둔다', async () => {
    const user = userEvent.setup();
    stubUnlink(500);
    renderWithProviders(<ChannelSettingsScreen />);

    await user.click(await youtube().findByRole('button', { name: '연동 해제' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '연동 해제' }));

    expect(await screen.findByText('연동 해제에 실패했어요')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(youtube().getByText('정상')).toBeInTheDocument();
  });
});
