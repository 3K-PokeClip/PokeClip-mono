import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChannelSettingsScreen } from '@/features/settings/channels/ChannelSettingsScreen';
import type { ChzzkLinkStatus } from '@/api/chzzkLink';
import { useAuthStore } from '@/stores/auth';
import { useOnboardingStore } from '@/stores/onboarding';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';

const { goToChzzkConsent } = vi.hoisted(() => ({ goToChzzkConsent: vi.fn() }));

// location.assign은 jsdom이 못 흉내 낸다 — 이동은 모킹하고, 등록 주소 판정은
// chzzkOAuth.test.ts가 순수 함수로 검증한다. (googleOAuth와 같은 분리)
vi.mock('@/features/settings/channels/chzzkOAuth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/settings/channels/chzzkOAuth')>()),
  goToChzzkConsent,
}));

const LINK_URL = '/api/chzzk-link';
const START_URL = '/api/chzzk-link/start';

/** GET /api/chzzk-link 응답만 갈아끼우는 스텁 — 나머지는 케이스에서 덧붙인다. */
function stubLinkStatus(body: unknown, status = 200) {
  return stubFetch((url, init) => {
    if (url === START_URL && init?.method === 'POST') {
      return jsonResponse(200, { authorizeUrl: 'https://chzzk.naver.com/account-interlock?x=1' });
    }
    return jsonResponse(status, body);
  });
}

const linked = (status: ChzzkLinkStatus, extra: Record<string, unknown> = {}) => ({
  linked: status === 'ACTIVE' || status === 'EXPIRED',
  status,
  channelId: 'chan-secret-1',
  channelName: '게임하는너구리',
  linkedAt: '2026-08-21T03:00:00Z',
  ...extra,
});

/** 채널 행 안으로 범위를 좁힌다 — 「연동」 버튼은 행마다 있어 이름만으로는 구분되지 않는다. */
const row = (name: string) => within(screen.getByRole('group', { name }));
const chzzk = () => row('치지직');

