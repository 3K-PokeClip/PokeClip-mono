import type { ReactNode } from 'react';
import { ScreenTransition } from '@/components/app-shell/ScreenTransition';
import { SettingsSidebar } from '@/features/settings/SettingsSidebar';
import styles from './layout.module.css';

// 설정 하위 화면 공용 셸 — 좌측 사이드바 + 콘텐츠 (디자인 Side 이식)
//
// 슬라이드 래퍼가 page가 아니라 여기 붙는 이유: 사이드바까지 한 덩어리로 움직여야 하고,
// 이 레이아웃은 다른 독 탭에서 들어올 때 마운트·나갈 때 언마운트되어 enter/exit가 발화한다.
// 설정 내부 이동에서는 유지되므로 슬라이드가 돌지 않는다 — 의도한 동작이다.
export default function SettingsLayout({ children }: { children: ReactNode }) {
  return (
    <ScreenTransition>
      <div className={styles.shell}>
        <SettingsSidebar />
        <div className={styles.content}>{children}</div>
      </div>
    </ScreenTransition>
  );
}
