import type { ReactNode } from 'react';
import { AuthGuard } from '@/components/app-shell/AuthGuard';

// 독을 걷어낸 전체 화면 그룹 — 클립 편집기처럼 화면을 끝까지 쓰는 작업 화면이 여기 온다.
// (dock) 그룹과 다른 점은 하단 Dock이 없다는 것뿐이라 로그인 가드는 그대로 덮는다.
// 나가는 길은 화면이 직접 낸다 (편집기 헤더의 「보관함으로」).
export default function FullscreenLayout({ children }: { children: ReactNode }) {
  return <AuthGuard>{children}</AuthGuard>;
}
