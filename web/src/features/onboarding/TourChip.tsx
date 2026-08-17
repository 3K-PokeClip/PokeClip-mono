'use client';

import { HelpCircle } from 'lucide-react';
import { Button, Portal } from '@/ui';
import { useOnboardingStore } from '@/stores/onboarding';
import styles from './Onboarding.module.css';

// 재진입 칩 (디자인 1a ③ — 투어 종료 후 프레임 우하단 고정).
// 웰컴 다이얼로그는 다시 열지 않는다 — 디자인 명세가 "코치마크 투어를 처음부터 다시 시작"이다.
export function TourChip() {
  const startTour = useOnboardingStore((s) => s.startTour);

  return (
    <Portal>
      <div className={styles.chip}>
        <Button variant="outline" size="sm" onClick={startTour} iconStart={<HelpCircle aria-hidden />}>
          둘러보기
        </Button>
      </div>
    </Portal>
  );
}
