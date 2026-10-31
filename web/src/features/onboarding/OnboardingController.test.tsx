import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { axe } from 'jest-axe';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { HomeScreen } from '@/features/home/HomeScreen';
import { OnboardingController } from '@/features/onboarding/OnboardingController';
import { useOnboardingStore } from '@/stores/onboarding';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';
import { ChannelRelationContext } from '@/features/channel/ChannelScope';

const push = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push }),
}));
const { track } = vi.hoisted(() => ({ track: vi.fn() }));
vi.mock('@/lib/analytics', () => ({ track, identifyUser: vi.fn() }));
/** 사용 통계로 나간 안내 종료 이벤트(POK-357) */
const finished = () =>
  track.mock.calls
    .filter(([name]) => name === 'onboarding_tour_finished')
    .map(([, props]) => props);

// jsdom에는 scrollIntoView가 없다 — 스포트라이트 진입 시 호출되므로 스텁한다.
beforeAll(() => {
  Element.prototype.scrollIntoView = vi.fn();
});

function resetStore() {
  useOnboardingStore.setState({
    welcomeSeen: false,
    tourDone: false,
    channelLinked: false,
    pluginLinked: false,
    hydrated: false,
    tourStep: null,
  });
}

// 홈은 마운트 뒤 clip·auth를 부른다. 투어의 지난 방송 격자·오른쪽 패널 단계는 방송 기록이 있을 때만 서고(5단계),
// 포키가 해 주는 일 단계는 기록이 없을 때만 선다(4단계, POK-378) — 기본 서버는 지난 방송 하나를 둔다.
const PAST_BROADCAST = {
  streamId: 'past-1',
  status: 'vod_ready',
  relation: 'OWNER',
  startedAt: '2026-09-26T10:00:00Z',
  endedAt: '2026-09-26T12:00:00Z',
  vodExpiresAt: null,
  title: '지난 방송',
};

function homeServerWith(history: boolean) {
  return (url: string) => {
    if (url.startsWith('/api/clip/library'))
      return jsonResponse(200, { items: [], nextCursor: null });
    if (url.startsWith('/api/clip/broadcasts?state=past'))
      return jsonResponse(200, { broadcasts: history ? [PAST_BROADCAST] : [], nextCursor: null });
    if (url.startsWith('/api/clip/broadcasts'))
      return jsonResponse(200, { broadcasts: [], nextCursor: null });
    return jsonResponse(200, { id: 1, email: 'me@example.com', name: null, profileImageUrl: null });
  };
}
const homeServer = homeServerWith(true);

/** 홈과 함께 렌더 — 스포트라이트 타깃(data-tour-id)이 실제로 존재하는 환경. */
function renderWithHome() {
  return renderWithProviders(
    <>
      <HomeScreen />
      <OnboardingController />
    </>,
  );
}

