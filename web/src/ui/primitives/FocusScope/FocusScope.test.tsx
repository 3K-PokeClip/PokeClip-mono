import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { FocusScope } from './FocusScope';

// jsdom은 배치를 하지 않아 offsetWidth가 늘 0이다 — 보이는 요소만 고르는 필터를 통과시키려고 크기를 준다
beforeEach(() => {
  vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(10);
});
afterEach(() => vi.restoreAllMocks());

describe('FocusScope: 처음 포커스', () => {
  it('tabindex=-1인 버튼은 건너뛴다 — 선택 안 된 roving 탭이 포커스로 선택되지 않게(POK-379)', () => {
    render(
      <FocusScope>
        <button type="button" tabIndex={-1}>
          Windows
        </button>
        <button type="button" tabIndex={0}>
          macOS
        </button>
      </FocusScope>,
    );
    expect(screen.getByRole('button', { name: 'macOS' })).toHaveFocus();
  });

  it('일반 버튼은 그대로 첫 포커스다', () => {
    render(
      <FocusScope>
        <button type="button">첫째</button>
        <button type="button">둘째</button>
      </FocusScope>,
    );
    expect(screen.getByRole('button', { name: '첫째' })).toHaveFocus();
  });
});
