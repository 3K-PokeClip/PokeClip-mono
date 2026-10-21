'use client';

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Check } from 'lucide-react';
import { Badge, Button, FocusScope, Portal, useScrollLock } from '@/ui';
import { track } from '@/lib/analytics';
import { useOnboardingStore } from '@/stores/onboarding';
import {
  TOUR_STEPS,
  nextVisibleStep,
  spotlightRect,
  stepDone,
  tooltipPosition,
  type Rect,
} from './tourSteps';
import { hasTargetInDom, isHomePending } from './tourDom';
import styles from './Onboarding.module.css';

// 코치마크 투어 오버레이 (디자인 1a ②·④ 규칙).
// 모든 단계가 스포트라이트다 — 링 하나가 transition으로 다음 대상까지 미끄러진다. 1·2단계(방송 채널·플러그인)는
// 홈의 해당 줄을 짚고, 그 줄이 아직 없으면(홈을 못 읽음) 중앙 카드(전체 딤)로 대신한다(POK-378).
// 배경 클릭·ESC는 잠금(디자인 "배경 클릭 잠금") — 종료는 건너뛰기·완료만.
// CTA는 router.push로 설정 화면에 다녀온다 — tourStep이 스토어 메모리에 남아 홈 복귀 시 같은 스텝 재개.

/**
 * 안내가 끝난 것을 한 번만 센다 (POK-357). 측정·백스탑 이펙트가 잇달아(StrictMode는 두 번) 끝낼 수 있어서
 * 이미 끝났으면(tourDone) 보내지 않는다. 스토어의 completeTour는 자동 종료와 마지막 단계를 가르지 못해 여기서 가른다.
 */
function trackTourFinished(outcome: 'completed' | 'skipped' | 'auto') {
  if (useOnboardingStore.getState().tourDone) return;
  track('onboarding_tour_finished', { outcome });
}

function sameRect(a: Rect, b: Rect): boolean {
  return a.top === b.top && a.left === b.left && a.width === b.width && a.height === b.height;
}

/**
 * @param visible 지금 홈에서 보일 단계(TOUR_STEPS 인덱스) — 「n/전체」·점·「완료」가 이것으로 센다
 * @param pending 홈이 아직 첫 응답을 기다리는 중이라 뒤 단계가 몇 개일지 모른다
 */
