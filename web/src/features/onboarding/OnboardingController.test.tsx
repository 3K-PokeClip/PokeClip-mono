import { fireEvent, render, screen } from '@testing-library/react';
import { axe } from 'jest-axe';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { HomeScreen } from '@/features/home/HomeScreen';
import { OnboardingController } from '@/features/onboarding/OnboardingController';
import { useOnboardingStore } from '@/stores/onboarding';

const push = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push }),
}));

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

/** 홈과 함께 렌더 — 스포트라이트 타깃(data-tour-id)이 실제로 존재하는 환경. */
function renderWithHome() {
  return render(
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
    resetStore();
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
  });

  it('둘러보기 시작 — 1/6 채널 연동 카드가 뜨고 CTA가 채널 연동 화면으로 간다 (DoD)', async () => {
    renderWithHome();

    fireEvent.click(await screen.findByRole('button', { name: '둘러보기 시작' }));

    expect(screen.getByRole('dialog', { name: /시작 가이드 1\/6/ })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '채널 연동하기' }));
    expect(push).toHaveBeenCalledWith('/settings/channels');
  });

  it('2단계의 연동 코드 발급 CTA는 플러그인 설정으로 간다 (DoD)', async () => {
    renderWithHome();

    fireEvent.click(await screen.findByRole('button', { name: '둘러보기 시작' }));
    fireEvent.click(screen.getByRole('button', { name: '다음' }));

    expect(screen.getByRole('dialog', { name: /시작 가이드 2\/6/ })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '연동 코드 발급' }));
    expect(push).toHaveBeenCalledWith('/settings/plugin');
  });

  it('채널 연동 뒤 홈 복귀 — 웰컴 1단계와 투어 1단계 카드에 완료가 표시된다 (DoD)', async () => {
    // 채널 연동 화면에서 연동을 마치고 돌아온 상태를 저장값으로 재현한다.
    window.localStorage.setItem('pc-onboarding', JSON.stringify({ v: 1, channelLinked: true }));
    renderWithHome();

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('완료');

    fireEvent.click(screen.getByRole('button', { name: '둘러보기 시작' }));
    expect(screen.getByRole('dialog', { name: /시작 가이드 1\/6/ })).toHaveTextContent('완료');
  });

  it('6단계까지 다음으로 진행하고 완료를 누르면 칩으로 돌아간다', async () => {
    renderWithHome();

    fireEvent.click(await screen.findByRole('button', { name: '둘러보기 시작' }));
    for (let i = 0; i < 5; i += 1) {
      fireEvent.click(screen.getByRole('button', { name: '다음' }));
    }

    expect(screen.getByRole('dialog', { name: /시작 가이드 6\/6/ })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '완료' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '둘러보기' })).toBeInTheDocument();
    expect(useOnboardingStore.getState().tourDone).toBe(true);
  });

  it('건너뛰기는 어느 스텝에서든 투어를 끝낸다', async () => {
    renderWithHome();

    fireEvent.click(await screen.findByRole('button', { name: '둘러보기 시작' }));
    fireEvent.click(screen.getByRole('button', { name: '건너뛰기' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(useOnboardingStore.getState().tourDone).toBe(true);
  });

  it('스포트라이트 타깃이 하나도 없으면 3단계 진입 시 자동 완료된다', async () => {
    // 홈 없이 컨트롤러만 — 라이브 밴드·VOD 그리드 등 타깃이 전무한 환경.
    render(<OnboardingController />);

    fireEvent.click(await screen.findByRole('button', { name: '둘러보기 시작' }));
    fireEvent.click(screen.getByRole('button', { name: '다음' })); // 1 → 2
    fireEvent.click(screen.getByRole('button', { name: '다음' })); // 2 → (3~6 결손) → 완료

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(useOnboardingStore.getState().tourDone).toBe(true);
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
