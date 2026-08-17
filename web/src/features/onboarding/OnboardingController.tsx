'use client';

import { useOnboardingHydration, useOnboardingStore } from '@/stores/onboarding';
import { TourChip } from './TourChip';
import { TourOverlay } from './TourOverlay';
import { WelcomeDialog } from './WelcomeDialog';

// 홈 온보딩 조립부 (POK-113) — 웰컴 다이얼로그 · 코치마크 투어 · 재진입 칩.
// hydrated 전에는 아무것도 그리지 않는다 — 서버 렌더와 클라 첫 렌더가 같아야
// 하이드레이션이 안 어긋난다 (출력은 전부 Portal이라 홈 DOM 구조와 무관).
export function OnboardingController() {
  useOnboardingHydration();
  const hydrated = useOnboardingStore((s) => s.hydrated);
  const welcomeSeen = useOnboardingStore((s) => s.welcomeSeen);
  const tourStep = useOnboardingStore((s) => s.tourStep);

  if (!hydrated) return null;
  if (tourStep !== null) return <TourOverlay stepIndex={tourStep} />;
  if (!welcomeSeen) return <WelcomeDialog />;
  return <TourChip />;
}
