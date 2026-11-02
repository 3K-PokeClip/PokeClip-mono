import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { ConfirmDialog } from './ConfirmDialog';

const BASE = {
  open: true,
  busy: false,
  title: '치지직 연동을 해제할까요?',
  consequences: ['감지가 멈춰요.', '보관함 클립은 남아요.'],
  footnote: '언제든 다시 연동할 수 있어요.',
  confirmLabel: '연동 해제',
} as const;

describe('ConfirmDialog', () => {
  it('renders the skeleton and wires consequences as the accessible description', () => {
    render(<ConfirmDialog {...BASE} onCancel={vi.fn()} onConfirm={vi.fn()} />);

    const dialog = screen.getByRole('dialog', { name: '치지직 연동을 해제할까요?' });
    expect(screen.getByText('언제든 다시 연동할 수 있어요.')).toBeInTheDocument();

    // 설명은 각주가 아니라 결과 목록이 맡는다 — aria-describedby가 ol을 가리킨다.
    const list = screen.getByRole('list');
    expect(dialog).toHaveAttribute('aria-describedby', list.id);
    expect(screen.getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      '감지가 멈춰요.',
      '보관함 클립은 남아요.',
    ]);
  });

  it('confirms with the danger button and cancels with the outline button', async () => {
    const user = userEvent.setup();
    const onCancel = vi.fn();
    const onConfirm = vi.fn();
    render(<ConfirmDialog {...BASE} onCancel={onCancel} onConfirm={onConfirm} />);

    await user.click(screen.getByRole('button', { name: '연동 해제' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: '취소' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('closes on Escape while idle', async () => {
    const user = userEvent.setup();
    const onCancel = vi.fn();
    render(<ConfirmDialog {...BASE} onCancel={onCancel} onConfirm={vi.fn()} />);

    await user.keyboard('{Escape}');
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('ignores every close path while busy and locks the buttons', async () => {
    const user = userEvent.setup();
    const onCancel = vi.fn();
    render(<ConfirmDialog {...BASE} busy onCancel={onCancel} onConfirm={vi.fn()} />);

    // 요청이 나간 뒤에는 결과가 돌아올 때까지 닫히지 않는다.
    await user.keyboard('{Escape}');
    expect(onCancel).not.toHaveBeenCalled();

    expect(screen.getByRole('button', { name: '취소' })).toBeDisabled();
    // loading 중엔 VisuallyHidden 「로딩 중」이 이름에 붙는다 — 부분 일치로 잡는다.
    const confirm = screen.getByRole('button', { name: /연동 해제/ });
    expect(confirm).toBeDisabled();
    expect(confirm).toHaveAttribute('aria-busy', 'true');
  });

  it('has no axe violations', async () => {
    render(<ConfirmDialog {...BASE} onCancel={vi.fn()} onConfirm={vi.fn()} />);
    expect(await axe(document.body)).toHaveNoViolations();
  });
});
