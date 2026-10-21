'use client';

import { useOnboardingHydration, useOnboardingStore } from '@/stores/onboarding';
import { useTourVisibility } from './tourDom';
import { TourChip } from './TourChip';
import { TourOverlay } from './TourOverlay';
import { WelcomeDialog } from './WelcomeDialog';

// 홈 온보딩 조립부 (POK-113) — 웰컴 다이얼로그 · 코치마크 투어 · 재진입 칩.
// hydrated 전에는 아무것도 그리지 않는다 — 서버 렌더와 클라 첫 렌더가 같아야
// 하이드레이션이 안 어긋난다 (출력은 전부 Portal이라 홈 DOM 구조와 무관).
// 몇 단계를 보여 줄지는 홈 짜임에 달렸다(막 가입 4 · 기록 있음 5, POK-378) — 웰컴 창은 홈을 다 읽은 뒤에 띄워
// 목록이 그 수와 맞게 하고, 투어의 「n/전체」·점도 같은 목록으로 센다.
export function OnboardingController() {
  useOnboardingHydration();
  const hydrated = useOnboardingStore((s) => s.hydrated);
  const welcomeSeen = useOnboardingStore((s) => s.welcomeSeen);
  const tourStep = useOnboardingStore((s) => s.tourStep);
  const { visible, pending } = useTourVisibility(tourStep !== null || !welcomeSeen);

  if (!hydrated) return null;
  if (tourStep !== null) {
    return <TourOverlay stepIndex={tourStep} visible={visible} pending={pending} />;
  }
  if (!welcomeSeen) return pending ? null : <WelcomeDialog visible={visible} />;
  return <TourChip />;
}
