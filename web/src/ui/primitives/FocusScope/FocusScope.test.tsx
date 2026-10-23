import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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

describe('FocusScope: inert 안은 건너뛴다', () => {
  it('뒤를 inert로 막은 덮개에서 Shift+Tab이 덮개 안에서 돈다 — 밖으로 새지 않는다(채널 스위처 거절 확인)', async () => {
    const user = userEvent.setup();
    render(
      <>
        <button type="button">밖</button>
        <FocusScope>
          <div inert>
            <button type="button">뒤 목록</button>
          </div>
          <button type="button">취소</button>
          <button type="button">거절하기</button>
        </FocusScope>
      </>,
    );
    expect(screen.getByRole('button', { name: '취소' })).toHaveFocus();

    await user.tab({ shift: true });
    expect(screen.getByRole('button', { name: '거절하기' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: '취소' })).toHaveFocus();
  });
});
