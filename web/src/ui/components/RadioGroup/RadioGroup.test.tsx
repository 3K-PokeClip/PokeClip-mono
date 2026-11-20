import { useState } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { RadioGroup } from './RadioGroup';

// 초점만으로는 고르지 않는다(PR #550 2차 리뷰) — 값이 밖에서 바뀌는 controlled 그룹에서 탭으로 돌아와 브라우저가 옛 라디오에
// 초점을 돌려주면 옛 값으로 덮어썼다. 고르는 길은 누르기(change)와 화살표 · Home · End(roving)뿐이다.

function Controlled({
  initial,
  onValueChange,
}: {
  initial?: string;
  onValueChange: (v: string) => void;
}) {
  const [value, setValue] = useState(initial);
  return (
    <RadioGroup
      aria-label="쪽"
      orientation="horizontal"
      value={value}
      onValueChange={(v) => {
        onValueChange(v);
        setValue(v);
      }}
    >
      <RadioGroup.Item value="left" label="왼쪽" />
      <RadioGroup.Item value="right" label="오른쪽" />
    </RadioGroup>
  );
}

const radio = (name: string) => screen.getByRole('radio', { name });

describe('RadioGroup', () => {
  it('초점만 받은 라디오는 고르지 않는다', () => {
    const onValueChange = vi.fn();
    render(<Controlled initial="left" onValueChange={onValueChange} />);

    fireEvent.focus(radio('오른쪽'));

    expect(onValueChange).not.toHaveBeenCalled();
    expect(radio('왼쪽')).toBeChecked();
    expect(radio('오른쪽')).not.toBeChecked();
  });

  it('고른 것이 없을 때 Tab으로 첫 라디오에 들어와도 고르지 않는다(APG)', () => {
    const onValueChange = vi.fn();
    render(<Controlled onValueChange={onValueChange} />);
    expect(radio('왼쪽')).toHaveAttribute('tabindex', '0');
    expect(radio('오른쪽')).toHaveAttribute('tabindex', '-1');

    act(() => radio('왼쪽').focus());

    expect(onValueChange).not.toHaveBeenCalled();
    expect(radio('왼쪽')).not.toBeChecked();
  });

  it('누르면 고른다', () => {
    const onValueChange = vi.fn();
    render(<Controlled initial="left" onValueChange={onValueChange} />);

    fireEvent.click(radio('오른쪽'));

    expect(onValueChange).toHaveBeenCalledWith('right');
    expect(radio('오른쪽')).toBeChecked();
  });

  it('화살표 · Home · End는 초점을 옮기며 고른다(끝에서는 돌아간다)', () => {
    const onValueChange = vi.fn();
    render(<Controlled initial="left" onValueChange={onValueChange} />);
    act(() => radio('왼쪽').focus());

    fireEvent.keyDown(radio('왼쪽'), { key: 'ArrowRight' });
    expect(onValueChange).toHaveBeenLastCalledWith('right');
    expect(radio('오른쪽')).toBeChecked();
    expect(radio('오른쪽')).toHaveFocus();
    expect(radio('오른쪽')).toHaveAttribute('tabindex', '0');

    fireEvent.keyDown(radio('오른쪽'), { key: 'ArrowRight' });
    expect(onValueChange).toHaveBeenLastCalledWith('left');
    expect(radio('왼쪽')).toHaveFocus();

    fireEvent.keyDown(radio('왼쪽'), { key: 'End' });
    expect(onValueChange).toHaveBeenLastCalledWith('right');
    fireEvent.keyDown(radio('오른쪽'), { key: 'Home' });
    expect(onValueChange).toHaveBeenLastCalledWith('left');
  });

  it('라디오에 건 onFocus는 그대로 불린다', () => {
    const onFocus = vi.fn();
    render(
      <RadioGroup aria-label="쪽" defaultValue="left">
        <RadioGroup.Item value="left" label="왼쪽" />
        <RadioGroup.Item value="right" label="오른쪽" onFocus={onFocus} />
      </RadioGroup>,
    );
    fireEvent.focus(radio('오른쪽'));
    expect(onFocus).toHaveBeenCalledTimes(1);
    expect(radio('왼쪽')).toBeChecked();
  });
});
