import { Badge } from '@/ui';
import styles from './sections.module.css';

// 아직 서버 기능이 없는 도구의 머리 줄 — 메뉴는 남기고(사용자 확정, POK-389) 「준비 중」이라고만 말한다.

export function PendingNote({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className={styles.sectionHead}>
      <span className={styles.sectionTitle}>{title}</span>
      <Badge tone="neutral" variant="soft" size="sm">
        준비 중
      </Badge>
      {hint !== undefined ? <span className={styles.hint}>{hint}</span> : null}
    </div>
  );
}
