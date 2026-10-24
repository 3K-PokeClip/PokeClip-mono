import type { ReactNode } from 'react';
import { ScreenTransition } from '@/components/app-shell/ScreenTransition';
import { ScreenSkeleton } from '@/components/app-shell/ScreenSkeleton';
import { Side } from '@/components/app-shell/Side';
import styles from '@/components/app-shell/dockShell.module.css';
import { ChannelScope } from '@/features/channel/ChannelScope';

// 설정 하위 화면 공용 셸 — 좌측 사이드바 + 콘텐츠 (디자인 Side 이식)
//
// 슬라이드 래퍼가 page가 아니라 여기 붙는 이유: 사이드바까지 한 덩어리로 움직여야 하고,
// 이 레이아웃은 다른 독 탭에서 들어올 때 마운트·나갈 때 언마운트되어 enter/exit가 발화한다.
// 설정 내부 이동에서는 유지되므로 슬라이드가 돌지 않는다 — 의도한 동작이다.
export default function SettingsLayout({ children }: { children: ReactNode }) {
  return (
    <ScreenTransition>
      <div className={styles.shell}>
        <Side menu="settings" />
        {/* 본문 랜드마크 — 사이드바 없는 화면은 ScreenContainer가 같은 역할을 한다 */}
        {/* 설정은 본문 패딩을 레이아웃이 잡는 유일한 그룹이다 — 방송·클립은 화면이 잡는다 */}
        <main className={styles.contentPadded}>
          {/* 채널 범위는 본문만 감싼다(POK-442): 채널이 서기 전에도 사이드바는 서고 본문은 뼈대로 기다린다.
              패딩은 이 <main>이 이미 주므로 패딩 없는 뼈대다 */}
          <ChannelScope fallback={<ScreenSkeleton padded={false} />}>{children}</ChannelScope>
        </main>
      </div>
    </ScreenTransition>
  );
}
