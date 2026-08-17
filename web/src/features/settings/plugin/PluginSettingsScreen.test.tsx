import { fireEvent, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PluginSettingsScreen } from '@/features/settings/plugin/PluginSettingsScreen';
import { useAuthStore } from '@/stores/auth';
import { useOnboardingStore } from '@/stores/onboarding';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';

// 서버의 사람용 표기와 같은 XXXX-XXXX (Crockford Base32, PairingCodeService.format)
const CODE_PATTERN = /^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/;
const CREATED_AT = '2026-08-02T03:00:00Z';
// 기대 날짜는 실행 환경 타임존으로 같이 계산한다 — CI 타임존이 달라도 어긋나지 않는다
const CREATED_AT_LABEL = new Date(CREATED_AT).toLocaleDateString('ko-KR');

interface StubOptions {
  issued?: boolean;
  rotateStatus?: number;
  pairingStatus?: number;
}

/** 플러그인 화면이 부르는 세 엔드포인트 스텁 — 호출 검증은 반환된 spy로. */
function stubStreamKeyFetch({
  issued = true,
  rotateStatus = 200,
  pairingStatus = 201,
}: StubOptions = {}) {
  return stubFetch((url, init) => {
    if (url === '/api/stream-keys/rotate')
      return rotateStatus === 200
        ? jsonResponse(200, { rotatedAt: '2026-08-17T03:00:00Z' })
        : jsonResponse(rotateStatus, { reason: '폐기할 키가 없다' });
    if (url === '/api/stream-keys/pairing-codes')
      return pairingStatus === 201
        ? jsonResponse(201, { code: 'KQ4M-7X2P', expiresAt: '2026-08-17T03:10:00Z' })
        : jsonResponse(pairingStatus, { reason: '발급 한도 초과' });
    if (url === '/api/stream-keys' && (init?.method ?? 'GET') === 'GET')
      return jsonResponse(
        200,
        issued ? { issued: true, createdAt: CREATED_AT } : { issued: false },
      );
    return jsonResponse(404);
  });
}

function callsTo(spy: ReturnType<typeof stubFetch>, url: string) {
  return spy.mock.calls.filter(([calledUrl]) => calledUrl === url);
}

beforeEach(() => {
  window.localStorage.clear();
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

describe('PluginSettingsScreen', () => {
  it('디자인 1m의 블록 세 개를 렌더하고 발급 상태를 서버에서 읽는다', async () => {
    stubStreamKeyFetch();
    renderWithProviders(<PluginSettingsScreen />);

    expect(screen.getByRole('region', { name: '플러그인 연결 상태' })).toHaveTextContent('연결됨');
    expect(screen.getByRole('heading', { name: '연동 코드' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '플러그인 다운로드' })).toBeInTheDocument();
    expect(
      await screen.findByText(new RegExp(`발행일 ${CREATED_AT_LABEL.replace(/\./g, '\\.')}`)),
    ).toBeInTheDocument();
  });

  it('코드 원문은 화면 어디에도 다시 나오지 않는다 (ADR-019)', async () => {
    stubStreamKeyFetch();
    const { container } = renderWithProviders(<PluginSettingsScreen />);

    expect(await screen.findByText(/보안을 위해 코드는 다시 표시되지 않아요/)).toBeInTheDocument();
    expect(screen.queryByText(CODE_PATTERN)).not.toBeInTheDocument();
    expect(container.textContent).not.toMatch(/srt:\/\//i);
  });

  it('재발급은 즉시 만료 경고 모달을 거쳐 rotate 후 새 코드를 발급한다 (POK-102 완료조건)', async () => {
    const spy = stubStreamKeyFetch();
    renderWithProviders(<PluginSettingsScreen />);

    fireEvent.click(await screen.findByRole('button', { name: '재발급' }));

    // 필수 경고 문구 — 기존 키 즉시 만료 + 방송 끊김
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('기존 코드와 스트림 키가 즉시 만료됩니다');
    expect(dialog).toHaveTextContent('방송 중이라면 송출이 끊겨요');
    // 경고를 보기 전에는 아무 호출도 나가면 안 된다
    expect(callsTo(spy, '/api/stream-keys/rotate')).toHaveLength(0);

    fireEvent.click(screen.getByRole('button', { name: '지금 재발급' }));

    expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();
    expect(screen.getByText(/이 코드는 지금만 보여요/)).toBeInTheDocument();
    // rotate가 발급보다 먼저다 — 순서가 뒤집히면 옛 키의 코드가 나간다
    expect(callsTo(spy, '/api/stream-keys/rotate')).toHaveLength(1);
    expect(callsTo(spy, '/api/stream-keys/pairing-codes')).toHaveLength(1);
    const rotateIndex = spy.mock.calls.findIndex(([url]) => url === '/api/stream-keys/rotate');
    const issueIndex = spy.mock.calls.findIndex(
      ([url]) => url === '/api/stream-keys/pairing-codes',
    );
    expect(rotateIndex).toBeLessThan(issueIndex);
  });

  it('rotate 404(키 없음 stale)여도 발급은 진행된다', async () => {
    stubStreamKeyFetch({ rotateStatus: 404 });
    renderWithProviders(<PluginSettingsScreen />);

    fireEvent.click(await screen.findByRole('button', { name: '재발급' }));
    fireEvent.click(await screen.findByRole('button', { name: '지금 재발급' }));

    expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();
  });

  it('미발급 상태의 첫 발급은 rotate 없이 1콜이고 온보딩 2단계를 완료로 만든다', async () => {
    const spy = stubStreamKeyFetch({ issued: false });
    renderWithProviders(<PluginSettingsScreen />);

    fireEvent.click(await screen.findByRole('button', { name: '코드 발급' }));

    expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();
    expect(callsTo(spy, '/api/stream-keys/rotate')).toHaveLength(0);
    expect(callsTo(spy, '/api/stream-keys/pairing-codes')).toHaveLength(1);
    expect(useOnboardingStore.getState().pluginLinked).toBe(true);
  });

  it('발급 429는 분당 한도 안내 토스트를 띄운다 (POK-103 완료조건)', async () => {
    stubStreamKeyFetch({ issued: false, pairingStatus: 429 });
    renderWithProviders(<PluginSettingsScreen />);

    fireEvent.click(await screen.findByRole('button', { name: '코드 발급' }));

    expect(await screen.findByText('코드 발급이 잠시 제한됐어요')).toBeInTheDocument();
    expect(screen.getByText(/1분에 3회까지만 발급할 수 있어요/)).toBeInTheDocument();
    // 온보딩 완료 처리는 성공에만 걸린다
    expect(useOnboardingStore.getState().pluginLinked).toBe(false);
  });
});
