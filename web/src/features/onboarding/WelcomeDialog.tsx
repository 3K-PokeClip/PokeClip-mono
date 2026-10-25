'use client';

import { useCallback, useEffect } from 'react';
import { Check } from 'lucide-react';
import { Button, FocusScope, Portal, VisuallyHidden, useScrollLock } from '@/ui';
import { BrandSymbol } from '@/components/app-shell/BrandSymbol';
import { track } from '@/lib/analytics';
import { useOnboardingStore } from '@/stores/onboarding';
import { TOUR_STEPS, WELCOME_STEPS, stepDone } from './tourSteps';
import styles from './Onboarding.module.css';

// 웰컴 다이얼로그 (디자인 1a ① — 첫 진입 시 1회).
// DS Dialog 대신 프리미티브 조합인 이유: 디자인이 백드롭 클릭 잠금 + 전용 딤(rgb(5 7 13/.58))을
// 요구하는데 Dialog는 dismiss·오버레이 색이 고정이다. ESC만 "나중에 볼게요"와 같게 둔다(a11y 탈출구).
// 목록은 지금 홈에서 실제로 짚을 단계만 — 막 가입 계정에 6개를 늘어놓으면 투어가 4번에서 끝날 때 어색하다(POK-378).
export function WelcomeDialog({ visible }: { visible: readonly number[] }) {
  const steps = WELCOME_STEPS.filter((step) => visible.some((i) => TOUR_STEPS[i]?.id === step.id));
  const dismissWelcome = useOnboardingStore((s) => s.dismissWelcome);
  const startTour = useOnboardingStore((s) => s.startTour);
  const channelLinked = useOnboardingStore((s) => s.channelLinked);
  const pluginLinked = useOnboardingStore((s) => s.pluginLinked);

  useScrollLock(true);

  // 안내를 시작하지 않고 닫음 — 한 번만 센다(이미 본 창이면 보내지 않는다) (POK-357)
  const later = useCallback(() => {
    if (!useOnboardingStore.getState().welcomeSeen) {
      track('onboarding_tour_finished', { outcome: 'later' });
    }
    dismissWelcome();
  }, [dismissWelcome]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') later();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [later]);

  return (
    <Portal>
      <div className={styles.welcomeBackdrop}>
        <FocusScope>
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="onboarding-welcome-title"
            aria-describedby="onboarding-welcome-desc"
            className={styles.welcomeCard}
          >
            <BrandSymbol size={36} className={styles.welcomeLogo} />
            <h2 id="onboarding-welcome-title" className={styles.welcomeTitle}>
              PokeClip에 오신 걸 환영해요
            </h2>
            <p id="onboarding-welcome-desc" className={styles.welcomeDesc}>
              방송만 켜면 하이라이트가 자동으로 쌓여요. 설정 2단계와 홈 화면 핵심 기능을 1분 만에
              짚어드릴게요.
            </p>
            <ol className={styles.welcomeSteps}>
              {steps.map((step, i) => {
                const done = stepDone(step.id, { channelLinked, pluginLinked });
                return (
                  <li key={step.id} className={styles.welcomeStep}>
                    <span className={styles.welcomeStepNo} data-done={done || undefined}>
                      {done ? <Check aria-hidden /> : i + 1}
                    </span>
                    <span className={styles.welcomeStepLabel}>{step.label}</span>
                    {/* DoD "단계 완료 시 체크 표시" — 보조기기에도 완료가 읽히게 한다 */}
                    {done && <VisuallyHidden>완료</VisuallyHidden>}
                  </li>
                );
              })}
            </ol>
            <div className={styles.welcomeActions}>
              <Button variant="ghost" size="sm" onClick={later}>
                나중에 볼게요
              </Button>
              <span className={styles.welcomeStart}>
                <Button
                  variant="solid"
                  size="sm"
                  onClick={() => {
                    startTour();
                    track('onboarding_tour_started', { entry: 'welcome' });
                  }}
                >
                  둘러보기 시작
                </Button>
              </span>
            </div>
          </div>
        </FocusScope>
      </div>
    </Portal>
  );
}
