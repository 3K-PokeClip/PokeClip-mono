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
  pairingStatus?: number;
}

/** 플러그인 화면이 부르는 엔드포인트 스텁 — rotate는 프론트에서 쓰지 않으므로 스텁에도 없다. */
function stubStreamKeyFetch({ issued = true, pairingStatus = 201 }: StubOptions = {}) {
  // 서버 ensureKey처럼 발급 성공이 키를 만든다 — invalidate 후 재조회가 발급됨으로 바뀐다
  let issuedState = issued;
  return stubFetch((url, init) => {
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
    // 라벨은 "최초 발급일" — 서버가 주는 시각이 키 생성일뿐이라 재발급 후에도 참말이다 (리뷰 #74)
    expect(
      await screen.findByText(new RegExp(`최초 발급일 ${CREATED_AT_LABEL.replace(/\./g, '\\.')}`)),
    ).toBeInTheDocument();
  });

  it('코드 원문은 화면 어디에도 다시 나오지 않는다 (ADR-019)', async () => {
    stubStreamKeyFetch();
    const { container } = renderWithProviders(<PluginSettingsScreen />);

    expect(await screen.findByText(/코드가 발급되어 있어요/)).toBeInTheDocument();
    expect(screen.queryByText(CODE_PATTERN)).not.toBeInTheDocument();
    expect(container.textContent).not.toMatch(/srt:\/\//i);
  });

  it('재발급은 확인 모달 없이 새 코드를 바로 발급한다 — rotate는 부르지 않는다', async () => {
    const spy = stubStreamKeyFetch();
    renderWithProviders(<PluginSettingsScreen />);

    fireEvent.click(await screen.findByRole('button', { name: '재발급' }));

    // 새 코드는 모달에서 1회 표시 — 카운트다운과 10분 유효 안내가 같이 붙는다 (POK-103)
    expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();
    expect(screen.getByText(/10분 동안만/)).toBeInTheDocument();
    expect(screen.getByRole('timer')).toHaveTextContent(/\d{2}:\d{2} 후 만료돼요/);
    // rotate는 프론트에서 쓰지 않는다 — 재발급이 방송 키를 건드리면 안 된다
    expect(callsTo(spy, '/api/stream-keys/rotate')).toHaveLength(0);
    expect(callsTo(spy, '/api/stream-keys/pairing-codes')).toHaveLength(1);
    // 경고(확인) 모달도 없다 — 키가 안 죽으니 경고할 위험 자체가 없다
    expect(screen.queryByText(/기존 스트림 키가 즉시 만료됩니다/)).not.toBeInTheDocument();

    // 재발급해도 최초 발급일은 그대로다 — 오늘로 튀었다가 재조회에 과거로 돌아오면 안 된다 (리뷰 #74)
    fireEvent.click(screen.getByRole('button', { name: '확인했어요' }));
    expect(
      await screen.findByText(new RegExp(`최초 발급일 ${CREATED_AT_LABEL.replace(/\./g, '\\.')}`)),
    ).toBeInTheDocument();
  });

  it('클라 시계가 서버와 어긋나도(과거 expiresAt) 발급 직후 만료로 보이지 않는다 (리뷰 #74)', async () => {
    // 시계가 10분 이상 빠른 기기 재현 — 서버 expiresAt이 클라 기준 이미 과거로 온다.
    // 카운트다운은 응답 수신 순간 + TTL로 앵커하므로 이 값에 흔들리면 안 된다.
    stubFetch((url, init) => {
      if (url === '/api/stream-keys/pairing-codes')
        return jsonResponse(201, {
          code: 'KQ4M-7X2P',
          expiresAt: new Date(Date.now() - 60 * 1000).toISOString(),
        });
      if (url === '/api/stream-keys' && (init?.method ?? 'GET') === 'GET')
        return jsonResponse(200, { issued: false });
      return jsonResponse(404);
    });
    renderWithProviders(<PluginSettingsScreen />);

    fireEvent.click(await screen.findByRole('button', { name: '코드 발급' }));

    expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();
    expect(screen.getByRole('timer')).toHaveTextContent(/(10:00|09:5\d) 후 만료돼요/);
    expect(screen.queryByText('코드가 만료됐어요')).not.toBeInTheDocument();
  });

  it('미발급 상태의 첫 발급도 같은 1콜이고 온보딩 2단계를 완료로 만든다', async () => {
    const spy = stubStreamKeyFetch({ issued: false });
    renderWithProviders(<PluginSettingsScreen />);

    fireEvent.click(await screen.findByRole('button', { name: '코드 발급' }));

    expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();
    expect(callsTo(spy, '/api/stream-keys/pairing-codes')).toHaveLength(1);
    expect(useOnboardingStore.getState().pluginLinked).toBe(true);
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

  it('발급 직후 상태 재조회가 실패해도 모달을 닫으면 발급됨으로 남는다 (리뷰 #74)', async () => {
    let issuedOnce = false;
    stubFetch((url, init) => {
      if (url === '/api/stream-keys/pairing-codes') {
        issuedOnce = true;
        return jsonResponse(201, {
          code: 'KQ4M-7X2P',
          expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
        });
      }
      if (url === '/api/stream-keys' && (init?.method ?? 'GET') === 'GET')
        // 발급 뒤 재조회가 일시 오류 — 낙관 캐시가 없으면 카드가 미발급으로 회귀한다
        return issuedOnce
          ? jsonResponse(500, { reason: '일시 오류' })
          : jsonResponse(200, { issued: false });
      return jsonResponse(404);
    });
    renderWithProviders(<PluginSettingsScreen />);

    fireEvent.click(await screen.findByRole('button', { name: '코드 발급' }));
    expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '확인했어요' }));

    expect(await screen.findByText(/코드가 발급되어 있어요/)).toBeInTheDocument();
    // 미발급으로 되돌아가 중복 발급을 유도하면 안 된다
    expect(screen.queryByRole('button', { name: '코드 발급' })).not.toBeInTheDocument();
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

  it('상태 조회 중 스켈레톤은 코드 박스 높이를 인라인으로 갖는다 (리뷰 #73)', () => {
    // Skeleton은 height를 항상 인라인 style로 깔아 클래스 height는 덮인다 —
    // props로 넘긴 값이 실제 인라인에 실리는지가 회귀 지점이다 (기본 16px로 렌더되던 버그)
    stubFetch(() => new Promise<Response>(() => {})); // 응답을 붙잡아 로딩 상태를 유지한다
    renderWithProviders(<PluginSettingsScreen />);

    const card = screen.getByRole('region', { name: '연동 코드' });
    const skeleton = card.querySelector<HTMLElement>('span[aria-hidden="true"]');
    expect(skeleton).not.toBeNull();
    expect(skeleton?.style.height).toBe('calc(64 * var(--pc-u))');
  });

  it('발급 요청 중엔 버튼이 라벨 없이 스피너만 보이고 비활성이다 (디자인 ②-1)', async () => {
    stubFetch((url, init) => {
      if (url === '/api/stream-keys/pairing-codes') return new Promise<Response>(() => {}); // 응답을 붙잡아 요청 중 상태를 유지한다
      if (url === '/api/stream-keys' && (init?.method ?? 'GET') === 'GET')
        return jsonResponse(200, { issued: true, createdAt: CREATED_AT });
      return jsonResponse(404);
    });
    renderWithProviders(<PluginSettingsScreen />);

    fireEvent.click(await screen.findByRole('button', { name: '재발급' }));

    // 접근 이름은 aria-label이 대신한다 — 응답까지 비활성
    const busyButton = await screen.findByRole('button', { name: '발급 요청 중' });
    expect(busyButton).toBeDisabled();
    // 라벨은 DOM에 남아 자리를 지킨다(폭 유지) — 시각은 .busyButton CSS가 숨기고,
    // 접근 이름에서는 aria-label이 이겨 '재발급'으로는 찾을 수 없어야 한다
    expect(busyButton).toHaveTextContent('재발급');
    expect(screen.queryByRole('button', { name: '재발급' })).not.toBeInTheDocument();
  });
});
