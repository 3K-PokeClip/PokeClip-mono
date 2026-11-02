import type { ReactNode } from 'react';
import { AuthGuard } from '@/components/app-shell/AuthGuard';
import { Dock } from '@/components/app-shell/Dock';
import { ChannelSwitchCurtain } from '@/features/channel/ChannelSwitchCurtain';

// 독 4개 화면이 공유하는 건 하단 Dock뿐이다. 헤더·사이드바는 화면마다 다르므로
// (디자인: 홈만 브랜드 헤더, 설정·클립은 최상단부터 전체 높이 사이드바) 각 화면이 직접 구성한다.
// 독 라우트는 전부 로그인 전제라 가드도 여기서 한 번에 덮는다 (POK-101).
// 고른 채널(POK-390)의 범위(ChannelScope)는 여기가 아니라 각 화면 그룹이 본문만 감싼다(POK-442): 여기서 화면 전체를
// 감싸면 새로고침 뒤 me가 올 때까지 사이드바·헤더까지 사라져 독만 남은 빈 화면이 된다. 그룹은 서로 배타라 범위는 늘 하나다.
export default function DockLayout({ children }: { children: ReactNode }) {
  return (
    <AuthGuard>
      {children}
      <Dock />
      {/* 채널을 바꿀 때 화면 전체를 잠깐 덮는다(사이드바·헤더·독까지): 채널 범위 밖에 둔다 */}
      <ChannelSwitchCurtain />
    </AuthGuard>
  );
}
