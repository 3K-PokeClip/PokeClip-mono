'use client';

import { usePathname } from 'next/navigation';
import { ScreenSkeleton } from '@/components/app-shell/ScreenSkeleton';
import { LiveScreenSkeleton } from '@/features/broadcast/livenow/LiveScreenSkeleton';
import { VodListLoadingScreen } from '@/features/broadcast/vod/VodListSkeleton';
import { LibraryLoadingScreen } from '@/features/clips/library/LibrarySkeleton';

// 새로고침 직후 채널이 서기 전(POK-442) 방송·클립 본문 자리. 경로로 화면을 알면 그 화면이 첫 로딩에 그릴 뼈대를 그대로
// 그린다: 공통 뼈대(행 4줄)를 그렸다가 채널이 서면 화면이 제 뼈대로 다시 바꿔, 새로고침 한 번에 뼈대가 두 번 바뀌었다
// (PR #377 2차 리뷰). 모르는 경로만 공통 뼈대다. 그룹 레이아웃은 서버 컴포넌트라 경로를 읽는 이 조각만 클라이언트다.
export function DockScreenSkeleton() {
  const pathname = usePathname() ?? '';
  if (pathname === '/broadcast/vod') return <VodListLoadingScreen />;
  // 라이브 대시보드와 지난 방송 상세(/broadcast/vod/[streamId])는 같은 화면(LiveScreen)이다
  if (pathname === '/broadcast/livenow' || pathname.startsWith('/broadcast/vod/')) {
    return <LiveScreenSkeleton />;
  }
  if (pathname === '/clips/library') return <LibraryLoadingScreen />;
  return <ScreenSkeleton />;
}
