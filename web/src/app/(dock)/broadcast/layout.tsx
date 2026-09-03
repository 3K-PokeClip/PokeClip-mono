import type { ReactNode } from 'react';
import { ScreenTransition } from '@/components/app-shell/ScreenTransition';
import { Side } from '@/components/app-shell/Side';
import styles from '@/components/app-shell/dockShell.module.css';

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
        <div className={styles.content}>{children}</div>
      </div>
    </ScreenTransition>
  );
}
