import {
  TOUR_STEPS,
  TOUR_TARGET,
  WELCOME_STEPS,
  isLastStep,
  isStepVisible,
  nextVisibleStep,
  spotlightRect,
  stepDone,
  tooltipPosition,
} from './tourSteps';

const allTargets = () => true;
const noTargets = () => false;

describe('TOUR_STEPS', () => {
  it('6단계 모두 스포트라이트다 — 설정 2단계만 타깃이 없으면 중앙 카드로 대신한다(POK-378)', () => {
    expect(TOUR_STEPS.map((s) => s.id)).toEqual([
      'channel',
      'plugin',
      'hero',
      'features',
      'vod',
      'aside',
    ]);
    expect(TOUR_STEPS.slice(0, 2).every((s) => s.fallback === 'centered')).toBe(true);
    expect(TOUR_STEPS.slice(2).every((s) => s.fallback === undefined)).toBe(true);
  });

  it('설정 2단계는 홈의 방송 채널·플러그인 줄을 짚는다', () => {
    expect(TOUR_STEPS.slice(0, 2).map((s) => s.targetId)).toEqual([
      TOUR_TARGET.homeChannel,
      TOUR_TARGET.homePlugin,
    ]);
  });

  it('설정 2단계의 CTA가 해당 화면으로 간다 (DoD — 각 단계가 해당 화면으로 연결)', () => {
    expect(TOUR_STEPS[0]!.cta).toEqual({ label: '채널 연동하기', href: '/settings/channels' });
    expect(TOUR_STEPS[1]!.cta).toEqual({ label: '연동 코드 발급', href: '/settings/plugin' });
  });

  it('스포트라이트는 홈 위에서 아래 순서다 — 큰 칸 → 포키가 해 주는 일 → 지난 방송 → 오른쪽 패널(POK-378)', () => {
    expect(TOUR_STEPS.slice(2).map((s) => s.targetId)).toEqual([
      TOUR_TARGET.homeHero,
      TOUR_TARGET.homeFeatures,
      TOUR_TARGET.vodGrid,
      TOUR_TARGET.homeAside,
    ]);
  });

  it('웰컴 리스트는 6단계 카피를 가진다', () => {
    expect(WELCOME_STEPS).toHaveLength(6);
    expect(WELCOME_STEPS[0]!.label).toBe('채널 연동하기');
  });
});

describe('stepDone', () => {
  it('채널·플러그인만 완료 개념이 있다', () => {
    const flags = { channelLinked: true, pluginLinked: false };
    expect(stepDone('channel', flags)).toBe(true);
    expect(stepDone('plugin', flags)).toBe(false);
    expect(stepDone('hero', { channelLinked: true, pluginLinked: true })).toBe(false);
  });
});

describe('nextVisibleStep', () => {
  it('정방향·역방향으로 한 칸씩 이동한다', () => {
    expect(nextVisibleStep(0, 1, allTargets)).toBe(1);
    expect(nextVisibleStep(3, -1, allTargets)).toBe(2);
  });

  it('타깃 없는 스포트라이트 스텝은 건너뛴다', () => {
    const onlyAside = (id: string) => id === TOUR_TARGET.homeAside;
    expect(nextVisibleStep(1, 1, onlyAside)).toBe(5);
    expect(nextVisibleStep(5, -1, onlyAside)).toBe(1);
  });

  it('끝을 넘으면 null — 정방향 null은 완료 처리 신호다', () => {
    expect(nextVisibleStep(5, 1, allTargets)).toBeNull();
    expect(nextVisibleStep(0, -1, allTargets)).toBeNull();
    expect(nextVisibleStep(1, 1, noTargets)).toBeNull();
  });

  it('isStepVisible — 중앙 카드 대체가 있는 스텝은 항상, 나머지 스포트라이트는 타깃이 있어야 보인다', () => {
    expect(isStepVisible(0, noTargets)).toBe(true);
    expect(isStepVisible(2, noTargets)).toBe(false);
    expect(isStepVisible(6, allTargets)).toBe(false);
  });

  it('isLastStep은 6번째 스텝에서만 참이다', () => {
    expect(isLastStep(5)).toBe(true);
    expect(isLastStep(4)).toBe(false);
  });
});

describe('spotlightRect', () => {
  it('대상 사각형에 여백 10px을 두른다 (디자인 규칙)', () => {
    expect(spotlightRect({ top: 100, left: 50, width: 200, height: 80 })).toEqual({
      top: 90,
      left: 40,
      width: 220,
      height: 100,
    });
  });
});

describe('tooltipPosition', () => {
  const viewport = { width: 1440, height: 900 };
  const tooltip = { width: 330, height: 160 };

  it('대상 아래에 공간이 있으면 아래에 붙인다', () => {
    const pos = tooltipPosition(
      { top: 100, left: 200, width: 300, height: 200 },
      tooltip,
      viewport,
    );
    expect(pos.placement).toBe('below');
    expect(pos.top).toBe(312); // 100 + 200 + gap 12
    expect(pos.left).toBe(200);
  });

  it('아래 공간이 부족하면 위로 올린다', () => {
    const pos = tooltipPosition(
      { top: 700, left: 200, width: 300, height: 150 },
      tooltip,
      viewport,
    );
    expect(pos.placement).toBe('above');
    expect(pos.top).toBe(528); // 700 - 12 - 160
  });

  it('좌우는 뷰포트 여백 안으로 클램프된다', () => {
    const nearRight = tooltipPosition(
      { top: 100, left: 1400, width: 30, height: 30 },
      tooltip,
      viewport,
    );
    expect(nearRight.left).toBe(1440 - 330 - 16);

    const nearLeft = tooltipPosition(
      { top: 100, left: 2, width: 30, height: 30 },
      tooltip,
      viewport,
    );
    expect(nearLeft.left).toBe(16);
  });
});
