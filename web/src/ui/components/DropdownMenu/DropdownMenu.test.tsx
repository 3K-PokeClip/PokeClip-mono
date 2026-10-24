import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DropdownMenu } from './DropdownMenu';

function SpeedMenu({ onSelect }: { onSelect: (speed: string) => void }) {
  return (
    <DropdownMenu>
      <DropdownMenu.Trigger>
        <button type="button">속도</button>
      </DropdownMenu.Trigger>
      <DropdownMenu.Content>
        {['0.5×', '1×', '2×'].map((speed) => (
          <DropdownMenu.Item key={speed} onSelect={() => onSelect(speed)}>
            {speed}
          </DropdownMenu.Item>
        ))}
      </DropdownMenu.Content>
    </DropdownMenu>
  );
}

describe('DropdownMenu', () => {
  it('키보드로 열면 첫 항목에 초점이 가고, 화살표로 옮겨 Enter로 고른다(PR #341 2차)', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    render(<SpeedMenu onSelect={onSelect} />);
    const trigger = screen.getByRole('button', { name: '속도' });
    trigger.focus();

    // 내용은 Portal이 한 번 더 그린 뒤에 붙는다: 열림 이펙트 때 찾으면 아직 없어 초점이 트리거에 남았다
    await user.keyboard('{Enter}');
    const items = screen.getAllByRole('menuitem');
    expect(document.activeElement).toBe(items[0]);

    await user.keyboard('{ArrowDown}{ArrowDown}');
    expect(document.activeElement).toBe(items[2]);
    await user.keyboard('{Enter}');
    expect(onSelect).toHaveBeenCalledWith('2×');
    // 닫으면 초점이 트리거로 돌아간다
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(trigger);
  });

  it('다시 열어도 첫 항목부터다: 앞에서 연 기록이 남지 않는다', async () => {
    const user = userEvent.setup();
    render(<SpeedMenu onSelect={vi.fn()} />);
    screen.getByRole('button', { name: '속도' }).focus();
    await user.keyboard('{Enter}');
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();

    await user.keyboard('{Enter}');
    expect(document.activeElement).toBe(screen.getAllByRole('menuitem')[0]);
  });
});
