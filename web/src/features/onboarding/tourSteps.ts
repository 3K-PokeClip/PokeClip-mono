// 시작 가이드 투어의 순수 모델 (POK-113) — 스텝 정의·이동·배치 수학.
// DOM을 모르는 순수 함수만 둔다 (dockTransition.ts·playerMath.ts 선례) — 측정은 TourOverlay가 한다.

/**
 * 스포트라이트 타깃 id의 단일 진실원 — 홈 컴포넌트가 data-tour-id로 단다.
 * 홈 개편(POK-378): 큰 칸은 늘 있고, 「포키가 해 주는 일」은 방송 기록이 없을 때만, 지난 방송·오른쪽 패널은 있을 때만 선다 —
 * 없는 타깃의 단계는 건너뛰므로 신규 계정은 1·2·3·4, 기록이 있는 계정은 1·2·3·5·6을 본다.
 * 1·2단계(방송 채널·플러그인)는 신규 계정이면 큰 칸의 준비 단계 줄, 기록이 있으면 오른쪽 연결 상태 줄을 짚는다 —
 * 둘 중 하나만 서므로 같은 id를 나눠 단다.
 */
export const TOUR_TARGET = {
  homeChannel: 'home-channel',
  homePlugin: 'home-plugin',
  homeHero: 'home-hero',
  homeFeatures: 'home-features',
  vodGrid: 'vod-grid',
  homeAside: 'home-aside',
} as const;

export type TourTargetId = (typeof TOUR_TARGET)[keyof typeof TOUR_TARGET];
export type TourStepId = 'channel' | 'plugin' | 'hero' | 'features' | 'vod' | 'aside';

export interface TourStep {
  id: TourStepId;
  /** centered=중앙 카드(전체 딤) · spotlight=대상 강조 */
  kind: 'centered' | 'spotlight';
  title: string;
  body: string;
  targetId?: TourTargetId;
  /**
   * 타깃이 없을 때 — 기본은 건너뛴다. 'centered'면 중앙 카드로 대신 보인다(홈을 아직 못 읽었을 때도
   * 설정 2단계는 빠지면 안 된다).
   */
  fallback?: 'centered';
  cta?: { label: string; href: string };
}

// 설정 2단계·지난 방송 카피는 디자인(PokeClip UI.dc.html 1a 코치마크) 원문 그대로. 큰 칸·포키·오른쪽 패널은 홈 개편(POK-378) 카피.
export const TOUR_STEPS: readonly TourStep[] = [
  {
    id: 'channel',
    kind: 'spotlight',
    targetId: TOUR_TARGET.homeChannel,
    fallback: 'centered',
    title: '채널 연동',
    body: '방송 플랫폼을 연결하면 방송 감지가 시작돼요. 설정 → 채널 연동에서 언제든 추가·해제할 수 있어요.',
    cta: { label: '채널 연동하기', href: '/settings/channels' },
  },
  {
    id: 'plugin',
    kind: 'spotlight',
    targetId: TOUR_TARGET.homePlugin,
    fallback: 'centered',
    title: 'OBS 플러그인 설치',
    body: 'PokeClip 플러그인을 설치하고 연동 코드 8자리를 입력하면 방송 화면·채팅이 실시간으로 연결돼요.',
    cta: { label: '연동 코드 발급', href: '/settings/plugin' },
  },
  {
    id: 'hero',
    kind: 'spotlight',
    targetId: TOUR_TARGET.homeHero,
    title: '맨 위 칸이 지금 상황을 알려줘요',
    body: '처음엔 준비 단계를, 방송을 시작한 뒤엔 이번 주에 찾은 카드와 만든 클립을 보여 줘요.',
  },
  {
    id: 'features',
    kind: 'spotlight',
    targetId: TOUR_TARGET.homeFeatures,
    title: '방송만 켜면 포키가 찾아요',
    body: '방송이 켜지면 채팅이 터진 순간을 하이라이트 카드로 모아요. 첫 방송이 끝나면 이 아래가 지난 방송으로 채워져요.',
  },
  {
    id: 'vod',
    kind: 'spotlight',
    targetId: TOUR_TARGET.vodGrid,
    title: '지난 방송에서도 클립',
    body: 'VOD는 60일 동안 보관돼요. 지난 방송에서 감지된 카드를 골라 언제든 클립으로 만들 수 있어요.',
  },
  {
    id: 'aside',
    kind: 'spotlight',
    targetId: TOUR_TARGET.homeAside,
    title: '연결 상태 · 포키의 팁',
    body: '방송 채널·유튜브 연결이 끊기면 여기서 바로 다시 연결해요. 팁은 들어올 때마다 바뀌어요.',
  },
];

