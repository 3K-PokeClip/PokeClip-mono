import * as React from 'react';

/** React의 <ViewTransition> — 런타임에 없으면 자식을 그대로 통과시킨다.
 *
 *  Next은 App Router 번들에서 `react`를 자기가 번들한 canary로 alias하므로
 *  (next/dist/build/create-compiler-aliases.js) 앱 빌드에는 존재한다.
 *  하지만 vitest는 node_modules의 안정판 react(19.2.x)를 그대로 쓰고 거기엔 없다 —
 *  직접 import하면 렌더 시점에 터지므로 이 모듈을 거쳐서 쓴다.
 *  폴백은 애니메이션만 빠질 뿐 화면은 정상 동작한다. */
type ViewTransitionClass = string | Record<string, string>;

export type ViewTransitionProps = {
  children: React.ReactNode;
  name?: string;
  share?: ViewTransitionClass;
  enter?: ViewTransitionClass;
  exit?: ViewTransitionClass;
  default?: ViewTransitionClass;
};

const Native = (React as unknown as { ViewTransition?: React.ComponentType<ViewTransitionProps> })
  .ViewTransition;

export const ViewTransition: React.ComponentType<ViewTransitionProps> =
  Native ?? (({ children }: ViewTransitionProps) => <>{children}</>);
