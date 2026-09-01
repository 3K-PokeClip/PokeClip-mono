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

// jsdom에는 Pointer Capture가 없다(`setPointerCapture is not a function`). 드래그로 무언가를
// 옮기는 화면(프로필 사진 크롭 등)은 pointerdown에서 이것을 부르므로, 없으면 테스트가 아니라
// **이벤트 핸들러가 통째로 터진다** — vitest는 그것을 실패가 아니라 `Errors`로 따로 세어
// 케이스는 초록으로 남는데 종료 코드는 1이 된다. 케이스 줄만 보면 놓치고 CI에서 막힌다.
// 포인터를 잡는 것 자체는 검증 대상이 아니므로 빈 함수로 채운다.
for (const name of ['setPointerCapture', 'releasePointerCapture', 'hasPointerCapture'] as const) {
  if (typeof Element.prototype[name] !== 'function') {
    Object.defineProperty(Element.prototype, name, {
      configurable: true,
      writable: true,
      value: name === 'hasPointerCapture' ? () => false : () => undefined,
    });
  }
}
