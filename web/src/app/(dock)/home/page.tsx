import { GlobalHeader } from '@/components/app-shell/GlobalHeader';
import { ScreenContainer } from '@/components/app-shell/ScreenContainer';
import { ScreenTransition } from '@/components/app-shell/ScreenTransition';
import { HomeScreen } from '@/features/home/HomeScreen';
import styles from '@/features/home/HomeScreen.module.css';

export const metadata = { title: '홈 · PokeClip' };

// 독 1 — 홈 대시보드 (디자인 1a)
// 브랜드 헤더는 디자인 1a 홈 전용이다. 설정·클립은 사이드바가 그 자리를 대신한다.
// .page 래퍼가 헤더 뒤까지 내려오는 전체 폭 상단 그라디언트를 그린다.
export default function HomePage() {
  return (
    <ScreenTransition>
      <div className={styles.page}>
        <GlobalHeader />
        <ScreenContainer>
          <HomeScreen />
        </ScreenContainer>
      </div>
    </ScreenTransition>
  );
}