/** 웰컴 다이얼로그의 단계 리스트 (투어 카드 제목과 다르다) — 홈에 보이는 단계만 추려 보인다(POK-378). */
export const WELCOME_STEPS: readonly { id: TourStepId; label: string }[] = [
  { id: 'channel', label: '채널 연동하기' },
  { id: 'plugin', label: 'OBS 플러그인 설치' },
  { id: 'hero', label: '맨 위 칸에서 지금 상황 보기' },
  { id: 'features', label: '방송 중 하이라이트 자동 감지' },
  { id: 'vod', label: '지난 방송·VOD에서 클립 만들기' },
  { id: 'aside', label: '연결 상태·팁 챙기기' },
];

export interface StepDoneFlags {
  channelLinked: boolean;
  pluginLinked: boolean;
}

/** 완료 체크 파생 — 설정 2단계만 완료 개념이 있다 (DoD "단계 완료 시 체크 표시"). */
export function stepDone(id: TourStepId, flags: StepDoneFlags): boolean {
  if (id === 'channel') return flags.channelLinked;
  if (id === 'plugin') return flags.pluginLinked;
  return false;
}

export function isStepVisible(index: number, hasTarget: (targetId: string) => boolean): boolean {
  const step = TOUR_STEPS[index];
  if (!step) return false;
  return (
    step.kind === 'centered' ||
    step.fallback === 'centered' ||
    (step.targetId !== undefined && hasTarget(step.targetId))
  );
}

/** 이 스텝을 지금 스포트라이트로 그리는가 — 타깃이 없고 중앙 카드로 대신하는 스텝이면 false */
export function isSpotlightNow(step: TourStep, hasTarget: (targetId: string) => boolean): boolean {
  return step.kind === 'spotlight' && step.targetId !== undefined && hasTarget(step.targetId);
}

/**
 * from에서 dir 방향으로 다음 보이는 스텝을 찾는다.
 * 타깃이 없는 스포트라이트 스텝은 건너뛴다(방송 기록 유무로 홈 짜임이 다르다). 중앙 카드 대체가 있는 스텝은 남긴다.
 * 끝을 넘으면 null — 정방향 null은 투어 완료로 처리한다.
 */
export function nextVisibleStep(
  from: number,
  dir: 1 | -1,
  hasTarget: (targetId: string) => boolean,
): number | null {
  for (let i = from + dir; i >= 0 && i < TOUR_STEPS.length; i += dir) {
    if (isStepVisible(i, hasTarget)) return i;
  }
  return null;
}

export function isLastStep(index: number): boolean {
  return index === TOUR_STEPS.length - 1;
}

export interface Rect {
  top: number;
  left: number;
  width: number;
  height: number;
}

/** 스포트라이트 링 사각형 — 대상 주변 여백 10px (디자인 규칙). */
export function spotlightRect(target: Rect, padding = 10): Rect {
  return {
    top: target.top - padding,
    left: target.left - padding,
    width: target.width + padding * 2,
    height: target.height + padding * 2,
  };
}

export interface TooltipPosition {
  top: number;
  left: number;
  placement: 'below' | 'above';
}

/** 툴팁 배치 — 대상 아래 우선, 공간 부족 시 위 (디자인 규칙). 좌우는 뷰포트에 클램프. */
export function tooltipPosition(
  spot: Rect,
  tooltip: { width: number; height: number },
  viewport: { width: number; height: number },
  gap = 12,
  margin = 16,
): TooltipPosition {
  const belowTop = spot.top + spot.height + gap;
  const placement: TooltipPosition['placement'] =
    belowTop + tooltip.height + margin <= viewport.height ? 'below' : 'above';
  const top = placement === 'below' ? belowTop : Math.max(margin, spot.top - gap - tooltip.height);
  const maxLeft = Math.max(margin, viewport.width - tooltip.width - margin);
  const left = Math.min(Math.max(margin, spot.left), maxLeft);
  return { top, left, placement };
}