describe('OnboardingController', () => {
  beforeEach(() => {
    window.localStorage.clear();
    push.mockClear();
    track.mockClear();
    resetStore();
    stubFetch(homeServer);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('신규 계정 첫 진입 — 웰컴 다이얼로그가 뜬다', async () => {
    renderWithHome();

    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { name: 'PokeClip에 오신 걸 환영해요' }),
    ).toBeInTheDocument();
  });

  it('나중에 볼게요 — 다이얼로그가 닫히고 재진입 칩이 남는다', async () => {
    renderWithHome();

    fireEvent.click(await screen.findByRole('button', { name: '나중에 볼게요' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '둘러보기' })).toBeInTheDocument();
    expect(finished()).toEqual([{ outcome: 'later' }]);
  });

  it('둘러보기 시작 — 1/5 채널 연동 카드가 뜨고 CTA가 채널 연동 화면으로 간다 (DoD)', async () => {
    renderWithHome();

    fireEvent.click(await screen.findByRole('button', { name: '둘러보기 시작' }));

    expect(screen.getByRole('dialog', { name: /시작 가이드 1\/5/ })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '채널 연동하기' }));
    expect(push).toHaveBeenCalledWith('/settings/channels');
  });

  it('2단계의 연동 코드 발급 CTA는 플러그인 설정으로 간다 (DoD)', async () => {
    renderWithHome();

    fireEvent.click(await screen.findByRole('button', { name: '둘러보기 시작' }));
    fireEvent.click(screen.getByRole('button', { name: '다음' }));

    expect(screen.getByRole('dialog', { name: /시작 가이드 2\/5/ })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '연동 코드 발급' }));
    expect(push).toHaveBeenCalledWith('/settings/plugin');
  });

  it('남의 채널이면 채널 연동·코드 발급 CTA가 비활성이다(POK-390): 그 화면은 그 채널에서 막혀 있다', async () => {
    renderWithProviders(
      <ChannelRelationContext.Provider value="EDITOR">
        <HomeScreen />
        <OnboardingController />
      </ChannelRelationContext.Provider>,
    );

    fireEvent.click(await screen.findByRole('button', { name: '둘러보기 시작' }));
    expect(screen.getByRole('button', { name: '채널 연동하기' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '다음' }));
    expect(screen.getByRole('button', { name: '연동 코드 발급' })).toBeDisabled();
    expect(push).not.toHaveBeenCalled();
  });

  it('채널 연동 뒤 홈 복귀 — 웰컴 1단계와 투어 1단계 카드에 완료가 표시된다 (DoD)', async () => {
    // 채널 연동 화면에서 연동을 마치고 돌아온 상태를 저장값으로 재현한다.
    window.localStorage.setItem('pc-onboarding', JSON.stringify({ v: 1, channelLinked: true }));
    renderWithHome();

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('완료');

    fireEvent.click(screen.getByRole('button', { name: '둘러보기 시작' }));
    expect(screen.getByRole('dialog', { name: /시작 가이드 1\/5/ })).toHaveTextContent('완료');
  });

  it('기록이 있으면 5단계 — 포키가 해 주는 일은 빼고 세며, 끝에서 완료를 누르면 칩으로 돌아간다(POK-378)', async () => {
    renderWithHome();
    // 지난 방송 격자가 서야 지난 방송·오른쪽 패널 단계 타깃이 있다 — 홈을 다 읽은 뒤 시작한다
    await screen.findByRole('region', { name: '지난 방송 · VOD' });

    const welcome = await screen.findByRole('dialog');
    expect(within(welcome).getAllByRole('listitem')).toHaveLength(5);
    expect(welcome).not.toHaveTextContent('하이라이트 자동 감지');
    fireEvent.click(screen.getByRole('button', { name: '둘러보기 시작' }));
    fireEvent.click(screen.getByRole('button', { name: '다음' })); // 1 → 2
    fireEvent.click(screen.getByRole('button', { name: '다음' })); // 2 → 3 큰 칸
    expect(screen.getByRole('dialog', { name: /시작 가이드 3\/5/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '다음' })); // 3 → (포키가 해 주는 일 없음) → 지난 방송
    expect(screen.getByRole('dialog', { name: /시작 가이드 4\/5/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '다음' })); // 지난 방송 → 오른쪽 패널

    expect(screen.getByRole('dialog', { name: /시작 가이드 5\/5/ })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '완료' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '둘러보기' })).toBeInTheDocument();
    expect(useOnboardingStore.getState().tourDone).toBe(true);
    expect(finished()).toEqual([{ outcome: 'completed' }]);
  });

  it('방송 기록이 없는 계정은 4단계로 센다 — 웰컴 목록도 넷, 포키가 해 주는 일 「4/4」에서 끝난다(POK-378)', async () => {
    stubFetch(homeServerWith(false));
    renderWithHome();
    await screen.findByRole('region', { name: '포키가 해 주는 일' });

    // 「6단계」라 해 놓고 4에서 끝나면 「왜 벌써 끝나지」가 된다 — 목록·번호·점 모두 보이는 단계만 센다
    const welcome = await screen.findByRole('dialog');
    expect(within(welcome).getAllByRole('listitem')).toHaveLength(4);
    expect(welcome).not.toHaveTextContent('지난 방송');
    fireEvent.click(screen.getByRole('button', { name: '둘러보기 시작' }));
    expect(screen.getByRole('dialog', { name: /시작 가이드 1\/4/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '다음' })); // 1 → 2
    fireEvent.click(screen.getByRole('button', { name: '다음' })); // 2 → 3 큰 칸
    fireEvent.click(screen.getByRole('button', { name: '다음' })); // 3 → 4 포키가 해 주는 일
    expect(screen.getByRole('dialog', { name: /시작 가이드 4\/4/ })).toBeInTheDocument();
    // 기록 없는 계정은 4단계가 실제 마지막 — 「다음」이 아니라 「완료」라 적는다(리뷰 #299)
    expect(screen.queryByRole('button', { name: '다음' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '완료' })); // 4 → (지난 방송·오른쪽 패널 없음) → 완료

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(useOnboardingStore.getState().tourDone).toBe(true);
    expect(finished()).toEqual([{ outcome: 'completed' }]);
  });

  /** 지난 방송 목록 응답을 붙잡아 홈을 「읽는 중」에 묶어 둔다 — release()로 풀어 준다 */
  function holdPastList() {
    let release = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    stubFetch(async (url) => {
      if (url.startsWith('/api/clip/broadcasts?state=past')) await gate;
      return homeServer(url);
    });
    return () => release();
  }

  it('홈을 읽는 동안엔 웰컴 창을 띄우지 않는다 — 몇 단계일지 안 뒤에 띄운다(POK-378)', async () => {
    const release = holdPastList();
    renderWithHome();
    await screen.findByLabelText('불러오는 중');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    release();
    const welcome = await screen.findByRole('dialog');
    expect(within(welcome).getAllByRole('listitem')).toHaveLength(5);
  });

  it('홈을 읽는 중엔 3단계에서 투어가 끝나지 않고 「다음」을 잠근다 — 다 읽으면 지난 방송으로 이어진다(리뷰 #299)', async () => {
    const release = holdPastList();
    // 설정에 다녀와 홈이 다시 읽는 중인 상태 — 웰컴은 이미 봤고 투어가 메모리에 남아 있다
    window.localStorage.setItem('pc-onboarding', JSON.stringify({ v: 1, welcomeSeen: true }));
    useOnboardingStore.setState({ hydrated: true, welcomeSeen: true, tourStep: 0 });
    renderWithHome();

    await screen.findByRole('dialog', { name: /시작 가이드 1/ });
    fireEvent.click(screen.getByRole('button', { name: '다음' })); // 1 → 2
    fireEvent.click(screen.getByRole('button', { name: '다음' })); // 2 → 3 큰 칸(스켈레톤)
    // 전체가 몇 단계일지 아직 모른다 — 번호만 보인다
    expect(screen.getByRole('dialog', { name: /^시작 가이드 3 — / })).toBeInTheDocument();
    // 뒤 단계 타깃이 아직 없다 — 여기서 끝내지 않고 잠근다
    await waitFor(() => expect(screen.getByRole('button', { name: '다음' })).toBeDisabled());
    expect(useOnboardingStore.getState().tourDone).toBe(false);

    release();
    await waitFor(() => expect(screen.getByRole('button', { name: '다음' })).toBeEnabled());
    expect(screen.getByRole('dialog', { name: /시작 가이드 3\/5/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '다음' })); // 3 → (포키가 해 주는 일 없음) → 지난 방송
    expect(screen.getByRole('dialog', { name: /시작 가이드 4\/5/ })).toBeInTheDocument();
  });

  it('설정에서 돌아와 홈을 읽는 동안 중앙 카드였던 1단계가, 홈이 그려지면 방송 채널 줄을 짚는다(리뷰 #299)', async () => {
    const release = holdPastList();
    // 「채널 연동하기」로 설정에 다녀온 상태 — 투어 단계는 메모리에 남아 있다
    window.localStorage.setItem('pc-onboarding', JSON.stringify({ v: 1, welcomeSeen: true }));
    useOnboardingStore.setState({ hydrated: true, welcomeSeen: true, tourStep: 0 });
    renderWithHome();

    const dialog = await screen.findByRole('dialog', { name: /시작 가이드 1/ });
    // 짚을 줄이 아직 없어 중앙 카드 — 넓은 스포트라이트 카드 표시가 없다
    expect(dialog).not.toHaveAttribute('data-wide');

    const scroll = vi.mocked(Element.prototype.scrollIntoView);
    scroll.mockClear();
    release();
    await waitFor(() =>
      expect(screen.getByRole('dialog', { name: /시작 가이드 1\/5/ })).toHaveAttribute(
        'data-wide',
        'true',
      ),
    );
    // 나중에 선 줄도 화면 안으로 끌어온다 — 오버레이가 스크롤을 잠가 좁은 화면에서 카드가 화면 밖에 갇히지 않게(리뷰 #299 2차)
    const row = document.querySelector('[data-tour-id="home-channel"]');
    expect(row).not.toBeNull();
    expect(scroll.mock.contexts).toContain(row);
  });

  it('짚은 줄이 그대로여도 창 높이가 바뀌면 카드 자리를 다시 잡는다(리뷰 #299 3차)', async () => {
    renderWithHome();
    fireEvent.click(await screen.findByRole('button', { name: '둘러보기 시작' }));
    const card = screen.getByRole('dialog', { name: /시작 가이드 1\/5/ });
    await waitFor(() => expect(card.style.top).not.toBe(''));
    const before = card.style.top;

    const height = window.innerHeight;
    try {
      // 카드가 아래에 들어갈 자리가 없을 만큼 창을 줄인다 — 짚은 줄의 자리(jsdom에선 0)는 그대로다
      Object.defineProperty(window, 'innerHeight', { configurable: true, value: 120 });
      fireEvent(window, new Event('resize'));
      await waitFor(() => expect(card.style.top).not.toBe(before));
    } finally {
      Object.defineProperty(window, 'innerHeight', { configurable: true, value: height });
    }
  });

  it('투어를 끝내 칩만 남으면 DOM 관찰을 멈춘다 — 홈 폴링마다 단계를 다시 세지 않는다(리뷰 #299 2차)', async () => {
    // 지금 붙어 있는 관찰자 수를 센다
    const Native = window.MutationObserver;
    let live = 0;
    class Counting extends Native {
      private on = false;
      observe(target: Node, options?: MutationObserverInit) {
        if (!this.on) live += 1;
        this.on = true;
        super.observe(target, options);
      }
      disconnect() {
        if (this.on) live -= 1;
        this.on = false;
        super.disconnect();
      }
    }
    vi.stubGlobal('MutationObserver', Counting);
    renderWithHome();

    fireEvent.click(await screen.findByRole('button', { name: '둘러보기 시작' }));
    expect(live).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('button', { name: '건너뛰기' }));

    expect(screen.getByRole('button', { name: '둘러보기' })).toBeInTheDocument();
    expect(live).toBe(0);
  });

  it('건너뛰기는 어느 스텝에서든 투어를 끝낸다', async () => {
    renderWithHome();

    fireEvent.click(await screen.findByRole('button', { name: '둘러보기 시작' }));
    fireEvent.click(screen.getByRole('button', { name: '건너뛰기' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(useOnboardingStore.getState().tourDone).toBe(true);
    expect(finished()).toEqual([{ outcome: 'skipped' }]);
  });

  it('스포트라이트 타깃이 하나도 없으면 3단계 진입 시 자동 완료된다', async () => {
    // 홈 없이 컨트롤러만 — 라이브 밴드·VOD 그리드 등 타깃이 전무한 환경.
    render(<OnboardingController />);

    fireEvent.click(await screen.findByRole('button', { name: '둘러보기 시작' }));
    fireEvent.click(screen.getByRole('button', { name: '다음' })); // 1 → 2
    // 뒤에 보일 단계가 없으니 2단계 버튼이 「완료」다
    fireEvent.click(screen.getByRole('button', { name: '완료' })); // 2 → (3~6 결손) → 완료

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(useOnboardingStore.getState().tourDone).toBe(true);
    // 보이는 마지막 단계에서 「다음」을 눌렀으니 완료로 센다 — 이어지는 백스탑 이펙트가 한 번 더 세지 않는다
    expect(finished()).toEqual([{ outcome: 'completed' }]);
  });

  // axe는 다이얼로그 요소로 스코프한다 — 테스트 스캐폴딩(landmark 없는 body 직속 렌더)이
  // region 규칙에 걸리는 것을 피하고 오버레이 자체의 접근성만 본다.
  it('접근성 위반이 없다 — 웰컴 다이얼로그', async () => {
    renderWithHome();

    expect(await axe(await screen.findByRole('dialog'))).toHaveNoViolations();
  });

  it('접근성 위반이 없다 — 투어 카드', async () => {
    renderWithHome();

    fireEvent.click(await screen.findByRole('button', { name: '둘러보기 시작' }));

    expect(await axe(screen.getByRole('dialog'))).toHaveNoViolations();
  });
});
