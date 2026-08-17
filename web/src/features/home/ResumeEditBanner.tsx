import { Button, IconButton } from '@/ui';
import { Play, X } from 'lucide-react';
import styles from './HomeScreen.module.css';
import type { ResumeDraft } from './useHomeMockState';

// 디자인 1a 상단 — 편집하던 클립 이어가기 배너.
// 클립 편집기는 M2(POK-107)라 진입 버튼은 비활성으로 둔다.
export function ResumeEditBanner({
  draft,
  onDismiss,
}: {
  draft: ResumeDraft;
  onDismiss: () => void;
}) {
  return (
    <section className={styles.resumeBanner} aria-label="이어서 편집">
      <div className={styles.resumeThumb} aria-hidden>
        <Play size={13} fill="currentColor" strokeWidth={0} />
      </div>
      <div className={styles.resumeBody}>
        <div className={styles.resumeTitle}>편집하던 클립이 있어요 — “{draft.title}”</div>
        <div className={styles.resumeMeta}>{draft.meta}</div>
      </div>
      <Button variant="solid" size="sm" disabled>
        이어서 편집
      </Button>
      <IconButton variant="ghost" size="sm" aria-label="배너 닫기" onClick={onDismiss}>
        <X size={14} aria-hidden />
      </IconButton>
    </section>
  );
}
