import Link from 'next/link';
import { Tag } from '@/ui';
import { TOUR_TARGET } from '@/features/onboarding/tourSteps';
import styles from './HomeScreen.module.css';
import { Thumb } from './Thumb';
import type { LiveNow } from './useHomeMockState';

// 디자인 1a ② — 방송 중일 때만 노출되는 라이브 밴드.
// 두 액션 모두 라이브 대시보드(1b)로 간다 — "카드 검토"는 우측 카드 목록이 목적지다.
export function LiveNowBand({ live }: { live: LiveNow }) {
  return (
    <section aria-label="라이브" className={styles.liveSection} data-tour-id={TOUR_TARGET.liveBand}>
      <h2 className={styles.sectionLabel}>
        <span className={styles.livePulseDot} aria-hidden />
        라이브
      </h2>
      <div className={styles.liveBand}>
        <Thumb label="라이브 방송 화면" className={styles.liveThumb}>
          <span className={styles.livePill}>LIVE {live.uptimeLabel}</span>
          <span className={styles.viewerPill}>시청자 {live.viewers}</span>
        </Thumb>
        <div className={styles.liveBody}>
          <div className={styles.liveMetaRow}>
            <Tag variant="soft" size="sm">
              {live.platform}
            </Tag>
            <span className={styles.liveMetaText}>{live.startedNote}</span>
          </div>
          <div className={styles.liveTitle}>{live.title}</div>
          <div className={styles.liveStats}>
            감지된 카드 <b>{live.detectedCards}</b> · 클립 완료 <b>{live.completedClips}</b>
          </div>
          <div className={styles.liveActions}>
            <Link href="/broadcast/livenow" className={styles.solidLink}>
              대시보드 열기
            </Link>
            <Link href="/broadcast/livenow" className={styles.ghostLink}>
              카드 검토
            </Link>
          </div>
        </div>
      </div>
    </section>
  );
}
