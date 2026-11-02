import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  resolve: {
    // tsconfig의 "@/*" → "src/*" 매핑을 vitest에도 맞춘다
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    css: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    // 날짜를 다루는 테스트는 지역 시간대로 계산한다(Intl·new Date(y,m,d)). 고정하지 않으면
    // 같은 코드가 CI(UTC)에서는 통과하고 UTC+10 이상·UTC-10 이하 머신에서는 깨진다 —
    // 코드 결함이 아닌 이유로 빨개지는 자리라 서비스 시간대에 못 박는다.
    env: { TZ: 'Asia/Seoul' },
  },
});
