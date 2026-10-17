import { fileURLToPath, URL } from 'node:url';
import preact from '@preact/preset-vite';
import { defineConfig } from 'vitest/config';

// web 디자인 토큰을 그대로 쓴다 — 복사하지 않는다 (web/src/ui/styles가 정본).
const webStyles = fileURLToPath(new URL('../../web/src/ui/styles', import.meta.url));
const webRoot = fileURLToPath(new URL('../../web/src/ui', import.meta.url));
// 브랜드 심볼도 web 정본을 그대로 쓴다.
const webBrand = fileURLToPath(new URL('../../web/public/brand', import.meta.url));

export default defineConfig({
  plugins: [preact()],
  base: './',
  resolve: {
    alias: { '@pc-styles': webStyles, '@pc-brand': webBrand },
  },
  css: {
    modules: { localsConvention: 'camelCaseOnly' },
  },
  build: {
    outDir: '../data/ui',
    emptyOutDir: true,
    assetsInlineLimit: 0, // CSP font-src 'self' — 폰트를 data: 로 인라인하지 않는다
    sourcemap: false,
    target: 'es2022', // OBS 32의 CEF(Chromium 127+)
  },
  server: {
    port: 5178,
    fs: { allow: ['.', webRoot, webBrand] },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
