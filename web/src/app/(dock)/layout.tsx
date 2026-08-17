import type { ReactNode } from 'react';
import { Dock } from '@/components/app-shell/Dock';

// 독 4개 화면이 공유하는 건 하단 Dock뿐이다. 헤더·사이드바는 화면마다 다르므로
// (디자인: 홈만 브랜드 헤더, 설정·클립은 최상단부터 전체 높이 사이드바) 각 화면이 직접 구성한다.
export default function DockLayout({ children }: { children: ReactNode }) {
  return (
    <>
      {children}
      <Dock />
    </>
  );
}
