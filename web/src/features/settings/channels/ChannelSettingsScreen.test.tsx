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

const { goToChzzkConsent, goToYoutubeConsent } = vi.hoisted(() => ({
  goToChzzkConsent: vi.fn(),
  goToYoutubeConsent: vi.fn(),
}));

// location.assign은 jsdom이 못 흉내 낸다 — 이동은 모킹하고, 등록 주소 판정은
// chzzkOAuth.test.ts가 순수 함수로 검증한다. (googleOAuth와 같은 분리)
vi.mock('@/features/settings/channels/chzzkOAuth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/settings/channels/chzzkOAuth')>()),
  goToChzzkConsent,
}));
vi.mock('@/features/settings/channels/youtubeOAuth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/settings/channels/youtubeOAuth')>()),
  goToYoutubeConsent,
}));

const LINK_URL = '/api/chzzk-link';
const START_URL = '/api/chzzk-link/start';
const YT_LINK_URL = '/api/youtube-link';
const YT_START_URL = '/api/youtube-link/start';
// warnIfCallbackMismatch는 모킹하지 않고 원본을 태운다 — 등록 주소가 맞는 동의 URL이면
// 조용해야 하고, 그 성질 자체가 회귀 대상이다. redirectUri를 빼면 케이스마다 경고가 찍혀
// 진짜 회귀 경고와 구분되지 않는다. (jsdom 기본 오리진이 localhost:3000이다)
const AUTHORIZE_URL = `https://chzzk.naver.com/account-interlock?clientId=cid&redirectUri=${encodeURIComponent(
  'http://localhost:3000/oauth/chzzk/callback',
)}&state=signed`;
// 유튜브(구글)는 표준 OAuth라 파라미터가 snake_case다 — camelCase로 만들면
// warnIfCallbackMismatch가 케이스마다 경고를 찍는다.
const YT_AUTHORIZE_URL = `https://accounts.google.com/o/oauth2/v2/auth?client_id=cid&redirect_uri=${encodeURIComponent(
  'http://localhost:3000/oauth/youtube/callback',
)}&state=signed`;

/**
 * 플랫폼별 GET 응답을 갈아끼우는 스텁 — 치지직·유튜브가 같은 화면에 사니 URL로 갈라야
 * 한다. 한 본문을 양쪽에 주면 「연동 끊김」·「다시 시도」 같은 문구가 두 행에 동시에 떠
 * 단일 매치 단언이 깨진다.
 */
