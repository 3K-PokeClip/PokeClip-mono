import { Badge } from '@/ui';
import { TOUR_TARGET } from '@/features/onboarding/tourSteps';
import styles from './HomeScreen.module.css';
import { Thumb } from './Thumb';
import type { HomeVod } from './useHomeMockState';

// 디자인 1a ③ — 지난 방송·VOD 그리드.
// "지난 방송 목록" 화면(1f)은 M2(POK-106)라 링크 자리만 둔다 (SettingsSidebar 선례).
export function VodGrid({ vods }: { vods: HomeVod[] }) {
  return (
    <section aria-label="지난 방송 · VOD" data-tour-id={TOUR_TARGET.vodGrid}>
      <div className={styles.vodHeader}>
        <h2 className={styles.sectionLabel}>지난 방송 · VOD</h2>
        <span className={styles.vodKeepNote}>60일 보관</span>
        <span className={styles.mutedLink} aria-disabled="true">
          지난 방송 목록
        </span>
      </div>
      <ul className={styles.vodGrid}>
        {vods.map((vod) => (
          <li key={vod.id} className={styles.vodCard}>
            <Thumb label="VOD 썸네일">
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
              {vod.duration ? <span className={styles.durationPill}>{vod.duration}</span> : null}
            </Thumb>
            <div className={styles.vodText}>
              <div className={styles.vodTitle}>{vod.title}</div>
              <div className={styles.vodMeta}>{vod.meta}</div>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
