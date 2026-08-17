import { Badge, Card, Progress, type BadgeTone } from '@/ui';
import styles from './HomeScreen.module.css';
import type { PublishRow, PublishStatus } from './useHomeMockState';

const STATUS_BADGE: Record<PublishStatus, { label: string; tone: BadgeTone }> = {
  uploading: { label: '업로드 중', tone: 'neutral' },
  scheduled: { label: '예약됨', tone: 'neutral' },
  published: { label: '발행됨', tone: 'success' },
};

// 디자인 1a 우측 — 발행 현황 카드. "라이브러리"(1g 보관함)는 M2라 링크 자리만.
export function PublishStatusCard({ rows }: { rows: PublishRow[] }) {
  return (
    <Card variant="outline" padding={0}>
      <div className={styles.asideCardHeader}>
        <h2 className={styles.asideCardTitle}>발행 현황</h2>
        <span className={styles.mutedLink} aria-disabled="true">
          라이브러리
        </span>
      </div>
      <ul className={styles.asideRows}>
        {rows.map((row) => {
          const badge = STATUS_BADGE[row.status];
          return (
            <li key={row.id} className={styles.asideRow}>
              <span className={styles.asideRowTitle}>{row.title}</span>
              {row.status === 'uploading' && row.progress != null ? (
                <Progress
                  value={row.progress}
                  size="sm"
                  label="업로드 진행률"
                  className={styles.rowProgress}
                />
              ) : null}
              {row.note ? <span className={styles.asideRowNote}>{row.note}</span> : null}
              <Badge tone={badge.tone} variant="soft" size="sm">
                {badge.label}
              </Badge>
            </li>
          );
        })}
      </ul>
    </Card>
  );
}
