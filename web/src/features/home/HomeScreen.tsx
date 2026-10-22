'use client';

import styles from './HomeScreen.module.css';
import { ConnectionCard } from './ConnectionCard';
import { HeroSkeleton, QuietWeekHero, SetupHome, WeekHero } from './HomeHero';
import { LiveNowBand } from './LiveNowBand';
import { TipCard } from './TipCard';
import { VodGrid } from './VodGrid';
import { useHomeMockState } from './useHomeMockState';
import { TOUR_TARGET } from '@/features/onboarding/tourSteps';

// 홈 대시보드 — 디자인 1a를 POK-378에서 개편했다. 맨 위 큰 칸 하나가 지금 상황을 말한다:
//   방송 기록 없음 → 첫 클립까지 가는 길 · 이번 주에 방송함 → 이번 주 기록 · 이번 주 0회 → 마지막 방송 + 지난주 기록.
// 그 아래 본문 맨 위엔 방송 중일 때만 라이브 밴드(옛 「이어서 편집」 자리), 이어 지난 방송 격자.
// 오른쪽은 늘 채워지는 연결 상태·포키의 팁(옛 발행 현황·만료 임박은 실데이터가 자주 비었다).
// 시작 가이드(웰컴·코치마크 투어)는 홈 page의 OnboardingController가 붙인다 (POK-113) —
// 이 화면은 스포트라이트 타깃(data-tour-id)만 노출한다.
export function HomeScreen() {
  const { userName, live, vods, hasHistory, week, lastWeek, lastBroadcast, links, loading } =
    useHomeMockState();

  if (loading) return <HeroSkeleton />;
  if (!hasHistory) return <SetupHome userName={userName} links={links} />;

  return (
    <div>
      {live || week.broadcasts > 0 ? (
        <WeekHero userName={userName} week={week} />
      ) : (
        <QuietWeekHero userName={userName} lastWeek={lastWeek} lastBroadcast={lastBroadcast} />
      )}
      <div className={styles.grid}>
        <div className={styles.main}>
          {live ? <LiveNowBand live={live} /> : null}
          <VodGrid vods={vods} />
        </div>
        <aside
          className={styles.aside}
          aria-label="연결 상태·팁"
          data-tour-id={TOUR_TARGET.homeAside}
        >
          <ConnectionCard links={links} />
          <TipCard />
        </aside>
      </div>
    </div>
  );
}
