import type { ReactNode } from 'react';
import { Dock } from '@/components/app-shell/Dock';
import { GlobalHeader } from '@/components/app-shell/GlobalHeader';
import styles from './layout.module.css';

// 독 4개 화면이 공유하는 셸 — 전역 헤더 + 하단 중앙 Dock (POK-99)
export default function DockLayout({ children }: { children: ReactNode }) {
  return (
    <>
      <GlobalHeader />
      <main className={styles.main}>{children}</main>
      <Dock />
    </>
  );
}
