import type { ReactNode } from 'react';
import { ScreenTransition } from '@/components/app-shell/ScreenTransition';
import { Side } from '@/components/app-shell/Side';
import styles from './layout.module.css';

// 클립 하위 화면 공용 셸 — 좌측 사이드바 + 콘텐츠 (방송·설정 레이아웃과 같은 구조)
//
// 슬라이드 래퍼가 page가 아니라 여기 붙는다 — 사이드바까지 한 덩어리로 움직여야 하고
// 그룹 안 이동에서는 슬라이드가 돌지 않아야 한다(설정·방송과 같은 이유).
//
// 클립 편집기(/clips/editor)는 이 레이아웃 밖이다 — (fullscreen) 그룹에 있어 URL은 /clips
// 아래지만 라우트 그룹이 달라 여기를 거치지 않는다((fullscreen)/layout.test.tsx가 지킨다).
export default function ClipsLayout({ children }: { children: ReactNode }) {
  return (
    <ScreenTransition>
      <div className={styles.shell}>
        <Side menu="clips" />
        {/* 본문 랜드마크·패딩은 각 화면이 세운다 — 보관함은 우측 상세 패널이 본문 옆에 서야 해서
            레이아웃이 패딩을 주면 패널까지 밀린다 */}
        <div className={styles.content}>{children}</div>
      </div>
    </ScreenTransition>
  );
}
