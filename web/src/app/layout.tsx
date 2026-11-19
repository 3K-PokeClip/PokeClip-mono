import '@/ui/styles/global.css';
import './shell.css';
import './small-screen.css';

import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { getThemeInitScript } from '@/ui/theme/getThemeInitScript';
import { SmallScreenNotice } from '@/components/app-shell/SmallScreenNotice';
import { Providers } from './providers';

export const metadata: Metadata = {
  title: 'PokeClip',
  description: 'PokeClip — 다크 퍼스트 디자인 시스템 기반 앱',
  // 대시보드(app.·dev.)는 검색에 나올 자리가 아니다 — 공개 소개는 랜딩(pokeclip.com)이 맡는다(POK-349).
  // 로그인 화면·개발 데모까지 모든 페이지에 걸리도록 루트에 둔다. robots.txt Disallow로 막지 않는다 —
  // 막으면 크롤러가 이 noindex를 읽지 못해 주소만 색인될 수 있다
  robots: { index: false, follow: false },
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    // data-theme 기본 dark — init 스크립트가 저장된 선택으로 덮는다. 스크립트가 못 돌아도 다크로 그려진다
    <html lang="ko" data-theme="dark" suppressHydrationWarning>
      <head>
        {/* 첫 페인트 전에 data-theme을 적용해 테마 플래시(FOUC)를 막는다. */}
        <script dangerouslySetInnerHTML={{ __html: getThemeInitScript() }} />
      </head>
      {/* 브라우저 확장이 hydration 전에 body 속성을 주입하는 경우가 있어 속성 불일치만 억제 */}
      <body suppressHydrationWarning>
        <Providers>{children}</Providers>
        {/* 휴대폰 크기 화면에서는 앱을 가리고 이 안내만 보인다(small-screen.css, POK-610). <body> 바로 아래여야 한다 —
            CSS가 `body > :not(.pc-small-screen)`으로 나머지를 가리므로 감싸면 안내까지 가려진다 */}
        <SmallScreenNotice />
      </body>
    </html>
  );
}
