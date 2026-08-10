import '@pokeclip/ui/global.css';
import '@pokeclip/ui/styles.css';

import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { getThemeInitScript } from '@pokeclip/ui/theme-init';
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
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
