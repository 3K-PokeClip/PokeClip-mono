'use client';

import styles from './HomeScreen.module.css';
import { ExpiringVodCard } from './ExpiringVodCard';
import { LiveNowBand } from './LiveNowBand';
import { PublishStatusCard } from './PublishStatusCard';
import { RecentCardsCard } from './RecentCardsCard';
import { ResumeEditBanner } from './ResumeEditBanner';
import { VodGrid } from './VodGrid';
import { useHomeMockState } from './useHomeMockState';
import { TOUR_TARGET } from '@/features/onboarding/tourSteps';

// 디자인 1a — 홈 대시보드. 실값은 clip·auth에서 오고(POK-251), 창구가 없는 칸은 「준비 중」이다.
// 시작 가이드(웰컴·코치마크 투어)는 홈 page의 OnboardingController가 붙인다 (POK-113) —
// 이 화면은 스포트라이트 타깃(data-tour-id)만 노출한다.
export function HomeScreen() {
  const {
    userName,
    greeting,
    resumeDraft,
    resumeDismissed,
    dismissResume,
    live,
    vods,
    publishRows,
    publishUnavailable,
    expiringVods,
    recentCards,
    loading,
  } = useHomeMockState();

  return (
    <div>
      <div className={styles.greeting}>
        <h1 className={styles.greetingTitle}>
          {greeting}
          {userName ? `, ${userName}님` : ''}
        </h1>
        <p className={styles.greetingSub}>방송이 끝나기 전에, 클립은 이미 준비되고 있어요.</p>
      </div>
      <div className={styles.grid}>
        <div className={styles.main}>
          {resumeDraft && !resumeDismissed ? (
            <ResumeEditBanner draft={resumeDraft} onDismiss={dismissResume} />
          ) : null}
          {live ? (
            <LiveNowBand live={live} />
          ) : (
            <section
              aria-label="라이브"
              className={styles.liveSection}
              data-tour-id={TOUR_TARGET.liveBand}
            >
              <h2 className={styles.sectionLabel}>라이브</h2>
              <p className={styles.emptyState}>
                {loading ? '불러오는 중…' : '지금 방송 중인 채널이 없어요.'}
              </p>
            </section>
          )}
          <VodGrid vods={vods} loading={loading} />
          <RecentCardsCard cards={recentCards} loading={loading} />
        </div>
        <aside
          className={styles.aside}
          aria-label="발행·보관 현황"
          data-tour-id={TOUR_TARGET.homeAside}
        >
          <PublishStatusCard rows={publishRows} unavailable={publishUnavailable} />
          <ExpiringVodCard vods={expiringVods} loading={loading} />
        </aside>
      </div>
    </div>
  );
}
