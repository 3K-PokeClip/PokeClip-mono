import { act, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Tooltip } from './Tooltip';

afterEach(() => {
  vi.useRealTimers();
});

function openOn(trigger: HTMLElement) {
  act(() => {
    trigger.focus();
  });
  act(() => {
    vi.advanceTimersByTime(300);
  });
}

describe('Tooltip', () => {
  it('열리면 트리거를 툴팁 글로 설명한다(aria-describedby)', () => {
    vi.useFakeTimers();
    render(
      <Tooltip content="편집기로 열어요">
        <button type="button">편집</button>
      </Tooltip>,
    );
    const trigger = screen.getByRole('button', { name: '편집' });
    expect(trigger).not.toHaveAttribute('aria-describedby');

    openOn(trigger);
    expect(trigger).toHaveAttribute('aria-describedby', screen.getByRole('tooltip').id);
  });

  it('describe={false}면 이름과 같은 글을 설명으로 잇지 않고 툴팁도 낭독에서 뺀다', () => {
    // 아이콘 버튼의 이름(aria-label)을 눈에 보여 주기만 하는 툴팁 — 이으면 「채팅 숨기기, 버튼, 채팅 숨기기」로 두 번 읽혔다
    vi.useFakeTimers();
    render(
      <Tooltip content="채팅 숨기기" describe={false}>
        <button type="button" aria-label="채팅 숨기기">
          ×
        </button>
      </Tooltip>,
    );
    const trigger = screen.getByRole('button', { name: '채팅 숨기기' });

    openOn(trigger);
    expect(screen.getByRole('tooltip', { hidden: true })).toHaveTextContent('채팅 숨기기');
    expect(screen.queryByRole('tooltip')).toBeNull();
    expect(trigger).not.toHaveAttribute('aria-describedby');
  });
});
