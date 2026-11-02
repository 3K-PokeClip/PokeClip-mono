import { fireEvent, render, screen } from '@testing-library/react';
import { axe } from 'jest-axe';
import { describe, expect, it, vi } from 'vitest';
import { DownloadBanner } from './DownloadBanner';

describe('DownloadBanner', () => {
  it('「플러그인 다운로드」가 창을 연다', () => {
    const onOpen = vi.fn();
    render(<DownloadBanner onOpen={onOpen} />);
    const button = screen.getByRole('button', { name: '플러그인 다운로드' });
    expect(button).toHaveAttribute('aria-haspopup', 'dialog');
    fireEvent.click(button);
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('설치·연결 상태를 지어내지 않고, 접근성 위반이 없다', async () => {
    const { container } = render(<DownloadBanner onOpen={() => {}} />);
    expect(container).not.toHaveTextContent(/설치됨|연결됨/);
    expect(await axe(container)).toHaveNoViolations();
  });
});
