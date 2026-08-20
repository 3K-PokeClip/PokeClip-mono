import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Dock } from '@/components/app-shell/Dock';
import styles from '@/components/app-shell/Dock.module.css';

vi.mock('next/navigation', () => ({
  usePathname: () => '/broadcast/livenow',
}));

describe('Dock', () => {
  it('독 4개 링크를 렌더하고 현재 경로를 활성으로 표시한다', () => {
    render(<Dock />);

    const links = screen.getAllByRole('link');
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      '/home',
      '/broadcast',
      '/clips',
      '/settings',
    ]);

    expect(screen.getByRole('link', { name: /방송/ })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: /홈/ })).not.toHaveAttribute('aria-current');
  });

  it('활성 항목에만 알약을 렌더한다', () => {
    // 알약이 둘이면 브라우저가 옛/새 한 쌍으로 보지 못해 이동 대신 각자 페이드된다
    const { container } = render(<Dock />);

    expect(container.querySelectorAll(`.${styles.pill}`)).toHaveLength(1);
    expect(
      screen.getByRole('link', { name: /방송/ }).querySelector(`.${styles.pill}`),
    ).not.toBeNull();
  });
});
