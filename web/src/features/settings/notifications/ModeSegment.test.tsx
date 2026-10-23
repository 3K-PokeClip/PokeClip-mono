import { useState } from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ModeSegment, type ModeOption } from '@/features/settings/notifications/ModeSegment';

type Mode = 'off' | 'on' | 'important';

const OPTIONS: ModeOption<Mode>[] = [
  { key: 'off', label: '끔' },
  { key: 'on', label: '받기' },
  { key: 'important', label: '중요', accent: true },
];

// 칸 셋이 100px씩 — 칸 0은 x 0~100, 칸 1은 100~200, 칸 2는 200~300
const RECT = { left: 0, top: 0, right: 300, bottom: 30, width: 300, height: 30, x: 0, y: 0 };
const X = [50, 150, 250] as const;
const Y = 15;

function setup(initial: Mode = 'important') {
  const onChange = vi.fn();
  function Harness() {
    const [value, setValue] = useState<Mode>(initial);
    return (
      <>
        <span id="t">알림</span>
        <ModeSegment
          options={OPTIONS}
          value={value}
          onChange={(next) => {
            onChange(next);
            setValue(next);
          }}
          labelledBy="t"
        />
      </>
    );
  }
  render(<Harness />);
  const group = screen.getByRole('radiogroup', { name: '알림' });
  vi.spyOn(group, 'getBoundingClientRect').mockReturnValue({
    ...RECT,
    toJSON: () => RECT,
  } as DOMRect);
  const checked = () =>
    within(group).getByRole('radio', { checked: true }).closest('label')!.textContent;
  const option = (label: string) => within(group).getByText(label).closest('label')!;
  const thumb = group.querySelector('[aria-hidden]')!;
  return { group, onChange, checked, option, thumb };
}

describe('ModeSegment', () => {
  it('다른 칸을 눌렀다 떼면 그 칸으로 바뀐다 — 누르는 동안엔 그 칸만 흐려진다', () => {
    const { group, onChange, checked, option } = setup('important');

    fireEvent.pointerDown(group, { pointerId: 1, clientX: X[0], clientY: Y });
    expect(option('끔')).toHaveAttribute('data-dimmed');
    expect(onChange).not.toHaveBeenCalled();

    fireEvent.pointerUp(group, { pointerId: 1, clientX: X[0], clientY: Y });
    expect(onChange).toHaveBeenCalledWith('off');
    expect(checked()).toBe('끔');
  });

  it('손잡이를 잡아 끌면 따라오고, 놓는 칸으로 정해진다', () => {
    const { group, onChange, checked, option, thumb } = setup('important');

    fireEvent.pointerDown(group, { pointerId: 1, clientX: X[2], clientY: Y });
    expect(thumb).toHaveAttribute('data-grabbed');

    fireEvent.pointerMove(group, { pointerId: 1, clientX: X[1], clientY: Y });
    // 끄는 중엔 손잡이 자리만 옮기고 값은 아직 그대로다
    expect(option('받기')).toHaveAttribute('data-active');
    expect(group.style.getPropertyValue('--seg-i')).toBe('1');
    expect(onChange).not.toHaveBeenCalled();

    fireEvent.pointerMove(group, { pointerId: 1, clientX: X[0], clientY: Y });
    fireEvent.pointerUp(group, { pointerId: 1, clientX: X[0], clientY: Y });
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith('off');
    expect(checked()).toBe('끔');
    expect(thumb).not.toHaveAttribute('data-grabbed');
  });

  it('위아래로 멀리 벗어나 놓으면 취소된다', () => {
    const { group, onChange, checked } = setup('important');

    fireEvent.pointerDown(group, { pointerId: 1, clientX: X[2], clientY: Y });
    fireEvent.pointerMove(group, { pointerId: 1, clientX: X[0], clientY: Y });
    fireEvent.pointerUp(group, { pointerId: 1, clientX: X[0], clientY: Y + 120 });

    expect(onChange).not.toHaveBeenCalled();
    expect(checked()).toBe('중요');
    expect(group.style.getPropertyValue('--seg-i')).toBe('2');
  });

  it('화면 스크롤 등으로 포인터가 끊기면 아무것도 바꾸지 않는다', () => {
    const { group, onChange, thumb } = setup('important');

    fireEvent.pointerDown(group, { pointerId: 1, clientX: X[2], clientY: Y });
    fireEvent.pointerCancel(group, { pointerId: 1 });

    expect(thumb).not.toHaveAttribute('data-grabbed');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('포인터로 고르면 그 칸의 라디오로 포커스가 옮겨 간다 — 키보드로 와 있던 다른 줄에 남지 않는다', () => {
    // 포커스가 앞 줄에 남으면 이어서 누른 방향키가 그 줄을 바꾼다(PR #317 리뷰)
    function Row({ id, title }: { id: string; title: string }) {
      const [value, setValue] = useState<Mode>('on');
      return (
        <>
          <span id={id}>{title}</span>
          <ModeSegment options={OPTIONS} value={value} onChange={setValue} labelledBy={id} />
        </>
      );
    }
    render(
      <>
        <Row id="top" title="위 줄" />
        <Row id="bottom" title="아래 줄" />
      </>,
    );
    const top = screen.getByRole('radiogroup', { name: '위 줄' });
    const bottom = screen.getByRole('radiogroup', { name: '아래 줄' });
    vi.spyOn(top, 'getBoundingClientRect').mockReturnValue({
      ...RECT,
      toJSON: () => RECT,
    } as DOMRect);

    // 키보드로 아래 줄에 와 있다
    within(bottom).getByRole('radio', { name: '받기' }).focus();

    fireEvent.pointerDown(top, { pointerId: 1, clientX: X[0], clientY: Y });
    fireEvent.pointerUp(top, { pointerId: 1, clientX: X[0], clientY: Y });

    const topOff = within(top).getByRole('radio', { name: '끔' });
    expect(topOff).toBeChecked();
    expect(topOff).toHaveFocus();
  });

  it('벗어나 놓아 취소하면 포커스도 옮기지 않는다', () => {
    const { group } = setup('important');
    const before = within(group).getByRole('radio', { name: '중요' });
    before.focus();

    fireEvent.pointerDown(group, { pointerId: 1, clientX: X[2], clientY: Y });
    fireEvent.pointerUp(group, { pointerId: 1, clientX: X[0], clientY: Y + 120 });

    expect(before).toHaveFocus();
  });

  it('키보드·낭독기는 숨긴 라디오로 고른다', () => {
    const { group, onChange, checked } = setup('on');

    fireEvent.click(within(group).getByRole('radio', { name: '중요' }));
    expect(onChange).toHaveBeenCalledWith('important');
    expect(checked()).toBe('중요');
  });

  it('강조 칸은 골라졌을 때만 강조 표시를 받는다', () => {
    const { option } = setup('important');

    expect(option('중요')).toHaveAttribute('data-accent');
    expect(option('중요')).toHaveAttribute('data-active');
    expect(option('끔')).not.toHaveAttribute('data-accent');
  });
});
