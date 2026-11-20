import { QueryClient, focusManager } from '@tanstack/react-query';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PAIRING_CODE_TTL_MS } from '@/api/streamKeys';
import {
  PluginSettingsScreen,
  STATUS_SLOW_MS,
} from '@/features/settings/plugin/PluginSettingsScreen';
import { PAIRING_WATCH_MS } from '@/features/settings/plugin/useStreamKeyState';
import { PLUGIN_DOWNLOADS } from '@/lib/pluginDownload';
import { useAuthStore } from '@/stores/auth';
import { useOnboardingStore } from '@/stores/onboarding';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';

const { track } = vi.hoisted(() => ({ track: vi.fn() }));
vi.mock('@/lib/analytics', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/analytics')>()),
  track,
}));

// 서버의 사람용 표기와 같은 XXXX-XXXX (Crockford Base32, PairingCodeService.format)
const CODE_PATTERN = /^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/;
const CREATED_AT = '2026-08-02T03:00:00Z';
// 연결 시각(lastPairedAt) — 코드가 OBS에 들어간 때다. 발급일과 다른 날로 둬 두 날짜가 섞이면 드러나게 한다
const PAIRED_AT = '2026-08-03T04:00:00Z';
// 기대 날짜는 실행 환경 타임존으로 같이 계산한다 — CI 타임존이 달라도 어긋나지 않는다
const PAIRED_AT_LABEL = new Date(PAIRED_AT).toLocaleDateString('ko-KR');
const PAIRED_LINE = new RegExp(`마지막 연결 ${PAIRED_AT_LABEL.replace(/\./g, '\\.')}`);

interface StubOptions {
  /** 코드를 받은 적이 있나(키 존재) */
  issued?: boolean;
  /** 플러그인에 코드를 넣은 적이 있나(응답에 lastPairedAt이 실린다). 기본은 「코드만 받았다」 */
  paired?: boolean;
  pairingStatus?: number;
}

/**
 * 플러그인 화면이 부르는 엔드포인트 스텁 — rotate는 프론트에서 쓰지 않으므로 스텁에도 없다.
 * `pairNow`는 「코드가 OBS에 들어갔다」를 만든다: 서버의 교환(exchange)이 연결 시각을 적는 것과 같다.
 */
function stubStreamKeyFetch({
  issued = true,
  paired = false,
  pairingStatus = 201,
}: StubOptions = {}) {
  // 서버 ensureKey처럼 발급 성공이 키를 만든다 — invalidate 후 재조회가 발급됨으로 바뀐다
  let issuedState = issued;
  let lastPairedAt: string | undefined = paired ? PAIRED_AT : undefined;
  const spy = stubFetch((url, init) => {
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
        // 서버는 값이 없는 칸을 아예 싣지 않는다(NON_NULL) — JSON.stringify가 undefined를 빼 주어 같은 모양이 된다
        issuedState ? { issued: true, createdAt: CREATED_AT, lastPairedAt } : { issued: false },
      );
    return jsonResponse(404);
  });
  return Object.assign(spy, {
    pairNow(at = new Date().toISOString()) {
      lastPairedAt = at;
    },
  });
}

function callsTo(spy: ReturnType<typeof stubFetch>, url: string) {
  return spy.mock.calls.filter(([calledUrl]) => calledUrl === url);
}

/** 상태(GET /api/stream-keys)를 몇 번 물었나 */
function statusCalls(spy: ReturnType<typeof stubFetch>) {
  return callsTo(spy, '/api/stream-keys').length;
}

/** 다시 읽는 주기 하나를 돌리고 응답까지 act 안에서 받는다(가짜 타이머 — 렌더 전에 켠다) */
async function passTime(queryClient: QueryClient, ms = PAIRING_WATCH_MS + 100) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
    await vi.waitUntil(() => queryClient.isFetching() === 0);
  });
}

/** 연결 순서 카드에서 코드를 받는다 — 2번 「다시 켰어요」를 눌러야 3번 「코드 받기」가 열린다 */
async function issueFromSteps() {
  fireEvent.click(await screen.findByRole('button', { name: '다시 켰어요' }));
  fireEvent.click(screen.getByRole('button', { name: '코드 받기' }));
}

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  track.mockReset();
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
  vi.useRealTimers();
});

