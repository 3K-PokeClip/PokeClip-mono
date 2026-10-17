import { Badge, Button, Card } from '@/ui';
import styles from './HomeScreen.module.css';
import type { ExpiringVod } from './useHomeMockState';

// 디자인 1a 우측 — 만료 임박 VOD 카드. clip `vodExpiresAt` 실값(7일 이내).
// "카드 저장"은 보관함 API가 없어 비활성 (DownloadBanner 선례).
export function ExpiringVodCard({ vods, loading }: { vods: ExpiringVod[]; loading: boolean }) {
  return (
    <Card variant="outline" padding={0}>
      <div className={styles.asideCardHeader}>
        <h2 className={styles.asideCardTitle}>만료 임박 VOD</h2>
        <span className={styles.asideCardNote}>만료 시 카드도 삭제</span>
      </div>
      {vods.length === 0 ? (
        <p className={styles.emptyState}>
          {loading ? '불러오는 중…' : '7일 안에 만료되는 VOD가 없어요.'}
        </p>
      ) : (
        <ul className={styles.asideRows}>
          {vods.map((vod) => (
            <li key={vod.id} className={styles.asideRow}>
              <Badge tone={vod.urgent ? 'danger' : 'neutral'} variant="soft" size="sm">
                {vod.dday}
              </Badge>
              <span className={styles.asideRowTitle}>{vod.title}</span>
              <Button variant="ghost" size="sm" disabled>
                카드 저장
              </Button>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
