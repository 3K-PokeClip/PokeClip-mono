import Link from 'next/link';
import { Badge, Card } from '@/ui';
import styles from './HomeScreen.module.css';
import type { RecentCard } from './useHomeMockState';

function timeAgo(iso: string, now: number): string {
  const diff = Math.max(0, Math.floor((now - Date.parse(iso)) / 1000));
  if (diff < 60) return '방금';
  if (diff < 3600) return `${Math.floor(diff / 60)}분 전`;
  if (diff < 86_400) return `${Math.floor(diff / 3600)}시간 전`;
  return `${Math.floor(diff / 86_400)}일 전`;
}

// 최근 하이라이트 카드 — clip jump-cards 실값. 라이브·지난 방송을 합쳐 최신순.
export function RecentCardsCard({ cards, loading }: { cards: RecentCard[]; loading: boolean }) {
  const now = Date.now();
  return (
    <Card variant="outline" padding={0}>
      <div className={styles.asideCardHeader}>
        <h2 className={styles.asideCardTitle}>최근 하이라이트 카드</h2>
        <span className={styles.asideCardNote}>라이브 · 지난 방송 합산</span>
      </div>
      {cards.length === 0 ? (
        <p className={styles.emptyState}>{loading ? '불러오는 중…' : '감지된 카드가 없어요.'}</p>
      ) : (
        <ul className={styles.asideRows}>
          {cards.map((c) => (
            <li key={c.id} className={styles.asideRow}>
              <Badge tone={c.isLive ? 'danger' : 'neutral'} variant="soft" size="sm">
                {c.isLive ? 'LIVE' : 'VOD'}
              </Badge>
              <Link href={c.href} className={styles.asideRowTitle}>
                {c.streamId} · {c.positionLabel}
              </Link>
              <span className={styles.asideRowNote}>
                {c.source === 'hotkey' ? '단축키' : '자동'}
                {c.score !== null ? ` · 점수 ${c.score}` : ''} · {timeAgo(c.createdAt, now)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
