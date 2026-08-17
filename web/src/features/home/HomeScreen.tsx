'use client';

import styles from './HomeScreen.module.css';
import { ExpiringVodCard } from './ExpiringVodCard';
import { LiveNowBand } from './LiveNowBand';
import { PublishStatusCard } from './PublishStatusCard';
import { ResumeEditBanner } from './ResumeEditBanner';
import { VodGrid } from './VodGrid';
import { useHomeMockState } from './useHomeMockState';

// 디자인 1a — 홈 대시보드 (하이파이 상태).
// 빈 상태·시작 가이드는 POK-113에서 이 화면의 분기로 붙는다.
export function HomeScreen() {
  const { userName, greeting, resumeDraft, dismissResume, live, vods, publishRows, expiringVods } =
    useHomeMockState();

  return (
    <div>
      <div className={styles.greeting}>
        <h1 className={styles.greetingTitle}>
          {greeting}, {userName}님
        </h1>
        <p className={styles.greetingSub}>방송이 끝나기 전에, 클립은 이미 준비되고 있어요.</p>
      </div>
      <div className={styles.grid}>
        <div className={styles.main}>
          {resumeDraft ? <ResumeEditBanner draft={resumeDraft} onDismiss={dismissResume} /> : null}
          {live ? <LiveNowBand live={live} /> : null}
          <VodGrid vods={vods} />
        </div>
        <aside className={styles.aside} aria-label="발행·보관 현황">
          <PublishStatusCard rows={publishRows} />
          <ExpiringVodCard vods={expiringVods} />
        </aside>
      </div>
    </div>
  );
}