export function TourOverlay({
  stepIndex,
  visible,
  pending,
}: {
  stepIndex: number;
  visible: readonly number[];
  pending: boolean;
}) {
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

  // 스포트라이트 측정 — 타깃이 없으면 중앙 카드 대체가 있는 스텝은 중앙 카드로, 나머지는 다음 보이는 스텝으로
  // (전부 없으면 완료). 단 홈이 아직 읽는 중이면 타깃이 곧 서므로 건너뛰지 않고 중앙 카드로 기다린다(리뷰 #299).
  // spot이 서 있느냐가 곧 스포트라이트로 그리느냐다.
  const measure = useCallback(() => {
    const homePending = isHomePending();
    const el =
      step?.kind === 'spotlight' && step.targetId
        ? document.querySelector(`[data-tour-id="${step.targetId}"]`)
        : null;
    if (!step || !el) {
      setSpot(null);
      setCardPos(null);
      if (step?.kind === 'spotlight' && step.fallback !== 'centered' && !homePending) {
        const next = nextVisibleStep(stepIndex, 1, hasTargetInDom);
        if (next === null) {
          trackTourFinished('auto');
          completeTour();
        } else setTourStep(next);
      }
      return;
    }
    const r = el.getBoundingClientRect();
    const next = spotlightRect({ top: r.top, left: r.left, width: r.width, height: r.height });
    // 같은 자리면 그대로 둔다 — 아래 DOM 관찰이 다시 재도 다시 그리지 않게
    setSpot((prev) => (prev && sameRect(prev, next) ? prev : next));
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

  // 홈이 늦게 그려지거나(스켈레톤 → 실제 화면, 설정에서 돌아온 직후) 레이아웃이 바뀌면 다시 잰다 — 오버레이가 스크롤을
  // 잠가 scroll 이벤트가 오지 않고, 단계가 그대로면 위 레이아웃 이펙트도 다시 돌지 않는다(리뷰 #299).
  // 링·카드의 style 변경은 attributeFilter 밖이라 스스로를 다시 부르지 않는다.
  useEffect(() => {
    let raf = 0;
    const schedule = () => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(measure);
    };
    const mutations = new MutationObserver(schedule);
    mutations.observe(document.body, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['data-tour-id', 'data-tour-pending'],
    });
    const sizes = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(schedule);
    sizes?.observe(document.body);
    return () => {
      cancelAnimationFrame(raf);
      mutations.disconnect();
      sizes?.disconnect();
    };
  }, [measure]);

  // 스텝 변경 시 카드로 포커스 이동 — aria-label 변경이 보조기기에 낭독된다.
  useEffect(() => {
    cardRef.current?.focus();
  }, [stepIndex]);

  // 스텝 인덱스가 범위를 벗어나면 종료 (백스탑).
  useEffect(() => {
    if (step) return;
    trackTourFinished('auto');
    completeTour();
  }, [step, completeTour]);
  if (!step) return null;

  const goNext = () => {
    const next = nextVisibleStep(stepIndex, 1, hasTargetInDom);
    if (next !== null) {
      setTourStep(next);
      return;
    }
    // 홈을 아직 읽는 중이면 뒤 단계 타깃이 곧 선다 — 여기서 끝내면 4~6단계를 못 보고 tourDone이 남는다(리뷰 #299)
    if (isHomePending()) return;
    trackTourFinished('completed');
    completeTour();
  };
  const skip = () => {
    trackTourFinished('skipped');
    skipTour();
  };
  const goPrev = () => {
    const prev = nextVisibleStep(stepIndex, -1, hasTargetInDom);
    if (prev !== null) setTourStep(prev);
  };

  const done = stepDone(step.id, { channelLinked, pluginLinked });
  // 번호·점은 TOUR_STEPS 6개가 아니라 지금 보일 단계로 센다 — 막 가입 계정이 「4/6」에서 끝나 「왜 벌써 끝나지」가
  // 되지 않게(POK-378). 홈을 읽는 중이면 전체 수를 아직 모르니 번호만 보인다
  const shown = visible.includes(stepIndex)
    ? visible
    : [...visible, stepIndex].sort((a, b) => a - b);
  const position = pending
    ? `${shown.indexOf(stepIndex) + 1}`
    : `${shown.indexOf(stepIndex) + 1}/${shown.length}`;
  const hasNext = visible.some((i) => i > stepIndex);
  // 「완료」는 단계 번호가 아니라 실제로 다음에 보일 단계가 없을 때 — 기록 없는 계정은 4단계가 마지막이다(리뷰 #299).
  // 홈을 읽는 중이라 아직 모르면 「다음」을 잠근다
  const last = !hasNext && !pending;
  const waitingForHome = !hasNext && pending;
  // 측정 전 첫 프레임은 중앙 카드로 그려지지만, 측정이 레이아웃 이펙트라 칠하기 전에 스포트라이트로 바뀐다
  const spotlight = spot !== null;

  const card = (
    <FocusScope>
      <div
        ref={cardRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={`시작 가이드 ${position} — ${step.title}`}
        className={
          spotlight
            ? `${styles.tourCard} ${styles.spotCard}`
            : `${styles.tourCard} ${styles.centeredCard}`
        }
        style={spotlight && cardPos ? { top: cardPos.top, left: cardPos.left } : undefined}
        data-pending={spotlight && !cardPos ? true : undefined}
        data-wide={spotlight && step.cta ? true : undefined}
      >
        <div className={styles.cardTitleRow}>
          <Badge tone="point" variant="soft" size="sm">
            {position}
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
            {!pending &&
              shown.map((i) => (
                <span key={i} className={i === stepIndex ? styles.dotActive : styles.dot} />
              ))}
          </span>
          <span className={styles.cardActions}>
            <Button variant="ghost" size="sm" onClick={skip}>
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
            <Button variant="solid" size="sm" onClick={goNext} disabled={waitingForHome}>
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
