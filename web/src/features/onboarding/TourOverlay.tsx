'use client';

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Check } from 'lucide-react';
import { Badge, Button, FocusScope, Portal, useScrollLock } from '@/ui';
import { useOnboardingStore } from '@/stores/onboarding';
import {
  TOUR_STEPS,
  isLastStep,
  nextVisibleStep,
  spotlightRect,
  stepDone,
  tooltipPosition,
  type Rect,
} from './tourSteps';
import styles from './Onboarding.module.css';

// 코치마크 투어 오버레이 (디자인 1a ②·④ 규칙).
// 1·2단계는 중앙 카드(전체 딤), 3~6단계는 스포트라이트 — 링 하나가 transition으로
// 다음 대상까지 미끄러진다. 배경 클릭·ESC는 잠금(디자인 "배경 클릭 잠금") — 종료는 건너뛰기·완료만.
// CTA는 router.push로 설정 화면에 다녀온다 — tourStep이 스토어 메모리에 남아 홈 복귀 시 같은 스텝 재개.

function hasTargetInDom(targetId: string): boolean {
  return document.querySelector(`[data-tour-id="${targetId}"]`) !== null;
}

export function TourOverlay({ stepIndex }: { stepIndex: number }) {
  const router = useRouter();
  const setTourStep = useOnboardingStore((s) => s.setTourStep);
  const skipTour = useOnboardingStore((s) => s.skipTour);
  const completeTour = useOnboardingStore((s) => s.completeTour);
  const channelLinked = useOnboardingStore((s) => s.channelLinked);
  const pluginLinked = useOnboardingStore((s) => s.pluginLinked);

  const step = TOUR_STEPS[stepIndex];
  const cardRef = useRef<HTMLDivElement>(null);
  const [spot, setSpot] = useState<Rect | null>(null);
  const [cardPos, setCardPos] = useState<{ top: number; left: number } | null>(null);

  useScrollLock(true);

  // 스포트라이트 측정 — 타깃 소실 시 다음 보이는 스텝으로, 전부 없으면 완료.
  const measure = useCallback(() => {
    if (!step || step.kind !== 'spotlight' || !step.targetId) {
      setSpot(null);
      setCardPos(null);
      return;
    }
    const el = document.querySelector(`[data-tour-id="${step.targetId}"]`);
    if (!el) {
      const next = nextVisibleStep(stepIndex, 1, hasTargetInDom);
      if (next === null) completeTour();
      else setTourStep(next);
      return;
    }
    const r = el.getBoundingClientRect();
    setSpot(spotlightRect({ top: r.top, left: r.left, width: r.width, height: r.height }));
  }, [step, stepIndex, setTourStep, completeTour]);

  // 스텝 진입 — 대상을 화면 안으로 끌어온 뒤 측정한다.
  useLayoutEffect(() => {
    if (step?.kind === 'spotlight' && step.targetId) {
      document
        .querySelector(`[data-tour-id="${step.targetId}"]`)
        ?.scrollIntoView({ block: 'nearest' });
    }
    measure();
  }, [step, measure]);

  // 카드 크기를 잰 뒤 배치하는 2-pass — 배치 전 프레임은 CSS가 visibility로 숨긴다.
  useLayoutEffect(() => {
    if (!spot || !cardRef.current) return;
    const rect = cardRef.current.getBoundingClientRect();
    const pos = tooltipPosition(
      spot,
      { width: rect.width || 330, height: rect.height || 160 },
      { width: window.innerWidth, height: window.innerHeight },
    );
    setCardPos({ top: pos.top, left: pos.left });
  }, [spot]);

  // 리사이즈·스크롤 재측정 (rAF 스로틀).
  useEffect(() => {
    let raf = 0;
    const onViewportChange = () => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(measure);
    };
    window.addEventListener('resize', onViewportChange);
    window.addEventListener('scroll', onViewportChange, { capture: true, passive: true });
    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener('resize', onViewportChange);
      window.removeEventListener('scroll', onViewportChange, { capture: true });
    };
  }, [measure]);

  // 스텝 변경 시 카드로 포커스 이동 — aria-label 변경이 보조기기에 낭독된다.
  useEffect(() => {
    cardRef.current?.focus();
  }, [stepIndex]);

  // 스텝 인덱스가 범위를 벗어나면 종료 (백스탑).
  useEffect(() => {
    if (!step) completeTour();
  }, [step, completeTour]);
  if (!step) return null;

  const goNext = () => {
    const next = nextVisibleStep(stepIndex, 1, hasTargetInDom);
    if (next === null) completeTour();
    else setTourStep(next);
  };
  const goPrev = () => {
    const prev = nextVisibleStep(stepIndex, -1, hasTargetInDom);
    if (prev !== null) setTourStep(prev);
  };

  const done = stepDone(step.id, { channelLinked, pluginLinked });
  const last = isLastStep(stepIndex);
  const spotlight = step.kind === 'spotlight';

  const card = (
    <FocusScope>
      <div
        ref={cardRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={`시작 가이드 ${stepIndex + 1}/${TOUR_STEPS.length} — ${step.title}`}
        className={
          spotlight ? `${styles.tourCard} ${styles.spotCard}` : `${styles.tourCard} ${styles.centeredCard}`
        }
        style={spotlight && cardPos ? { top: cardPos.top, left: cardPos.left } : undefined}
        data-pending={spotlight && !cardPos ? true : undefined}
      >
        <div className={styles.cardTitleRow}>
          <Badge tone="point" variant="soft" size="sm">
            {stepIndex + 1}/{TOUR_STEPS.length}
          </Badge>
          <span className={styles.cardTitle}>{step.title}</span>
          {/* DoD 체크 표면 — 채널 연동 후 홈 복귀 시 1단계 카드에 "완료"가 보인다 */}
          {done && (
            <Badge tone="success" variant="soft" size="sm">
              <Check aria-hidden className={styles.cardDoneCheck} />
              완료
            </Badge>
          )}
        </div>
        <p className={styles.cardBody}>{step.body}</p>
        <div className={styles.cardFooter}>
          <span className={styles.dots} aria-hidden>
            {TOUR_STEPS.map((s, i) => (
              <span key={s.id} className={i === stepIndex ? styles.dotActive : styles.dot} />
            ))}
          </span>
          <span className={styles.cardActions}>
            <Button variant="ghost" size="sm" onClick={skipTour}>
              건너뛰기
            </Button>
            {stepIndex > 0 && (
              <Button variant="outline" size="sm" onClick={goPrev}>
                이전
              </Button>
            )}
            {step.cta && (
              <Button variant="outline" size="sm" onClick={() => router.push(step.cta!.href)}>
                {step.cta.label}
              </Button>
            )}
            <Button variant="solid" size="sm" onClick={goNext}>
              {last ? '완료' : '다음'}
            </Button>
          </span>
        </div>
      </div>
    </FocusScope>
  );

  return (
    <Portal>
      {spotlight ? (
        <div className={styles.spotRoot}>
          <div className={styles.spotBlocker} aria-hidden />
          {spot && (
            <div
              className={styles.spotRing}
              aria-hidden
              style={{ top: spot.top, left: spot.left, width: spot.width, height: spot.height }}
            />
          )}
          {card}
        </div>
      ) : (
        <div className={styles.centeredBackdrop}>{card}</div>
      )}
    </Portal>
  );
}