beforeEach(() => {
  window.localStorage.clear();
  goToChzzkConsent.mockReset();
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

describe('ChannelSettingsScreen — 연동 상태 표시', () => {
  it('연동이 없으면 연동 버튼만 보인다 — 배지도 채널명도 없다', async () => {
    stubLinkStatus({ linked: false });
    renderWithProviders(<ChannelSettingsScreen />);

    expect(screen.getByRole('heading', { name: '채널 연동' })).toBeInTheDocument();
    expect(await chzzk().findByRole('button', { name: '연동' })).toBeInTheDocument();
    expect(screen.queryByText('정상')).not.toBeInTheDocument();
    expect(screen.queryByText(/게임하는너구리/)).not.toBeInTheDocument();
  });

  it('ACTIVE는 정상 배지와 채널명 한 줄을 보여준다 (1k)', async () => {
    stubLinkStatus(linked('ACTIVE'));
    renderWithProviders(<ChannelSettingsScreen />);

    expect(await screen.findByText('정상')).toBeInTheDocument();
    // 1k의 보조설명은 채널명 한 줄이다 — 연동일·팔로워를 덧붙이지 않는다
    expect(chzzk().getByText('게임하는너구리')).toBeInTheDocument();
  });

  it('EXPIRED는 갱신 필요로 알리고 다시 연동을 내준다', async () => {
    stubLinkStatus(linked('EXPIRED'));
    renderWithProviders(<ChannelSettingsScreen />);

    expect(await screen.findByText('갱신 필요')).toBeInTheDocument();
    expect(chzzk().getByRole('button', { name: '다시 연동' })).toBeInTheDocument();
    expect(screen.getByText(/하이라이트 감지가 멈출 수 있어요/)).toBeInTheDocument();
    // 살아 있는 행이라 해제도 실제로 먹는다
    expect(chzzk().getByRole('button', { name: '연동 해제' })).toBeInTheDocument();
  });

  it('BROKEN은 끊김을 조용히 삼키지 않고 다시 연동을 내준다', async () => {
    stubLinkStatus(linked('BROKEN', { linked: false }));
    renderWithProviders(<ChannelSettingsScreen />);

    expect(await screen.findByText('연동 끊김')).toBeInTheDocument();
    expect(chzzk().getByRole('button', { name: '다시 연동' })).toBeInTheDocument();
    // 서버 기준 이미 닫힌 행이라 DELETE가 무동작이다 — 눌러도 안 되는 버튼을 두지 않는다
    expect(chzzk().queryByRole('button', { name: '연동 해제' })).not.toBeInTheDocument();
  });

  it('UNLINKED는 미연동과 같게 그린다 — 해제한 채널 이름을 남기지 않는다', async () => {
    stubLinkStatus(linked('UNLINKED', { linked: false }));
    renderWithProviders(<ChannelSettingsScreen />);

    expect(await chzzk().findByRole('button', { name: '연동' })).toBeInTheDocument();
    expect(screen.queryByText(/게임하는너구리/)).not.toBeInTheDocument();
  });

  it('상태 조회에 실패하면 다시 시도를 내준다 — 미연동으로 오인시키지 않는다', async () => {
    stubLinkStatus({ reason: 'BOOM' }, 500);
    renderWithProviders(<ChannelSettingsScreen />);

    expect(await screen.findByRole('button', { name: '다시 시도' })).toBeInTheDocument();
    expect(chzzk().queryByRole('button', { name: '연동' })).not.toBeInTheDocument();
  });

  it('channelId가 화면 어디에도 나오지 않는다 — API 경계에서 버린다', async () => {
    stubLinkStatus(linked('ACTIVE'));
    const { container } = renderWithProviders(<ChannelSettingsScreen />);

    await screen.findByText('정상');
    expect(container.innerHTML).not.toContain('chan-secret-1');
  });
});

describe('ChannelSettingsScreen — 연동 시작', () => {
  it('연동을 누르면 서버가 준 동의 URL로 나간다', async () => {
    const user = userEvent.setup();
    const spy = stubLinkStatus({ linked: false });
    renderWithProviders(<ChannelSettingsScreen />);

    await user.click(await chzzk().findByRole('button', { name: '연동' }));

    await waitFor(() =>
      expect(goToChzzkConsent).toHaveBeenCalledWith(
        'https://chzzk.naver.com/account-interlock?x=1',
      ),
    );
    expect(
      spy.mock.calls.filter(([url, init]) => url === START_URL && init?.method === 'POST'),
    ).toHaveLength(1);
  });

  it('동의 URL 발급에 실패하면 오류를 알리고 이동하지 않는다', async () => {
    const user = userEvent.setup();
    stubFetch((url, init) => {
      if (url === START_URL && init?.method === 'POST') return jsonResponse(502, { reason: 'X' });
      return jsonResponse(200, { linked: false });
    });
    renderWithProviders(<ChannelSettingsScreen />);

    await user.click(await chzzk().findByRole('button', { name: '연동' }));

    expect(await screen.findByText('연동을 시작하지 못했어요')).toBeInTheDocument();
    expect(goToChzzkConsent).not.toHaveBeenCalled();
  });
});

describe('ChannelSettingsScreen — 온보딩·다른 플랫폼', () => {
  it('시작 가이드 1단계 플래그가 서버의 linked를 그대로 따라간다', async () => {
    stubLinkStatus(linked('ACTIVE'));
    renderWithProviders(<ChannelSettingsScreen />);

    await screen.findByText('정상');
    await waitFor(() => expect(useOnboardingStore.getState().channelLinked).toBe(true));
  });

  it('BROKEN이면 플래그가 서지 않는다 — 감지가 실제로 안 되는 상태다', async () => {
    useOnboardingStore.setState({ channelLinked: true });
    stubLinkStatus(linked('BROKEN', { linked: false }));
    renderWithProviders(<ChannelSettingsScreen />);

    await screen.findByText('연동 끊김');
    await waitFor(() => expect(useOnboardingStore.getState().channelLinked).toBe(false));
  });

  it('백엔드가 없는 SOOP·유튜브는 자리만 있고 누를 수 없다 — 있는 척하지 않는다', async () => {
    stubLinkStatus({ linked: false });
    renderWithProviders(<ChannelSettingsScreen />);

    await chzzk().findByRole('button', { name: '연동' });
    expect(row('SOOP').getByRole('button', { name: '연동' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '계정 추가 연동' })).toBeDisabled();
    // 치지직 쪽은 반대로 눌리는 상태여야 한다 — 전부 비활성이면 이 케이스가 무의미해진다
    expect(chzzk().getByRole('button', { name: '연동' })).toBeEnabled();
  });

  it('유튜브가 안 붙었다는 사실이 화면에 드러난다 — 배지·문구·비활성 버튼 세 겹', async () => {
    stubLinkStatus({ linked: false });
    renderWithProviders(<ChannelSettingsScreen />);

    const youtube = row('유튜브');
    expect(youtube.getByText('준비 중')).toBeInTheDocument();
    expect(youtube.getByText(/클립 업로드 연동은 준비 중이에요/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '계정 추가 연동' })).toBeDisabled();
    // 1k의 구획 이름 그대로 — 방송 채널(감지)과 자리를 가른다
    expect(screen.getByRole('heading', { name: '유튜브 계정' })).toBeInTheDocument();
    // 재연동 안내는 이벤트 기반이다 — 평소엔 경고 UI 자체가 없다. 1k의 안내 문구는
    // "언제 뜨는지"를 설명할 뿐이라 「재연동 필요」 배지·「다시 연동」 버튼으로 판정한다.
    expect(youtube.queryByText('재연동 필요')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '다시 연동' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '재인증' })).not.toBeInTheDocument();
  });

  it('접근성 위반이 없다', async () => {
    stubLinkStatus(linked('ACTIVE'));
    const { container } = renderWithProviders(<ChannelSettingsScreen />);

    await screen.findByText('정상');
    expect(await axe(container)).toHaveNoViolations();
  });
});

