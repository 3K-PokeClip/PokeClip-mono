import { Badge, Card, Progress, type BadgeTone } from '@/ui';
import styles from './HomeScreen.module.css';
import type { PublishRow, PublishStatus } from './useHomeMockState';

const STATUS_BADGE: Record<PublishStatus, { label: string; tone: BadgeTone }> = {
  uploading: { label: '업로드 중', tone: 'neutral' },
  checking: { label: '확인 필요', tone: 'warning' },
  scheduled: { label: '예약됨', tone: 'neutral' },
  published: { label: '발행됨', tone: 'success' },
};

// 디자인 1a 우측 — 발행 현황 카드. 줄은 보관함의 유튜브 업로드에서 온다(POK-111).
// 업로드는 진행률을 주지 않아 막대가 없고, 조회수·예약도 아직 없어 보조 글이 없다.
export function PublishStatusCard({
  rows,
  unavailable = false,
}: {
  /** null = 보관함을 아직 못 읽었다(「올린 영상 없음」과 다르다) */
  rows: PublishRow[] | null;
  unavailable?: boolean;
}) {
  return (
    <Card variant="outline" padding={0}>
      <div className={styles.asideCardHeader}>
        <h2 className={styles.asideCardTitle}>발행 현황</h2>
        <span className={styles.mutedLink} aria-disabled="true">
          라이브러리
        </span>
      </div>
      {rows === null ? (
        <p className={styles.emptyState}>
          {unavailable ? '발행 현황을 불러오지 못했어요' : '발행 현황을 불러오는 중…'}
        </p>
      ) : rows.length === 0 ? (
        <p className={styles.emptyState}>아직 유튜브에 올린 영상이 없어요</p>
      ) : (
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
      )}
    </Card>
  );
}
