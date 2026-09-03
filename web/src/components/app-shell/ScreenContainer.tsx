import type { ReactNode } from 'react';
import styles from './ScreenContainer.module.css';

// 사이드바 없는 독 화면(홈)의 본문 폭·여백 — 클립은 POK-235로 사이드바 화면이 됐다.
// (dock)/layout.tsx가 <main>을 제공하지 않으므로 본문 랜드마크는 여기서 세운다 —
// 자체 헤더를 가진 라이브(LiveScreen)와 설정 레이아웃은 각자 <main>을 세운다.
export function ScreenContainer({ children }: { children: ReactNode }) {
  return <main className={styles.container}>{children}</main>;
}
