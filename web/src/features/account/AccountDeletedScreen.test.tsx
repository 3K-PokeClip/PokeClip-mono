import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';
import { AccountDeletedScreen } from '@/features/account/AccountDeletedScreen';

const nav = vi.hoisted(() => ({ replace: vi.fn() }));

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: nav.replace }) }));

beforeEach(() => {
  nav.replace.mockReset();
});

describe('AccountDeletedScreen', () => {
  it('탈퇴 완료 안내와 다시 가입할 수 있다는 사실을 알린다', () => {
    render(<AccountDeletedScreen />);

    expect(screen.getByRole('heading', { name: '탈퇴가 완료되었어요' })).toBeInTheDocument();
    expect(
      screen.getByText(
        '계정과 보관함 데이터가 삭제되었습니다. 같은 Google 계정으로 다시 가입할 수 있어요.',
      ),
    ).toBeInTheDocument();
  });

  it('로그인 화면으로가 뒤로 돌아갈 수 없게 replace로 보낸다', async () => {
    const user = userEvent.setup();
    render(<AccountDeletedScreen />);

    await user.click(screen.getByRole('button', { name: '로그인 화면으로' }));

    // push면 뒤로 가기로 탈퇴 완료 화면에 되돌아온다
    expect(nav.replace).toHaveBeenCalledWith('/login');
  });

  it('접근성 위반이 없다', async () => {
    const { container } = render(<AccountDeletedScreen />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
