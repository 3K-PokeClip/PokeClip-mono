import Link from 'next/link';
import { IconButton, LinkButton } from '@/ui';
import { Play, X } from 'lucide-react';
import { TOUR_TARGET } from '@/features/onboarding/tourSteps';
import styles from './HomeScreen.module.css';
import type { ResumeDraft } from './useHomeMockState';

// 디자인 1a 상단 — 편집하던 클립 이어가기 배너.
// 진입 버튼은 클립 편집기 목업으로 간다 — 이어받을 편집본을 실제로 여는 것은
// 레시피 배선(POK-107) 몫이라 아직 어떤 편집본인지 싣지 않는다.
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
      <LinkButton as={Link} href="/clips/editor" variant="solid" size="sm">
        이어서 편집
      </LinkButton>
      <IconButton variant="ghost" size="sm" aria-label="배너 닫기" onClick={onDismiss}>
        <X size={14} aria-hidden />
      </IconButton>
    </section>
  );
}
