import type { ReactNode } from 'react';
import { ViewTransition } from './ViewTransition';

/** 독 탭 전환 시 화면을 좌우로 슬라이드시킨다. 애니메이션 본체는 app/shell.css의
 *  ::view-transition-old/new(.dock-forward|.dock-back)에 있다.
 *
 *  enter/exit는 마운트·언마운트에서만 발화하므로 (dock)/layout.tsx처럼 네비게이션 간
 *  유지되는 레이아웃이 아니라 화면 단위(page.tsx, 설정은 자기 layout.tsx)에 붙여야 한다.
 *
 *  default:'none'이 핵심 — 타입이 실리지 않은 이동(브라우저 뒤로가기, router.refresh(),
 *  설정 사이드바 내부 이동)에서는 슬라이드가 돌지 않는다. */
export function ScreenTransition({ children }: { children: ReactNode }) {
  return (
    <ViewTransition
      enter={{ 'dock-forward': 'dock-forward', 'dock-back': 'dock-back', default: 'none' }}
      exit={{ 'dock-forward': 'dock-forward', 'dock-back': 'dock-back', default: 'none' }}
      default="none"
    >
      {children}
    </ViewTransition>
  );
}
