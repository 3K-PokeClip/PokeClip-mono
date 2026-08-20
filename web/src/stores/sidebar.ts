import { create } from 'zustand';

interface SidebarState {
  collapsed: boolean;
  toggle: () => void;
  setCollapsed: (collapsed: boolean) => void;
}

// 사이드바 접힘은 화면이 아니라 셸의 상태다 — 방송·설정처럼 사이드바를 가진 그룹은
// 각자 자기 레이아웃에서 Side를 마운트하므로, 컴포넌트 지역 상태로 두면 탭을 옮길
// 때마다 접어둔 것이 혼자 펼쳐진다. 두 그룹의 사이드바는 같은 자리에 같은 모양으로
// 보이므로 접힘도 하나여야 한다.
//
// 새로고침까지 살리지는 않는다 — localStorage로 옮기면 SSR 첫 페인트와 어긋나
// 사이드바가 펼쳐졌다 접히는 깜빡임이 생긴다. 필요해지면 AuthGuard의 hydration
// 패턴(stores/auth.ts)을 따라 별도로 붙인다.
export const useSidebarStore = create<SidebarState>()((set) => ({
  collapsed: false,
  toggle: () => set((s) => ({ collapsed: !s.collapsed })),
  setCollapsed: (collapsed) => set({ collapsed }),
}));
