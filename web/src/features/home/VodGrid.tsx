import Link from 'next/link';
import { Badge } from '@/ui';
import { TOUR_TARGET } from '@/features/onboarding/tourSteps';
import styles from './HomeScreen.module.css';
import { Thumb } from './Thumb';
import type { HomeVod } from './useHomeMockState';

// 디자인 1a ③ — 지난 방송·VOD 그리드. clip `state=past` 실값이다 (제목은 아직 streamId).
export function VodGrid({ vods, loading }: { vods: HomeVod[]; loading: boolean }) {
  return (
    <section aria-label="지난 방송 · VOD" data-tour-id={TOUR_TARGET.vodGrid}>
      <div className={styles.vodHeader}>
        <h2 className={styles.sectionLabel}>지난 방송 · VOD</h2>
        <span className={styles.vodKeepNote}>60일 보관</span>
        <Link href="/broadcast/vod" className={styles.mutedLink}>
          지난 방송 목록
        </Link>
      </div>
      {vods.length === 0 ? (
        <p className={styles.emptyState}>{loading ? '불러오는 중…' : '지난 방송이 없어요.'}</p>
      ) : (
        <ul className={styles.vodGrid}>
          {vods.map((vod) => (
            <li key={vod.id} className={styles.vodCard}>
              <Link href={vod.href} className={styles.vodLink}>
                <Thumb label="썸네일 준비 중" src={vod.thumbnailUrl}>
                  {vod.badge?.kind === 'preparing' ? (
                    <span className={styles.overlayPillTopLeft}>준비 중</span>
                  ) : null}
                  {vod.badge?.kind === 'dday' ? (
                    <span className={styles.overlayTopLeft}>
                      <Badge tone="danger" variant="solid" size="sm">
                        {vod.badge.label}
                      </Badge>
                    </span>
                  ) : null}
                  {vod.duration ? (
                    <span className={styles.durationPill}>{vod.duration}</span>
                  ) : null}
                </Thumb>
                <div className={styles.vodText}>
                  <div className={styles.vodTitle}>{vod.title}</div>
                  <div className={styles.vodMeta}>{vod.meta}</div>
                </div>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
