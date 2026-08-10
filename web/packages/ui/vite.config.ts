import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import dts from 'vite-plugin-dts';

const root = import.meta.dirname;

export default defineConfig({
  plugins: [
    react(),
    dts({
      include: ['src'],
      exclude: ['**/*.stories.tsx', '**/*.test.{ts,tsx}', '**/test/**', '**/foundations/**'],
      insertTypesEntry: true,
    }),
    {
      // vite build --watch는 시작 시 dist를 비우는데, tokens/global/design-system.css는
      // build-css.mjs가 만드는 후처리 산출물이라 watch만으로는 재생성되지 않는다.
      // 매 번들 사이클마다 실행해 dist를 항상 완전한 상태로 유지한다 (앱이 dist를 소비).
      name: 'pokeclip:build-css',
      closeBundle() {
        const result = spawnSync('node', ['scripts/build-css.mjs'], {
          cwd: root,
          stdio: 'inherit',
        });
        if (result.status !== 0) this.warn('[build-css] post-build CSS generation failed');
      },
    },
  ],
  resolve: {
    alias: { '@': resolve(root, 'src') },
  },
  build: {
    // watch 모드에서 dist가 비워지는 순간 앱(dist 소비)이 module-not-found로 깨진다.
    // 클린 빌드가 필요한 build 스크립트만 --emptyOutDir 플래그로 강제한다.
    emptyOutDir: false,
    lib: {
      entry: {
        index: resolve(root, 'src/index.ts'),
        'tokens/index': resolve(root, 'src/tokens/index.ts'),
        // React 무의존 순수 엔트리 — RSC(서버 컴포넌트)에서 안전하게 import 가능.
        'theme-init': resolve(root, 'src/theme/getThemeInitScript.ts'),
      },
      formats: ['es'],
    },
    cssCodeSplit: false,
    sourcemap: true,
    rollupOptions: {
      external: ['react', 'react-dom', 'react/jsx-runtime'],
      output: {
        assetFileNames: (info) =>
          info.names?.some((n) => n.endsWith('.css')) ? 'styles.css' : 'assets/[name][extname]',
      },
    },
  },
});
