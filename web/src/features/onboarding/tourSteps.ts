// 시작 가이드 투어의 순수 모델 (POK-113) — 스텝 정의·이동·배치 수학.
// DOM을 모르는 순수 함수만 둔다 (dockTransition.ts·playerMath.ts 선례) — 측정은 TourOverlay가 한다.

/** 스포트라이트 타깃 id의 단일 진실원 — 홈 컴포넌트가 data-tour-id로 단다. */
export const TOUR_TARGET = {
  liveBand: 'live-band',
  vodGrid: 'vod-grid',
  homeAside: 'home-aside',
  resumeBanner: 'resume-banner',
} as const;

export type TourTargetId = (typeof TOUR_TARGET)[keyof typeof TOUR_TARGET];
export type TourStepId = 'channel' | 'plugin' | 'live' | 'vod' | 'publish' | 'resume';

export interface TourStep {
  id: TourStepId;
  /** centered=중앙 카드(설정 2단계, 전체 딤) · spotlight=대상 강조 */
  kind: 'centered' | 'spotlight';
  title: string;
  body: string;
  targetId?: TourTargetId;
  cta?: { label: string; href: string };
}

// 카피는 디자인(PokeClip UI.dc.html 1a 코치마크) 원문 그대로.
export const TOUR_STEPS: readonly TourStep[] = [
  {
    id: 'channel',
    kind: 'centered',
    title: '채널 연동',
    body: '치지직 · SOOP · 유튜브 채널을 연결하면 방송 감지가 시작돼요. 설정 → 채널 연동에서 언제든 추가·해제할 수 있어요.',
    cta: { label: '채널 연동하기', href: '/settings/channels' },
  },
  {
    id: 'plugin',
    kind: 'centered',
    title: 'OBS 플러그인 설치',
    body: 'PokeClip 플러그인을 설치하고 연동 코드 8자리를 입력하면 방송 화면·채팅이 실시간으로 연결돼요.',
    cta: { label: '연동 코드 발급', href: '/settings/plugin' },
  },
  {
    id: 'live',
    kind: 'spotlight',
    targetId: TOUR_TARGET.liveBand,
    title: '실시간 하이라이트 감지',
    body: '방송이 켜지면 채팅 급증·키워드를 분석해 하이라이트 카드가 자동으로 쌓여요. 진행 상황은 이 라이브 밴드에서 확인해요.',
  },
  {
    id: 'vod',
    kind: 'spotlight',
    targetId: TOUR_TARGET.vodGrid,
    title: '지난 방송에서도 클립',
    body: 'VOD는 60일 동안 보관돼요. 지난 방송에서 감지된 카드를 골라 언제든 클립으로 만들 수 있어요.',
  },
  {
    id: 'publish',
    kind: 'spotlight',
    targetId: TOUR_TARGET.homeAside,
    title: '발행 현황 · 만료 임박',
    body: '업로드 진행률·예약 상태와 만료가 가까운 VOD를 오른쪽 패널에서 한눈에 챙겨요.',
  },
  {
    id: 'resume',
    kind: 'spotlight',
    targetId: TOUR_TARGET.resumeBanner,
    title: '하다 만 편집은 이어서',
    body: '편집을 끝내지 못해도 자동 저장돼요. 홈에 돌아오면 이어서 편집을 가장 먼저 권해드려요.',
  },
];

/** 웰컴 다이얼로그의 6단계 리스트 (디자인 카피 원문 — 투어 카드 제목과 다르다). */
export const WELCOME_STEPS: readonly { id: TourStepId; label: string }[] = [
  { id: 'channel', label: '채널 연동하기' },
  { id: 'plugin', label: 'OBS 플러그인 설치' },
  { id: 'live', label: '실시간 하이라이트 감지' },
  { id: 'vod', label: '지난 방송·VOD에서 클립 만들기' },
  { id: 'publish', label: '발행 현황·만료 임박 챙기기' },
  { id: 'resume', label: '이어서 편집으로 복귀' },
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
  return step.kind === 'centered' || (step.targetId !== undefined && hasTarget(step.targetId));
}

/**
 * from에서 dir 방향으로 다음 보이는 스텝을 찾는다.
 * 타깃이 없는 스포트라이트 스텝은 건너뛴다 (라이브 오프라인·배너 닫힘 대비).
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
