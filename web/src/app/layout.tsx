import '@/ui/styles/global.css';
import './shell.css';

import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { getThemeInitScript } from '@/ui/theme/getThemeInitScript';
import { Providers } from './providers';

export const metadata: Metadata = {
  title: 'PokeClip',
  description: 'PokeClip — 다크 퍼스트 디자인 시스템 기반 앱',
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="ko" suppressHydrationWarning>
      <head>
        {/* 첫 페인트 전에 data-theme을 적용해 테마 플래시(FOUC)를 막는다. */}
        <script dangerouslySetInnerHTML={{ __html: getThemeInitScript() }} />
      </head>
      {/* 브라우저 확장이 hydration 전에 body 속성을 주입하는 경우가 있어 속성 불일치만 억제 */}
      <body suppressHydrationWarning>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
