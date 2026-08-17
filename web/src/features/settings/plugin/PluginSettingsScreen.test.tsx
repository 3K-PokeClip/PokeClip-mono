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
  // 서버 ensureKey처럼 발급 성공이 키를 만든다 — invalidate 후 재조회가 발급됨으로 바뀐다
  let issuedState = issued;
  return stubFetch((url, init) => {
    if (url === '/api/stream-keys/rotate')
      return rotateStatus === 200
        ? jsonResponse(200, { rotatedAt: '2026-08-17T03:00:00Z' })
        : jsonResponse(rotateStatus, { reason: '폐기할 키가 없다' });
    if (url === '/api/stream-keys/pairing-codes') {
      if (pairingStatus !== 201) return jsonResponse(pairingStatus, { reason: '발급 한도 초과' });
      issuedState = true;
      return jsonResponse(201, {
        code: 'KQ4M-7X2P',
        // 카운트다운이 실시간 기준이라 만료 시각도 지금 기준으로 만든다
        expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
      });
    }
    if (url === '/api/stream-keys' && (init?.method ?? 'GET') === 'GET')
      return jsonResponse(
        200,
        issuedState ? { issued: true, createdAt: CREATED_AT } : { issued: false },
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

    // 필수 경고 문구 — 기존 키 즉시 만료 + 방송 끊김. "기존 코드 무효화"는 약속하지
    // 않는다 — rotate가 미사용 페어링 코드를 못 죽이므로 거짓 보장이다 (리뷰 #73)
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('기존 스트림 키가 즉시 만료됩니다');
    expect(dialog).toHaveTextContent('방송 중이라면 송출이 끊겨요');
    expect(dialog).not.toHaveTextContent('기존 코드');
    // 경고를 보기 전에는 아무 호출도 나가면 안 된다
    expect(callsTo(spy, '/api/stream-keys/rotate')).toHaveLength(0);

    fireEvent.click(screen.getByRole('button', { name: '지금 재발급' }));

    // 새 코드는 모달에서 1회 표시 — 카운트다운과 1회 노출 경고가 같이 붙는다 (POK-103)
    expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();
    expect(screen.getByText(/한 번만/)).toBeInTheDocument();
    expect(screen.getByRole('timer')).toHaveTextContent(/\d{2}:\d{2} 후 만료돼요/);
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

  it('부분 실패(rotate 성공→발급 429) 후 재시도는 키를 다시 회전하지 않는다 (리뷰 #73)', async () => {
    let pairingCalls = 0;
    const spy = stubFetch((url, init) => {
      if (url === '/api/stream-keys/rotate')
        return jsonResponse(200, { rotatedAt: '2026-08-17T03:00:00Z' });
      if (url === '/api/stream-keys/pairing-codes') {
        pairingCalls += 1;
        return pairingCalls === 1
          ? jsonResponse(429, { reason: '발급 한도 초과' })
          : jsonResponse(201, {
              code: 'KQ4M-7X2P',
              expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
            });
      }
      if (url === '/api/stream-keys' && (init?.method ?? 'GET') === 'GET')
        return jsonResponse(200, { issued: true, createdAt: CREATED_AT });
      return jsonResponse(404);
    });
    renderWithProviders(<PluginSettingsScreen />);

    fireEvent.click(await screen.findByRole('button', { name: '재발급' }));
    fireEvent.click(await screen.findByRole('button', { name: '지금 재발급' }));

    // 발급 실패만 말하면 안 된다 — 키는 이미 회전됐다
    expect(await screen.findByText('기존 키는 이미 만료됐어요')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '재발급' }));
    fireEvent.click(await screen.findByRole('button', { name: '지금 재발급' }));

    expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();
    // 회전은 첫 시도의 1회뿐 — 재시도가 방송 키를 또 죽이면 안 된다
    expect(callsTo(spy, '/api/stream-keys/rotate')).toHaveLength(1);
    expect(callsTo(spy, '/api/stream-keys/pairing-codes')).toHaveLength(2);
  });

  it('상태 조회를 한 번도 못 읽으면 미발급이 아니라 오류·다시 시도를 보여준다 (리뷰 #73)', async () => {
    let statusCalls = 0;
    stubFetch((url, init) => {
      if (url === '/api/stream-keys' && (init?.method ?? 'GET') === 'GET') {
        statusCalls += 1;
        return statusCalls === 1
          ? jsonResponse(500, { reason: '서버 오류' })
          : jsonResponse(200, { issued: true, createdAt: CREATED_AT });
      }
      return jsonResponse(404);
    });
    renderWithProviders(<PluginSettingsScreen />);

    expect(await screen.findByText(/연동 코드 상태를 불러오지 못했어요/)).toBeInTheDocument();
    // 키 있는 사용자에게 "발급" 버튼을 보여주면 안 된다
    expect(screen.queryByRole('button', { name: '코드 발급' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));

    expect(await screen.findByText(/코드가 발급되어 있어요/)).toBeInTheDocument();
  });

  it('모달을 닫으면 코드 원문은 어디에도 다시 나오지 않는다 (ADR-019)', async () => {
    stubStreamKeyFetch({ issued: false });
    const { container } = renderWithProviders(<PluginSettingsScreen />);

    fireEvent.click(await screen.findByRole('button', { name: '코드 발급' }));
    expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '확인했어요' }));

    expect(screen.queryByText('KQ4M-7X2P')).not.toBeInTheDocument();
    expect(container.textContent).not.toContain('KQ4M-7X2P');
    // 카드는 발급됨 상태로 남는다 — 원문 없이
    expect(await screen.findByText(/코드가 발급되어 있어요/)).toBeInTheDocument();
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
