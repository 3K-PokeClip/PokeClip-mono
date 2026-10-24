import type { ReactNode } from 'react';
import { ScreenTransition } from '@/components/app-shell/ScreenTransition';
import { ScreenSkeleton } from '@/components/app-shell/ScreenSkeleton';
import { Side } from '@/components/app-shell/Side';
import styles from '@/components/app-shell/dockShell.module.css';
import { ChannelScope } from '@/features/channel/ChannelScope';

// 방송 하위 화면 공용 셸 — 좌측 사이드바 + 콘텐츠 (설정 레이아웃과 같은 구조)
//
// 슬라이드 래퍼가 page가 아니라 여기 붙는다 — 설정과 같은 이유로, 사이드바까지 한 덩어리로
// 움직여야 하고 그룹 안 이동(라이브 대시보드 ↔ 지난 방송)에서는 슬라이드가 돌지 않아야 한다.
export default function BroadcastLayout({ children }: { children: ReactNode }) {
  return (
    <ScreenTransition>
      <div className={styles.shell}>
        <Side menu="broadcast" />
        {/* 본문 랜드마크는 각 화면이 세운다 — 라이브 대시보드는 자체 헤더 아래 <main>을 갖는다 */}
        <div className={styles.content}>
          {/* 채널 범위는 본문만 감싼다(POK-442): 채널이 서기 전에도 사이드바는 서고 본문은 뼈대로 기다린다.
              방송 고정 화면(지난 방송 상세 · ?stream=)에서 채널을 바꾸면 홈으로 보내는 일도 이 범위가 한다 */}
          <ChannelScope fallback={<ScreenSkeleton />}>{children}</ChannelScope>
        </div>
      </div>
    </ScreenTransition>
  );
}
