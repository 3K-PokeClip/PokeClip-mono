import { GlobalHeader } from '@/components/app-shell/GlobalHeader';
import { ScreenContainer } from '@/components/app-shell/ScreenContainer';
import { ScreenTransition } from '@/components/app-shell/ScreenTransition';
import { ChannelScope } from '@/features/channel/ChannelScope';
import { HeroSkeleton } from '@/features/home/HomeHero';
import { HomeScreen } from '@/features/home/HomeScreen';
import { OnboardingController } from '@/features/onboarding/OnboardingController';
import styles from '@/features/home/HomeScreen.module.css';

export const metadata = { title: '홈 · PokeClip' };

// 독 1 — 홈 대시보드 (디자인 1a)
// 브랜드 헤더는 디자인 1a 홈 전용이다. 설정·클립은 사이드바가 그 자리를 대신한다.
// .page 래퍼가 헤더 뒤까지 내려오는 전체 폭 상단 그라디언트를 그린다.
// 온보딩(웰컴·투어·칩)은 홈 전용이라 여기서 붙는다 — 출력은 전부 Portal (POK-113).
// 채널 범위(POK-390)는 헤더 아래만 감싼다(POK-442): 헤더는 me를 기다리지 않고 바로 서고, 본문은 채널이 설 때까지 홈 첫
// 로딩과 같은 큰 칸 뼈대로 기다린다. 온보딩도 범위 안이다: 투어가 남의 채널인지(useChannelRelation)를 읽는다.
export default function HomePage() {
  return (
    <ScreenTransition>
      <div className={styles.page}>
        <GlobalHeader />
        <ChannelScope
          fallback={
            <ScreenContainer>
              <HeroSkeleton />
            </ScreenContainer>
          }
        >
          <ScreenContainer>
            <HomeScreen />
          </ScreenContainer>
          <OnboardingController />
        </ChannelScope>
      </div>
    </ScreenTransition>
  );
}