describe('ChannelSettingsScreen — 연동 해제', () => {
  /** GET은 unlinked 플래그를 따라가고, DELETE 응답은 케이스가 정한다. */
  function stubUnlink(deleteStatus: number) {
    let unlinked = false;
    return stubFetch((url, init) => {
      if (url === LINK_URL && init?.method === 'DELETE') {
        if (deleteStatus === 204) unlinked = true;
        return jsonResponse(deleteStatus, deleteStatus === 204 ? undefined : { reason: 'X' });
      }
      return jsonResponse(200, unlinked ? { linked: false } : linked('ACTIVE'));
    });
  }
  const deleteCalls = (spy: ReturnType<typeof stubFetch>) =>
    spy.mock.calls.filter(([url, init]) => url === LINK_URL && init?.method === 'DELETE');

  it('바로 해제하지 않는다 — 모달이 결과 3줄을 먼저 보여주고, 취소하면 요청이 안 나간다', async () => {
    const user = userEvent.setup();
    const spy = stubUnlink(204);
    renderWithProviders(<ChannelSettingsScreen />);

    await user.click(await chzzk().findByRole('button', { name: '연동 해제' }));

    const dialog = within(screen.getByRole('dialog'));
    expect(dialog.getByText('치지직 연동 해제')).toBeInTheDocument();
    expect(dialog.getByText('치지직 연동을 해제할까요?')).toBeInTheDocument();
    expect(
      dialog.getByText('해제하면 방송을 켜도 하이라이트를 감지하지 않아요.'),
    ).toBeInTheDocument();
    expect(
      dialog.getByText('이미 저장된 지난 방송과 보관함 클립은 그대로 남아요.'),
    ).toBeInTheDocument();
    expect(dialog.getByText('진행 중인 감지·클립 작업은 즉시 중단됩니다.')).toBeInTheDocument();
    expect(dialog.getByText('해제 후에도 언제든 다시 연동할 수 있어요.')).toBeInTheDocument();
    expect(deleteCalls(spy)).toHaveLength(0);

    await user.click(dialog.getByRole('button', { name: '취소' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(deleteCalls(spy)).toHaveLength(0);
    expect(screen.getByText('정상')).toBeInTheDocument();
  });

  it('확인하면 해제하고 결과를 토스트로만 알린다 — 되돌리기를 붙이지 않는다', async () => {
    const user = userEvent.setup();
    const spy = stubUnlink(204);
    renderWithProviders(<ChannelSettingsScreen />);

    await user.click(await chzzk().findByRole('button', { name: '연동 해제' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '연동 해제' }));

    expect(await screen.findByText('치지직 연동을 해제했어요')).toBeInTheDocument();
    expect(deleteCalls(spy)).toHaveLength(1);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(screen.queryByText('정상')).not.toBeInTheDocument());
    expect(screen.queryByRole('button', { name: '되돌리기' })).not.toBeInTheDocument();
    await waitFor(() => expect(useOnboardingStore.getState().channelLinked).toBe(false));
  });

  it('해제에 실패하면 오류를 알리고 연동 상태를 그대로 둔다', async () => {
    const user = userEvent.setup();
    stubUnlink(500);
    renderWithProviders(<ChannelSettingsScreen />);

    await user.click(await chzzk().findByRole('button', { name: '연동 해제' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '연동 해제' }));

    expect(await screen.findByText('연동 해제에 실패했어요')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.getByText('정상')).toBeInTheDocument();
  });
});
