'use client';

import { useEffect } from 'react';
import Image from 'next/image';
import { Check } from 'lucide-react';
import { Button, FocusScope, Portal, VisuallyHidden, useScrollLock } from '@/ui';
import { useOnboardingStore } from '@/stores/onboarding';
import { WELCOME_STEPS, stepDone } from './tourSteps';
import styles from './Onboarding.module.css';

// 웰컴 다이얼로그 (디자인 1a ① — 첫 진입 시 1회).
// DS Dialog 대신 프리미티브 조합인 이유: 디자인이 백드롭 클릭 잠금 + 전용 딤(rgb(5 7 13/.58))을
// 요구하는데 Dialog는 dismiss·오버레이 색이 고정이다. ESC만 "나중에 볼게요"와 같게 둔다(a11y 탈출구).
export function WelcomeDialog() {
  const dismissWelcome = useOnboardingStore((s) => s.dismissWelcome);
  const startTour = useOnboardingStore((s) => s.startTour);
  const channelLinked = useOnboardingStore((s) => s.channelLinked);
  const pluginLinked = useOnboardingStore((s) => s.pluginLinked);

  useScrollLock(true);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') dismissWelcome();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [dismissWelcome]);

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
            <Image
              src="/brand/pokeclip-symbol.svg"
              alt=""
              width={36}
              height={36}
              className={styles.welcomeLogo}
            />
            <h2 id="onboarding-welcome-title" className={styles.welcomeTitle}>
              PokeClip에 오신 걸 환영해요
            </h2>
            <p id="onboarding-welcome-desc" className={styles.welcomeDesc}>
              방송만 켜면 하이라이트가 자동으로 쌓여요. 설정 2단계와 홈 화면 핵심 기능을 1분 만에
              짚어드릴게요.
            </p>
            <ol className={styles.welcomeSteps}>
              {WELCOME_STEPS.map((step, i) => {
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
              <Button variant="ghost" size="sm" onClick={dismissWelcome}>
                나중에 볼게요
              </Button>
              <span className={styles.welcomeStart}>
                <Button variant="solid" size="sm" onClick={startTour}>
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