describe('PluginSettingsScreen', () => {
  describe('플러그인을 연결한 회원', () => {
    it('연동 코드 카드와 맨 아래 배너를 본다 — 연결은 서버가 준 시각으로만 말한다', async () => {
      stubStreamKeyFetch({ paired: true });
      renderWithProviders(<PluginSettingsScreen />);

      // 웹은 플러그인이 지금 붙어 있는지 모른다 — 시안의 연결 상태 배너도 「연결 진단」 버튼도 두지 않는다(ADR-077 결정 4)
      expect(screen.queryByRole('region', { name: '플러그인 연결 상태' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: '연결 진단' })).not.toBeInTheDocument();
      expect(screen.getByRole('heading', { name: '연동 코드' })).toBeInTheDocument();
      // 아는 것은 「코드가 쓰인 시각」뿐이라 「연결됨」 배지가 아니라 「연결했어요 + 그 날짜」로 적는다(POK-608)
      expect(await screen.findByText('OBS 플러그인을 연결했어요')).toBeInTheDocument();
      expect(screen.getByText(PAIRED_LINE)).toBeInTheDocument();
      expect(document.body).not.toHaveTextContent(/연결됨|연결 안 됨/);
      // 다른 PC에 더 붙일 때의 다운로드는 맨 아래 배너다 — 연결 순서 카드는 없다
      expect(screen.getByText(/다른 PC에 설치하나요/)).toBeInTheDocument();
      expect(screen.queryByRole('region', { name: 'OBS 플러그인 연결' })).not.toBeInTheDocument();
    });

    it('코드 원문은 화면 어디에도 다시 나오지 않는다 (ADR-019)', async () => {
      stubStreamKeyFetch({ paired: true });
      const { container } = renderWithProviders(<PluginSettingsScreen />);

      expect(await screen.findByText('OBS 플러그인을 연결했어요')).toBeInTheDocument();
      expect(screen.queryByText(CODE_PATTERN)).not.toBeInTheDocument();
      expect(container.textContent).not.toMatch(/srt:\/\//i);
    });

    it('재발급은 확인 모달 없이 새 코드를 바로 발급한다 — rotate는 부르지 않는다', async () => {
      const spy = stubStreamKeyFetch({ paired: true });
      renderWithProviders(<PluginSettingsScreen />);

      fireEvent.click(await screen.findByRole('button', { name: '재발급' }));

      // 새 코드는 모달에서 1회 표시 — 카운트다운과 10분 유효 안내가 같이 붙는다 (POK-103)
      expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();
      expect(screen.getByText(/10분 동안만/)).toBeInTheDocument();
      expect(screen.getByRole('timer')).toHaveTextContent(/\d{2}:\d{2} 후 만료돼요/);
      // rotate는 프론트에서 쓰지 않는다 — 재발급이 방송 키를 건드리면 이미 붙은 OBS가 끊긴다
      expect(callsTo(spy, '/api/stream-keys/rotate')).toHaveLength(0);
      expect(callsTo(spy, '/api/stream-keys/pairing-codes')).toHaveLength(1);
      // 경고(확인) 모달도 없다 — 키가 안 죽으니 경고할 위험 자체가 없다
      expect(screen.queryByText(/기존 스트림 키가 즉시 만료됩니다/)).not.toBeInTheDocument();

      // 코드를 다시 받았을 뿐 넣지는 않았다 — 마지막 연결 날짜는 그대로다
      fireEvent.click(screen.getByRole('button', { name: '확인했어요' }));
      expect(await screen.findByText(PAIRED_LINE)).toBeInTheDocument();
    });

    it('발급 요청 중엔 버튼이 라벨 없이 스피너만 보이고 비활성이다 (디자인 ②-1)', async () => {
      stubFetch((url, init) => {
        if (url === '/api/stream-keys/pairing-codes') return new Promise<Response>(() => {}); // 응답을 붙잡아 요청 중 상태를 유지한다
        if (url === '/api/stream-keys' && (init?.method ?? 'GET') === 'GET')
          return jsonResponse(200, {
            issued: true,
            createdAt: CREATED_AT,
            lastPairedAt: PAIRED_AT,
          });
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

    it('배너 → 창에서 OS별 고정 주소의 파일을 받는다 — 설치·연결 상태는 지어내지 않는다 (POK-379)', async () => {
      stubStreamKeyFetch({ paired: true });
      renderWithProviders(<PluginSettingsScreen />);

      fireEvent.click(await screen.findByRole('button', { name: '플러그인 다운로드' }));

      const dialog = await screen.findByRole('dialog', { name: '플러그인 다운로드' });
      expect(dialog).toBeInTheDocument();
      // jsdom 기본 UA는 OS를 모른다 — 감지 못 하면 Windows 탭으로 연다
      expect(screen.getByRole('tab', { name: 'Windows' })).toHaveAttribute('aria-selected', 'true');
      expect(screen.getByRole('link', { name: /Windows 설치 파일/ })).toHaveAttribute(
        'href',
        PLUGIN_DOWNLOADS.windows.url,
      );
      expect(document.body).not.toHaveTextContent(/설치됨|연결됨/);
    });

    it('다운로드 창을 열면 연 것만 한 번 센다 — 받기는 창 안 링크를 눌러야 따로 센다', async () => {
      stubStreamKeyFetch({ paired: true });
      renderWithProviders(<PluginSettingsScreen />);

      fireEvent.click(await screen.findByRole('button', { name: '플러그인 다운로드' }));
      await screen.findByRole('dialog', { name: '플러그인 다운로드' });

      // 연결한 회원이라 들어올 때 plugin_paired도 한 번 나간다(아래 「연결을 지켜본다」) — 여기서는 다운로드 이벤트만 본다
      expect(
        track.mock.calls.filter(([name]) => String(name).startsWith('plugin_download')),
      ).toEqual([['plugin_download_opened', { from: 'banner' }]]);
    });
  });

  describe('플러그인을 연결한 적 없는 회원 — 연결 순서 카드 (시안 C, POK-609)', () => {
    it('설치 → OBS 다시 켜기 → 코드 순서로 서고, 「코드 받기」는 OBS를 다시 켰다고 해야 열린다', async () => {
      const spy = stubStreamKeyFetch({ issued: false });
      renderWithProviders(<PluginSettingsScreen />);

      const card = await screen.findByRole('region', { name: 'OBS 플러그인 연결' });
      expect(card).toHaveTextContent('아래 순서대로 하면 연결돼요');
      // 세 단계가 순서대로다
      const steps = Array.from(card.querySelectorAll('li')).map((li) => li.textContent);
      expect(steps).toHaveLength(3);
      expect(steps[0]).toMatch(/플러그인을 설치해요/);
      expect(steps[1]).toMatch(/OBS를 다시 켜요/);
      expect(steps[2]).toMatch(/PokeClip 창에 연동 코드를 넣어요/);
      // 예전의 「연동 코드 · 코드 발급」 카드도, 이미 설치한 사람용 배너도 없다
      expect(screen.queryByRole('heading', { name: '연동 코드' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: '코드 발급' })).not.toBeInTheDocument();
      expect(screen.queryByText(/다른 PC에 설치하나요/)).not.toBeInTheDocument();

      // 처음엔 잠겨 있다 — 새 회원 넷이 3~10초 만에 설치 전에 코드부터 받았다
      const code = screen.getByRole('button', { name: '코드 받기' });
      expect(code).toBeDisabled();
      expect(card).toHaveTextContent('2번을 마치면 받을 수 있어요');
      fireEvent.click(code);
      expect(callsTo(spy, '/api/stream-keys/pairing-codes')).toHaveLength(0);

      const restarted = screen.getByRole('button', { name: '다시 켰어요' });
      expect(restarted).not.toHaveAttribute('aria-pressed');
      fireEvent.click(restarted);

      expect(screen.getByRole('button', { name: '코드 받기' })).toBeEnabled();
      expect(card).not.toHaveTextContent('2번을 마치면 받을 수 있어요');
      // 잘못 눌렀으면 되돌린다 — 3번이 도로 잠긴다
      const undo = screen.getByRole('button', { name: '아직이에요' });
      expect(undo).not.toHaveAttribute('aria-pressed');
      fireEvent.click(undo);
      expect(screen.getByRole('button', { name: '코드 받기' })).toBeDisabled();
    });

    it('코드만 받고 떠났다 돌아온 회원도 순서 카드를 본다 — 전에 받은 코드는 다시 볼 수 없다고 말한다', async () => {
      // 2026-11-18 새 회원 둘이 코드만 받고 떠났다 — 「코드를 받은 적」으로 가르던 때는 돌아와도 배너 화면이었다
      stubStreamKeyFetch(); // 발급됨 · 연결 시각 없음
      renderWithProviders(<PluginSettingsScreen />);

      const card = await screen.findByRole('region', { name: 'OBS 플러그인 연결' });
      expect(card).toHaveTextContent('전에 받은 코드는 다시 볼 수 없어요');
      expect(screen.getByRole('button', { name: '코드 받기' })).toBeDisabled();
      expect(screen.queryByRole('button', { name: '재발급' })).not.toBeInTheDocument();
      expect(screen.queryByText(/다른 PC에 설치하나요/)).not.toBeInTheDocument();
      // 발급은 연결이 아니다 — 연결했다는 말이 어디에도 없다
      expect(document.body).not.toHaveTextContent(/연결했어요|연결됨|마지막 연결/);
    });

    it('진한 버튼은 지금 할 단계 하나에만 있고, 다운로드 → 다시 켰어요 → 코드 받기로 따라간다 (시안 A, POK-613)', async () => {
      // 2026-11-20 17·18번: 다운로드를 눌러도 진한 버튼이 「다운로드」에 남고 2번은 연한 버튼이라, 다운로드만 4~5번 누르고 떠났다
      stubStreamKeyFetch({ issued: false });
      renderWithProviders(<PluginSettingsScreen />);

      const card = await screen.findByRole('region', { name: 'OBS 플러그인 연결' });
      const steps = () => Array.from(card.querySelectorAll('li'));
      // 창이 떠 있으면 카드가 접근성 트리에서 빠지므로 역할 대신 DOM으로 센다
      const solid = () =>
        Array.from(card.querySelectorAll('button'))
          .filter((b) => b.getAttribute('data-variant') === 'solid')
          .map((b) => b.textContent);
      const current = () => steps().findIndex((li) => li.getAttribute('aria-current') === 'step');

      // 처음: 1번
      expect(solid()).toEqual(['다운로드']);
      expect(current()).toBe(0);
      // 2번은 차례가 아니어도 테두리 버튼이다 — 연한(soft) 버튼은 카드 바탕과 거의 같아 버튼으로 안 보였다
      expect(screen.getByRole('button', { name: '다시 켰어요' })).toHaveAttribute(
        'data-variant',
        'outline',
      );

      // 창을 열었다 받지 않고 닫으면 그대로다 — 안내만 읽은 사람에게는 받은 파일이 없다(PR #549 리뷰)
      fireEvent.click(screen.getByRole('button', { name: '다운로드' }));
      let dialog = await screen.findByRole('dialog', { name: '플러그인 다운로드' });
      fireEvent.click(within(dialog).getByRole('button', { name: '닫기' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(solid()).toEqual(['다운로드']);
      expect(current()).toBe(0);

      // 창에서 설치 파일을 받고 돌아오면: 1번은 끝난 것으로 서고, 진한 버튼은 2번으로 간다
      fireEvent.click(screen.getByRole('button', { name: '다운로드' }));
      dialog = await screen.findByRole('dialog', { name: '플러그인 다운로드' });
      fireEvent.click(within(dialog).getByRole('link', { name: /설치 파일/ }));
      fireEvent.click(within(dialog).getByRole('button', { name: '닫기' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(solid()).toEqual(['다시 켰어요']);
      expect(current()).toBe(1);
      expect(steps()[0]).toHaveAttribute('data-state', 'done');
      // 다시 받을 길은 남는다 — 같은 창을 연다
      expect(screen.getByRole('button', { name: '다시 받기' })).toHaveAttribute(
        'data-variant',
        'outline',
      );

      // 다시 켰다고 하면: 3번 「코드 받기」
      fireEvent.click(screen.getByRole('button', { name: '다시 켰어요' }));
      expect(solid()).toEqual(['코드 받기']);
      expect(current()).toBe(2);
      expect(screen.getByRole('button', { name: '아직이에요' })).toHaveAttribute(
        'data-variant',
        'outline',
      );

      // 되돌리면 다시 2번이다 — 다운로드는 이미 눌렀다
      fireEvent.click(screen.getByRole('button', { name: '아직이에요' }));
      expect(solid()).toEqual(['다시 켰어요']);
      expect(current()).toBe(1);
    });

    it('설치 파일을 받은 것은 다른 메뉴에 다녀와도 이 탭이 기억한다 — 「다시 켰어요」는 잊는다 (PR #549 리뷰)', async () => {
      stubStreamKeyFetch({ issued: false });
      const first = renderWithProviders(<PluginSettingsScreen />);
      fireEvent.click(await screen.findByRole('button', { name: '다운로드' }));
      const dialog = await screen.findByRole('dialog', { name: '플러그인 다운로드' });
      fireEvent.click(within(dialog).getByRole('link', { name: /설치 파일/ }));
      fireEvent.click(within(dialog).getByRole('button', { name: '닫기' }));
      fireEvent.click(await screen.findByRole('button', { name: '다시 켰어요' }));
      first.unmount();

      // 다시 들어오면: 1번은 끝난 그대로, OBS는 지금도 켜져 있는지부터 다시 본다(진한 버튼은 2번)
      renderWithProviders(<PluginSettingsScreen />);
      const card = await screen.findByRole('region', { name: 'OBS 플러그인 연결' });
      expect(card.querySelector('li')).toHaveAttribute('data-state', 'done');
      expect(within(card).getByRole('button', { name: '다시 받기' })).toBeInTheDocument();
      expect(within(card).getByRole('button', { name: '다시 켰어요' })).toHaveAttribute(
        'data-variant',
        'solid',
      );
      expect(within(card).getByRole('button', { name: '코드 받기' })).toBeDisabled();
    });

    it('이미 설치하고 온 회원은 다운로드를 건너뛰고 2번을 눌러도 된다 — 그때 1번도 끝난 것으로 선다', async () => {
      stubStreamKeyFetch({ issued: false });
      renderWithProviders(<PluginSettingsScreen />);

      const card = await screen.findByRole('region', { name: 'OBS 플러그인 연결' });
      fireEvent.click(await screen.findByRole('button', { name: '다시 켰어요' }));

      const steps = Array.from(card.querySelectorAll('li'));
      expect(steps[0]).toHaveAttribute('data-state', 'done');
      expect(steps[2]).toHaveAttribute('aria-current', 'step');
      expect(screen.getByRole('button', { name: '코드 받기' })).toHaveAttribute(
        'data-variant',
        'solid',
      );
      // 받은 적이 없으니 1번 버튼 이름은 그대로다
      expect(screen.getByRole('button', { name: '다운로드' })).toHaveAttribute(
        'data-variant',
        'outline',
      );
    });

    it('1번 「다운로드」는 다운로드 창을 열고 guide로 센다', async () => {
      stubStreamKeyFetch({ issued: false });
      renderWithProviders(<PluginSettingsScreen />);

      fireEvent.click(await screen.findByRole('button', { name: '다운로드' }));

      expect(await screen.findByRole('dialog', { name: '플러그인 다운로드' })).toBeInTheDocument();
      expect(track.mock.calls).toEqual([['plugin_download_opened', { from: 'guide' }]]);
    });

    it('첫 발급도 같은 1콜이다 — 코드를 받은 것만으로는 온보딩 2단계가 끝나지 않는다 (POK-608)', async () => {
      const spy = stubStreamKeyFetch({ issued: false });
      renderWithProviders(<PluginSettingsScreen />);

      await issueFromSteps();

      expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();
      expect(callsTo(spy, '/api/stream-keys/pairing-codes')).toHaveLength(1);
      // 예전에는 여기서 완료로 올렸다 — 플러그인 없이 코드만 받은 회원에게 완료 체크가 붙었다
      expect(useOnboardingStore.getState().pluginLinked).toBe(false);
      // 발급 성공 뒤 한 번 센다 — 코드 원문은 싣지 않는다 (POK-357)
      expect(track.mock.calls).toEqual([['pairing_code_issued', {}]]);
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

      await issueFromSteps();

      expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();
      expect(screen.getByRole('timer')).toHaveTextContent(/(10:00|09:5\d) 후 만료돼요/);
      expect(screen.queryByText('코드가 만료됐어요')).not.toBeInTheDocument();
    });

    it('코드를 받아도 연결됐다고 말하지 않는다 — 닫으면 원문은 사라지고, 순서 카드는 받은 코드를 넣으라고 한다 (ADR-019)', async () => {
      stubStreamKeyFetch({ issued: false });
      const { container } = renderWithProviders(<PluginSettingsScreen />);

      await issueFromSteps();
      // 코드 모달이 떠 있는 동안에도 연결됐다고 말하지 않는다(모달은 body로 포털된다) — 발급은 OBS에 넣었다는 증거가 아니다
      expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();
      expect(document.body).not.toHaveTextContent(/연결됨|연결했어요|연결됐어요/);

      fireEvent.click(screen.getByRole('button', { name: '확인했어요' }));

      // 닫으면 원문은 어디에도 다시 나오지 않는다
      expect(screen.queryByText('KQ4M-7X2P')).not.toBeInTheDocument();
      expect(container.textContent).not.toContain('KQ4M-7X2P');
      // 넣지 않았으니 여전히 순서 카드다. 코드는 아직 살아 있다 — 새로 받으라고 하지 않고 넣으면 알려 준다고 한다
      // (「새로 받아 주세요」는 막 OBS에 넣으려는 사람에게 실패처럼 읽혔다, PR #539 리뷰)
      expect(
        await screen.findByText('받은 코드를 OBS의 PokeClip 창에 넣으면 여기서 바로 알려 드려요'),
      ).toBeInTheDocument();
      expect(screen.queryByText(/새로 받아 주세요/)).not.toBeInTheDocument();
      expect(document.body).not.toHaveTextContent(/연결됨|연결했어요|연결됐어요/);
    });

    it('발급 직후 상태 재조회가 실패해도 「코드를 받은 적 없음」으로 되돌아가지 않는다 (리뷰 #74)', async () => {
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
          // 발급 뒤 재조회가 일시 오류 — 낙관 캐시가 없으면 「받은 적 없음」으로 회귀한다
          return issuedOnce
            ? jsonResponse(500, { reason: '일시 오류' })
            : jsonResponse(200, { issued: false });
        return jsonResponse(404);
      });
      renderWithProviders(<PluginSettingsScreen />);

      await issueFromSteps();
      expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: '확인했어요' }));

      // 순서 카드 그대로다 — 받은 적 없는 회원의 「아래 순서대로 하면 연결돼요」로 돌아가지 않는다
      expect(
        await screen.findByText('받은 코드를 OBS의 PokeClip 창에 넣으면 여기서 바로 알려 드려요'),
      ).toBeInTheDocument();
      expect(screen.queryByText('아래 순서대로 하면 연결돼요')).not.toBeInTheDocument();
      // 읽어 둔 상태가 있으니 재조회 실패에 오류 화면으로 갈아끼우지도 않는다 (리뷰 #73)
      expect(screen.queryByText(/연동 코드 상태를 불러오지 못했어요/)).not.toBeInTheDocument();
    });

    it('발급 429는 분당 한도 안내 토스트를 띄운다 (POK-103 완료조건)', async () => {
      stubStreamKeyFetch({ issued: false, pairingStatus: 429 });
      renderWithProviders(<PluginSettingsScreen />);

      await issueFromSteps();

      expect(await screen.findByText('코드 발급이 잠시 제한됐어요')).toBeInTheDocument();
      expect(screen.getByText(/1분에 3회까지만 발급할 수 있어요/)).toBeInTheDocument();
      expect(useOnboardingStore.getState().pluginLinked).toBe(false);
    });

    it('발급 요청 중엔 3번 버튼이 스피너만 보이고 비활성이다 — 두 번 보내지 않는다', async () => {
      const spy = stubFetch((url, init) => {
        if (url === '/api/stream-keys/pairing-codes') return new Promise<Response>(() => {});
        if (url === '/api/stream-keys' && (init?.method ?? 'GET') === 'GET')
          return jsonResponse(200, { issued: false });
        return jsonResponse(404);
      });
      renderWithProviders(<PluginSettingsScreen />);

      await issueFromSteps();

      const busyButton = await screen.findByRole('button', { name: '발급 요청 중' });
      expect(busyButton).toBeDisabled();
      expect(busyButton).toHaveTextContent('코드 받기');
      fireEvent.click(busyButton);
      expect(callsTo(spy, '/api/stream-keys/pairing-codes')).toHaveLength(1);
    });
  });

  describe('상태를 아직 모르는 동안', () => {
    it('상태 조회 중 스켈레톤은 코드 박스 높이를 인라인으로 갖는다 (리뷰 #73)', () => {
      // Skeleton은 height를 항상 인라인 style로 깔아 클래스 height는 덮인다 —
      // props로 넘긴 값이 실제 인라인에 실리는지가 회귀 지점이다 (기본 16px로 렌더되던 버그)
      stubFetch(() => new Promise<Response>(() => {})); // 응답을 붙잡아 로딩 상태를 유지한다
      renderWithProviders(<PluginSettingsScreen />);

      const card = screen.getByRole('region', { name: '연동 코드' });
      const skeleton = card.querySelector<HTMLElement>('span[aria-hidden="true"]');
      expect(skeleton).not.toBeNull();
      expect(skeleton?.style.height).toBe('calc(64 * var(--pc-u))');
      // 읽기 전에는 순서 카드도 배너도 세우지 않는다 — 연결한 회원에게 설치부터 하라고 깜빡이지 않게
      expect(screen.queryByRole('region', { name: 'OBS 플러그인 연결' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: '플러그인 다운로드' })).not.toBeInTheDocument();
    });

    it('상태 조회를 한 번도 못 읽으면 「연결 전」이 아니라 오류·다시 시도를 보여준다 (리뷰 #73)', async () => {
      let asked = 0;
      stubFetch((url, init) => {
        if (url === '/api/stream-keys' && (init?.method ?? 'GET') === 'GET') {
          asked += 1;
          return asked === 1
            ? jsonResponse(500, { reason: '서버 오류' })
            : jsonResponse(200, { issued: true, createdAt: CREATED_AT, lastPairedAt: PAIRED_AT });
        }
        return jsonResponse(404);
      });
      renderWithProviders(<PluginSettingsScreen />);

      expect(await screen.findByText(/연동 코드 상태를 불러오지 못했어요/)).toBeInTheDocument();
      // 연결한 회원에게 설치부터 하라는 순서 카드를 보여주면 안 된다
      expect(screen.queryByRole('region', { name: 'OBS 플러그인 연결' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: '코드 받기' })).not.toBeInTheDocument();
      // 다운로드는 서버와 무관한 고정 주소다 — 못 읽었어도 받을 길은 둔다
      expect(screen.getByRole('button', { name: '플러그인 다운로드' })).toBeInTheDocument();

      fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));

      expect(await screen.findByText('OBS 플러그인을 연결했어요')).toBeInTheDocument();
    });

    it('다시 시도는 응답까지 오류 카드를 둔 채 그 버튼만 돈다 — 스켈레톤으로 갈아끼우지 않는다 (POK-446)', async () => {
      let asked = 0;
      let answer: (res: Response) => void = () => {};
      stubFetch((url, init) => {
        if (url === '/api/stream-keys' && (init?.method ?? 'GET') === 'GET') {
          asked += 1;
          if (asked === 3)
            return jsonResponse(200, {
              issued: true,
              createdAt: CREATED_AT,
              lastPairedAt: PAIRED_AT,
            });
          // 첫 조회는 실패, 둘째(다시 시도)는 붙잡아 「다시 읽는 중」을 만든다
          return asked === 1
            ? jsonResponse(500, { reason: '서버 오류' })
            : new Promise<Response>((resolve) => (answer = resolve));
        }
        return jsonResponse(404);
      });
      renderWithProviders(<PluginSettingsScreen />);
      const card = screen.getByRole('region', { name: '연동 코드' });

      fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }));

      // 접근 이름은 aria-label이 대신한다(발급 버튼과 같은 .busyButton) — 응답까지 잠겨 두 번 보내지 않는다
      const retrying = await screen.findByRole('button', { name: '다시 불러오는 중' });
      expect(retrying).toHaveAttribute('aria-busy', 'true');
      expect(retrying).toBeDisabled();
      expect(card).toHaveTextContent('연동 코드 상태를 불러오지 못했어요');
      // 다시 읽는 동안 쿼리는 pending으로 돌아가지만 코드 박스 스켈레톤(64u)으로 바뀌지 않는다
      expect(card.querySelector('[style*="64 * var(--pc-u)"]')).toBeNull();
      expect(asked).toBe(2);

      // 또 실패하면 다시 누를 수 있는 오류 카드로 돌아온다
      await act(async () => answer(jsonResponse(500, { reason: '서버 오류' })));
      const again = await screen.findByRole('button', { name: '다시 시도' });
      expect(again).not.toHaveAttribute('aria-busy');
      expect(again).toBeEnabled();

      fireEvent.click(again);
      expect(await screen.findByText('OBS 플러그인을 연결했어요')).toBeInTheDocument();
    });

    it('읽기가 오래 걸리면 배너부터 세우고, 늦게 「연결한 적 없음」이 오면 순서 카드가 받을 길을 대신한다', async () => {
      // 가짜 타이머는 렌더 전에 — 시간은 흐르게 두어 findBy*의 대기도 돈다
      vi.useFakeTimers({ shouldAdvanceTime: true });
      let answer: (res: Response) => void = () => {};
      stubFetch((url, init) => {
        if (url === '/api/stream-keys' && (init?.method ?? 'GET') === 'GET')
          return new Promise<Response>((resolve) => (answer = resolve));
        return jsonResponse(404);
      });
      renderWithProviders(<PluginSettingsScreen />);
      expect(screen.queryByRole('button', { name: '플러그인 다운로드' })).not.toBeInTheDocument();

      // 서버가 답하지 않아도 정해진 시간이 지나면 다운로드 창으로 갈 길이 선다
      await act(async () => {
        vi.advanceTimersByTime(STATUS_SLOW_MS);
      });
      expect(screen.getByText(/다른 PC에 설치하나요/)).toBeInTheDocument();

      await act(async () => answer(jsonResponse(200, { issued: false })));

      // 연결한 적 없는 회원에게 「다른 PC에 설치하나요?」는 남의 말이다 — 순서 카드의 1번이 받을 길이다
      expect(await screen.findByRole('region', { name: 'OBS 플러그인 연결' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '다운로드' })).toBeInTheDocument();
      expect(screen.queryByText(/다른 PC에 설치하나요/)).not.toBeInTheDocument();
    });

    it('읽기가 늦어 배너로 먼저 받았으면, 뒤늦게 붙은 순서 카드도 1번을 끝낸 것으로 세운다 (PR #549 리뷰)', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      let answer: (res: Response) => void = () => {};
      stubFetch((url, init) => {
        if (url === '/api/stream-keys' && (init?.method ?? 'GET') === 'GET')
          return new Promise<Response>((resolve) => (answer = resolve));
        return jsonResponse(404);
      });
      renderWithProviders(<PluginSettingsScreen />);
      await act(async () => {
        vi.advanceTimersByTime(STATUS_SLOW_MS);
      });

      // 배너 → 창 → 설치 파일. 순서 카드는 아직 없다
      fireEvent.click(screen.getByRole('button', { name: '플러그인 다운로드' }));
      const dialog = await screen.findByRole('dialog', { name: '플러그인 다운로드' });
      fireEvent.click(within(dialog).getByRole('link', { name: /설치 파일/ }));
      fireEvent.click(within(dialog).getByRole('button', { name: '닫기' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

      await act(async () => answer(jsonResponse(200, { issued: false })));
      const card = await screen.findByRole('region', { name: 'OBS 플러그인 연결' });
      expect(card.querySelector('li')).toHaveAttribute('data-state', 'done');
      expect(within(card).getByRole('button', { name: '다시 받기' })).toHaveAttribute(
        'data-variant',
        'outline',
      );
      expect(within(card).getByRole('button', { name: '다시 켰어요' })).toHaveAttribute(
        'data-variant',
        'solid',
      );
    });

    it('첫 조회가 실패했다가 다시 시도로 「연결한 적 없음」을 읽으면 순서 카드로 바뀐다', async () => {
      let asked = 0;
      stubFetch((url, init) => {
        if (url === '/api/stream-keys' && (init?.method ?? 'GET') === 'GET') {
          asked += 1;
          return asked === 1
            ? jsonResponse(500, { reason: '서버 오류' })
            : jsonResponse(200, { issued: false });
        }
        return jsonResponse(404);
      });
      renderWithProviders(<PluginSettingsScreen />);

      fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }));

      // 오류 카드가 있던 자리(맨 위)에서 그대로 바뀐다 — 예전의 잠기지 않은 「코드 발급」으로 새지 않는다
      expect(await screen.findByRole('region', { name: 'OBS 플러그인 연결' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '코드 받기' })).toBeDisabled();
      expect(screen.queryByRole('button', { name: '코드 발급' })).not.toBeInTheDocument();
      expect(screen.queryByText(/다른 PC에 설치하나요/)).not.toBeInTheDocument();
    });
  });

  describe('코드 창이 연결을 지켜본다 (POK-608)', () => {
    it('코드가 OBS에 들어가면 새로고침 없이 「연결됐어요」로 바뀌고, 그 뒤로는 묻지 않는다', async () => {
      // 가짜 타이머는 렌더 전에 — react-query가 주기를 걸 때의 타이머를 잡는다
      vi.useFakeTimers({ shouldAdvanceTime: true });
      const spy = stubStreamKeyFetch({ issued: false });
      const { queryClient } = renderWithProviders(<PluginSettingsScreen />);

      await issueFromSteps();
      expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();
      await passTime(queryClient, 0); // 발급 뒤 재조회까지 받는다
      const asked = statusCalls(spy);

      // 아직 안 넣었다 — 주기마다 다시 묻지만 화면은 코드 그대로다
      await passTime(queryClient);
      expect(statusCalls(spy)).toBeGreaterThan(asked);
      expect(screen.getByText('KQ4M-7X2P')).toBeInTheDocument();
      expect(useOnboardingStore.getState().pluginLinked).toBe(false);

      spy.pairNow();
      await passTime(queryClient);

      expect(
        await screen.findByRole('heading', { name: '플러그인이 연결됐어요' }),
      ).toBeInTheDocument();
      expect(screen.queryByText('KQ4M-7X2P')).not.toBeInTheDocument();
      // 온보딩 2단계는 여기서 처음 끝난다
      expect(useOnboardingStore.getState().pluginLinked).toBe(true);
      expect(track.mock.calls).toEqual([
        ['pairing_code_issued', {}],
        ['plugin_paired', { seen: 'code_dialog' }],
      ]);

      // 연결을 봤으면 창이 떠 있어도 더 묻지 않는다
      const afterPaired = statusCalls(spy);
      await passTime(queryClient, PAIRING_WATCH_MS * 3);
      expect(statusCalls(spy)).toBe(afterPaired);

      // 닫으면 순서 카드가 물러나고 연결한 회원의 화면이 된다 — 같은 연결을 다시 세지 않는다
      fireEvent.click(screen.getByRole('button', { name: '확인' }));
      expect(await screen.findByText('OBS 플러그인을 연결했어요')).toBeInTheDocument();
      expect(screen.queryByRole('region', { name: 'OBS 플러그인 연결' })).not.toBeInTheDocument();
      expect(screen.getByText(/다른 PC에 설치하나요/)).toBeInTheDocument();
      expect(track.mock.calls.filter(([name]) => name === 'plugin_paired')).toHaveLength(1);
    });

    it('이미 연결한 회원이 코드를 다시 받아도, 그 코드가 쓰이기 전에는 연결됐다고 하지 않는다', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      const spy = stubStreamKeyFetch({ paired: true });
      const { queryClient } = renderWithProviders(<PluginSettingsScreen />);

      // 다른 PC를 더 붙이려고 코드를 다시 받는다 — 연결 시각은 이미 있다
      fireEvent.click(await screen.findByRole('button', { name: '재발급' }));
      expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();
      await passTime(queryClient);

      // 「시각이 있다」만 보면 창이 뜨자마자 연결됐다고 한다 — 발급한 순간에서 바뀌어야 한다
      expect(screen.getByText('KQ4M-7X2P')).toBeInTheDocument();
      expect(
        screen.queryByRole('heading', { name: '플러그인이 연결됐어요' }),
      ).not.toBeInTheDocument();

      spy.pairNow();
      await passTime(queryClient);
      expect(
        await screen.findByRole('heading', { name: '플러그인이 연결됐어요' }),
      ).toBeInTheDocument();
    });

    it('창을 닫아도 코드가 살아 있는 동안 지켜보다가, OBS에 넣으면 카드가 바뀐다 — 운영 staleTime(60초)에서도 (PR #539 리뷰)', async () => {
      // 주 버튼 「확인했어요」로 창부터 닫고 OBS에 넣는 흐름이다. 감시를 창에 묶었을 때는 창을 닫는 순간 멈췄고,
      // 운영 staleTime 60초 탓에 돌아와도 다시 읽지 않아 화면이 연결을 못 봤다 — 기본 테스트 클라이언트(staleTime 0)로는 안 드러난다
      vi.useFakeTimers({ shouldAdvanceTime: true });
      const spy = stubStreamKeyFetch({ issued: false });
      const client = new QueryClient({
        defaultOptions: { queries: { staleTime: 60_000, retry: false } },
      });
      const { queryClient } = renderWithProviders(<PluginSettingsScreen />, client);

      await issueFromSteps();
      fireEvent.click(await screen.findByRole('button', { name: '확인했어요' }));
      await passTime(queryClient, 0);
      const asked = statusCalls(spy);

      // 아직 안 넣었다 — 창이 없어도 계속 묻는다
      await passTime(queryClient);
      expect(statusCalls(spy)).toBeGreaterThan(asked);

      spy.pairNow();
      await passTime(queryClient);

      expect(await screen.findByText('OBS 플러그인을 연결했어요')).toBeInTheDocument();
      expect(screen.queryByRole('region', { name: 'OBS 플러그인 연결' })).not.toBeInTheDocument();
      expect(useOnboardingStore.getState().pluginLinked).toBe(true);
      // 창을 닫은 뒤에 본 연결이다
      expect(track.mock.calls).toContainEqual(['plugin_paired', { seen: 'later' }]);

      // 연결을 봤으면 더 묻지 않는다
      const afterPaired = statusCalls(spy);
      await passTime(queryClient, PAIRING_WATCH_MS * 3);
      expect(statusCalls(spy)).toBe(afterPaired);
    });

    it('주기 조회가 못 본 연결도 마감 때 한 번 더 읽어 놓치지 않는다 (PR #539 2차 리뷰)', async () => {
      // 마지막 주기 조회와 마감 사이에 넣은 코드는 다음 주기가 마감 뒤라 주기 조회로는 못 본다. 그 틈을 숨은 탭으로
      // 만든다 — 숨은 동안은 주기 조회가 서므로, 마감 때의 마지막 읽기만이 연결을 볼 수 있다
      vi.useFakeTimers({ shouldAdvanceTime: true });
      const spy = stubStreamKeyFetch({ issued: false });
      const { queryClient } = renderWithProviders(<PluginSettingsScreen />);
      try {
        await issueFromSteps();
        expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();
        await passTime(queryClient, PAIRING_CODE_TTL_MS - PAIRING_WATCH_MS * 2);
        act(() => focusManager.setFocused(false));
        const asked = statusCalls(spy);

        // 마감 직전에 OBS가 코드를 넣었다 — 주기 조회는 서 있다
        spy.pairNow();
        await passTime(queryClient, PAIRING_WATCH_MS);
        expect(statusCalls(spy)).toBe(asked);
        await passTime(queryClient, PAIRING_WATCH_MS * 2);

        expect(
          await screen.findByRole('heading', { name: '플러그인이 연결됐어요' }),
        ).toBeInTheDocument();
      } finally {
        act(() => focusManager.setFocused(undefined));
      }
    });

    it('창을 닫은 뒤 이 화면에서 연결을 보면 초점이 바뀐 카드 제목으로 가고 상태 칸이 알린다 (PR #539 2차 리뷰)', async () => {
      // 주 흐름: 「확인했어요」로 창부터 닫고 OBS에 넣는다 — 초점이 있던 「코드 받기」가 카드째 사라진다
      vi.useFakeTimers({ shouldAdvanceTime: true });
      const spy = stubStreamKeyFetch({ issued: false });
      const { queryClient } = renderWithProviders(<PluginSettingsScreen />);

      await issueFromSteps();
      fireEvent.click(await screen.findByRole('button', { name: '확인했어요' }));
      screen.getByRole('button', { name: '코드 받기' }).focus();

      spy.pairNow();
      await passTime(queryClient);

      expect(await screen.findByText('OBS 플러그인을 연결했어요')).toBeInTheDocument();
      expect(document.activeElement).toBe(screen.getByRole('heading', { name: '연동 코드' }));
      expect(screen.getAllByRole('status').map((el) => el.textContent)).toContain(
        '플러그인이 연결됐어요',
      );
    });

    it('처음 들어올 때부터 연결된 회원에게는 초점을 옮기지도 알리지도 않는다', async () => {
      stubStreamKeyFetch({ paired: true });
      renderWithProviders(<PluginSettingsScreen />);

      expect(await screen.findByText('OBS 플러그인을 연결했어요')).toBeInTheDocument();
      expect(document.activeElement).toBe(document.body);
      expect(screen.getAllByRole('status').map((el) => el.textContent)).not.toContain(
        '플러그인이 연결됐어요',
      );
    });

    it('창을 닫은 뒤 코드가 만료되면 더 묻지 않고, 순서 카드가 새로 받으라고 한다', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      const spy = stubStreamKeyFetch({ issued: false });
      const { queryClient } = renderWithProviders(<PluginSettingsScreen />);

      await issueFromSteps();
      fireEvent.click(await screen.findByRole('button', { name: '확인했어요' }));
      expect(
        await screen.findByText('받은 코드를 OBS의 PokeClip 창에 넣으면 여기서 바로 알려 드려요'),
      ).toBeInTheDocument();

      await passTime(queryClient, PAIRING_CODE_TTL_MS + PAIRING_WATCH_MS);
      expect(await screen.findByText(/전에 받은 코드는 다시 볼 수 없어요/)).toBeInTheDocument();
      const asked = statusCalls(spy);

      await passTime(queryClient, PAIRING_WATCH_MS * 3);
      expect(statusCalls(spy)).toBe(asked);
    });

    it('창을 닫고 OBS에 넣은 뒤 화면이 알아채기 전에 새 코드를 받아도, 새 코드가 쓰이기 전에는 연결됐다고 하지 않는다 (PR #539 리뷰)', async () => {
      // 발급 순간의 기준을 캐시에서 읽던 때는 캐시(미연결)가 낡아, 새 코드를 받자마자 재조회가 앞 코드의 연결을 가져와
      // 새 코드 창이 「연결됐어요」로 바뀌고 코드가 사라졌다. 이제 서버 시각끼리 견준다(발급 응답 expiresAt − TTL)
      vi.useFakeTimers({ shouldAdvanceTime: true });
      const spy = stubStreamKeyFetch({ issued: false });
      const { queryClient } = renderWithProviders(<PluginSettingsScreen />);

      await issueFromSteps();
      fireEvent.click(await screen.findByRole('button', { name: '확인했어요' }));
      await passTime(queryClient, 0);

      // 앞 코드가 OBS에 들어갔다 — 화면이 다음 주기에 읽기 전에 새 코드를 받는다
      spy.pairNow(new Date(Date.now() - 1).toISOString());
      fireEvent.click(screen.getByRole('button', { name: '코드 받기' }));
      expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();
      await passTime(queryClient);

      // 회원은 앞 코드로 연결됐지만 새 코드는 아직 쓰이지 않았다 — 창은 코드를 그대로 보인다
      expect(screen.getByText('KQ4M-7X2P')).toBeInTheDocument();
      expect(
        screen.queryByRole('heading', { name: '플러그인이 연결됐어요' }),
      ).not.toBeInTheDocument();
    });

    it('연결을 보고 창을 닫으면 초점이 바뀐 카드 제목으로 간다 — 창을 연 버튼이 사라졌다 (PR #539 리뷰)', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      const spy = stubStreamKeyFetch({ issued: false });
      const { queryClient } = renderWithProviders(<PluginSettingsScreen />);

      fireEvent.click(await screen.findByRole('button', { name: '다시 켰어요' }));
      const trigger = screen.getByRole('button', { name: '코드 받기' });
      trigger.focus();
      fireEvent.click(trigger);
      expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();

      spy.pairNow();
      await passTime(queryClient);
      expect(
        await screen.findByRole('heading', { name: '플러그인이 연결됐어요' }),
      ).toBeInTheDocument();
      // 순서 카드가 연동 코드 카드로 바뀌어 「코드 받기」가 문서에서 빠졌다
      expect(trigger).not.toBeInTheDocument();

      fireEvent.click(screen.getByRole('button', { name: '확인' }));

      // body로 떨어지지 않는다 — 문서 처음부터 다시 탭하지 않게
      expect(document.activeElement).toBe(screen.getByRole('heading', { name: '연동 코드' }));
    });

    it('코드가 만료되면 창이 떠 있어도 더 묻지 않는다 — 죽은 코드는 쓰일 수 없다', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      const spy = stubStreamKeyFetch({ issued: false });
      const { queryClient } = renderWithProviders(<PluginSettingsScreen />);

      await issueFromSteps();
      expect(await screen.findByText('KQ4M-7X2P')).toBeInTheDocument();

      // 만료까지 흘려보낸다 — 마지막 읽기 뒤에 주기가 선다
      await passTime(queryClient, PAIRING_CODE_TTL_MS + PAIRING_WATCH_MS);
      expect(await screen.findByRole('heading', { name: '코드가 만료됐어요' })).toBeInTheDocument();
      const asked = statusCalls(spy);

      await passTime(queryClient, PAIRING_WATCH_MS * 3);
      expect(statusCalls(spy)).toBe(asked);
    });

    it('창을 닫은 뒤에 넣은 연결은 다시 들어왔을 때 한 번 센다(seen: later) — 같은 연결을 또 세지 않는다', async () => {
      stubStreamKeyFetch({ paired: true });
      const first = renderWithProviders(<PluginSettingsScreen />);
      expect(await screen.findByText('OBS 플러그인을 연결했어요')).toBeInTheDocument();
      expect(track.mock.calls).toEqual([['plugin_paired', { seen: 'later' }]]);
      // 서버가 「연결했다」고 하니 온보딩 2단계도 여기서 맞춰진다(다른 기기·창을 닫은 뒤의 연결)
      expect(useOnboardingStore.getState().pluginLinked).toBe(true);

      first.unmount();
      track.mockClear();
      renderWithProviders(<PluginSettingsScreen />);
      expect(await screen.findByText('OBS 플러그인을 연결했어요')).toBeInTheDocument();
      expect(track).not.toHaveBeenCalled();
    });
  });
});
