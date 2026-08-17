import type { ReactNode } from 'react';
import styles from './ScreenContainer.module.css';

// 사이드바 없는 독 화면(홈·라이브·클립)의 본문 폭·여백
export function ScreenContainer({ children }: { children: ReactNode }) {
  return <div className={styles.container}>{children}</div>;
}