function stubLinkStatus(
  body: unknown,
  status = 200,
  ytBody: unknown = { linked: false },
  ytStatus = 200,
) {
  return stubFetch((url, init) => {
    if (url === START_URL && init?.method === 'POST') {
      return jsonResponse(200, { authorizeUrl: AUTHORIZE_URL });
    }
    if (url === YT_START_URL && init?.method === 'POST') {
      return jsonResponse(200, { authorizeUrl: YT_AUTHORIZE_URL });
    }
    if (url === YT_LINK_URL) return jsonResponse(ytStatus, ytBody);
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

    await waitFor(() => expect(goToChzzkConsent).toHaveBeenCalledWith(AUTHORIZE_URL));
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

  it('스토어가 서기 전 직접 진입해도 저장된 stale 플래그를 서버 진실로 덮는다', async () => {
    // /settings/channels로 바로 들어오는 경로 — 온보딩 스토어를 세우는 OnboardingController는
    // /home에만 붙어서 여기선 안 돈다. 훅이 스스로 hydrate하지 않으면 정적 기본값(false)과
    // 비교하게 되고, 저장된 true가 그대로 남아 나중에 시작 가이드에서 되살아난다.
    window.localStorage.setItem(
      'pc-onboarding',
      JSON.stringify({
        v: 1,
        welcomeSeen: true,
        tourDone: true,
        channelLinked: true,
        pluginLinked: false,
      }),
    );
    useOnboardingStore.setState({ channelLinked: false, hydrated: false });
    stubLinkStatus(linked('BROKEN', { linked: false }));
    renderWithProviders(<ChannelSettingsScreen />);

    await screen.findByText('연동 끊김');
    // 스토어 값만 보면 기본값 false와 구분되지 않는다 — 저장소에 남은 값으로 판정한다
    await waitFor(() =>
      expect(JSON.parse(window.localStorage.getItem('pc-onboarding') ?? '{}').channelLinked).toBe(
        false,
      ),
    );
  });

  it('BROKEN이면 플래그가 서지 않는다 — 감지가 실제로 안 되는 상태다', async () => {
    useOnboardingStore.setState({ channelLinked: true });
    stubLinkStatus(linked('BROKEN', { linked: false }));
    renderWithProviders(<ChannelSettingsScreen />);

    await screen.findByText('연동 끊김');
    await waitFor(() => expect(useOnboardingStore.getState().channelLinked).toBe(false));
  });

  it('백엔드가 없는 SOOP은 자리만 있고 누를 수 없다 — 있는 척하지 않는다', async () => {
    stubLinkStatus({ linked: false });
    renderWithProviders(<ChannelSettingsScreen />);

    await chzzk().findByRole('button', { name: '연동' });
    expect(row('SOOP').getByRole('button', { name: '연동' })).toBeDisabled();
    // 실배선된 두 행은 반대로 눌리는 상태여야 한다 — 전부 비활성이면 이 케이스가 무의미해진다
    expect(chzzk().getByRole('button', { name: '연동' })).toBeEnabled();
    expect(row('유튜브').getByRole('button', { name: '연동' })).toBeEnabled();
  });

  it('유튜브가 실배선됐다 — 준비 중 세 겹과 다계정 UI가 없고 연동이 눌린다 (POK-221)', async () => {
    stubLinkStatus({ linked: false });
    renderWithProviders(<ChannelSettingsScreen />);

    const youtube = row('유튜브');
    expect(await youtube.findByRole('button', { name: '연동' })).toBeEnabled();
    // POK-205의 「준비 중」 세 겹이 전부 걷혔다
    expect(youtube.queryByText('준비 중')).not.toBeInTheDocument();
    expect(youtube.queryByText(/클립 업로드 연동은 준비 중이에요/)).not.toBeInTheDocument();
    // 「계정 추가 연동」은 비활성이 아니라 **부재**다 — 다계정 미지원 확정(ADR-052)으로
    // 시안 1k의 다계정 UI를 넣지 않는다. 채널 변경은 해제 후 재연동 안내가 대신한다.
    expect(screen.queryByRole('button', { name: '계정 추가 연동' })).not.toBeInTheDocument();
    expect(screen.getByText(/연동을 해제한 뒤 다시 연동/)).toBeInTheDocument();
    // 1k의 구획 이름 그대로 — 방송 채널(감지)과 자리를 가른다
    expect(screen.getByRole('heading', { name: '유튜브 계정' })).toBeInTheDocument();
    // 재연동 안내는 이벤트 기반이다 — 평시(미연동)엔 경고 UI 자체가 없다 (POK-205 규약 승계)
    expect(youtube.queryByText('연동 끊김')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '다시 연동' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '재인증' })).not.toBeInTheDocument();
  });

  it('접근성 위반이 없다', async () => {
    stubLinkStatus(linked('ACTIVE'), 200, {
      linked: true,
      status: 'ACTIVE',
      channelId: 'yt-secret-1',
      channelName: '포켓클립 채널',
    });
    const { container } = renderWithProviders(<ChannelSettingsScreen />);

    // 두 행이 모두 활성(배지·채널명·해제 버튼)인 상태로 검사한다
    await screen.findByText('게임하는너구리');
    await screen.findByText('포켓클립 채널');
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
      // 유튜브 행은 이 묶음의 관심 밖 — 미연동으로 눕혀 「정상」·「연동 해제」가
      // 치지직 행에서만 나오게 한다 (단일 매치 단언의 전제).
      if (url === YT_LINK_URL) return jsonResponse(200, { linked: false });
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
    // 카테고리 라벨(eyebrow)은 없다 — 1k 갱신 시안이 제목만 남긴다
    expect(dialog.queryByText('치지직 연동 해제')).not.toBeInTheDocument();
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
