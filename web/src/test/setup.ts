import '@testing-library/jest-dom/vitest';
import { beforeEach, expect } from 'vitest';
import { toHaveNoViolations } from 'jest-axe';
import { installFakeBroadcastChannel, resetFakeBroadcastChannels } from './fakeBroadcastChannel';

expect.extend(toHaveNoViolations);

// 탭 간 세션 동기화(stores/auth)가 BroadcastChannel을 쓴다 — 모든 테스트에서 가짜로 통일한다.
installFakeBroadcastChannel();
beforeEach(() => {
  resetFakeBroadcastChannels();
  installFakeBroadcastChannel(); // stubGlobal로 바꿨다 unstub한 테스트 뒤에도 다음 테스트는 가짜로 시작
});
