import Link from 'next/link';
import { IconButton, LinkButton } from '@/ui';
import { Play, X } from 'lucide-react';
import { TOUR_TARGET } from '@/features/onboarding/tourSteps';
import styles from './HomeScreen.module.css';
import type { ResumeDraft } from './useHomeMockState';

// 디자인 1a 상단 — 편집하던 클립 이어가기 배너.
// draft는 보관함에서 가장 최근에 고친 「편집 중」 편집본(POK-243)이고, 진입 버튼은 그 편집본을 연다(POK-251).
export function ResumeEditBanner({
  draft,
  onDismiss,
}: {
  draft: ResumeDraft;
  onDismiss: () => void;
}) {
  return (
    <section
      className={styles.resumeBanner}
      aria-label="이어서 편집"
      data-tour-id={TOUR_TARGET.resumeBanner}
    >
      <div className={styles.resumeThumb} aria-hidden>
        <Play size={13} fill="currentColor" strokeWidth={0} />
      </div>
      <div className={styles.resumeBody}>
        <div className={styles.resumeTitle}>편집하던 클립이 있어요 — “{draft.title}”</div>
        <div className={styles.resumeMeta}>{draft.meta}</div>
      </div>
      <LinkButton as={Link} href={draft.href} variant="solid" size="sm">
        이어서 편집
      </LinkButton>
      <IconButton variant="ghost" size="sm" aria-label="배너 닫기" onClick={onDismiss}>
        <X size={14} aria-hidden />
      </IconButton>
    </section>
  );
}
