import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { Button } from './Button';

describe('Button', () => {
  it('renders its variants and handles clicks', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();

    render(
      <Button variant="outline" size="sm" onClick={onClick}>
        저장
      </Button>,
    );

    const button = screen.getByRole('button', { name: '저장' });
    expect(button).toHaveAttribute('type', 'button');
    expect(button).toHaveAttribute('data-variant', 'outline');
    expect(button).toHaveAttribute('data-size', 'sm');

    await user.click(button);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('blocks interaction and exposes busy state while loading', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();

    render(
      <Button loading onClick={onClick}>
        저장
      </Button>,
    );

    const button = screen.getByRole('button');
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');

    await user.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('has no detectable accessibility violations', async () => {
    const { container } = render(<Button>계속</Button>);

    expect(await axe(container)).toHaveNoViolations();
  });
});
