import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Dock } from '@/components/app-shell/Dock';

vi.mock('next/navigation', () => ({
  usePathname: () => '/live',
}));

describe('Dock', () => {
  it('독 4개 링크를 렌더하고 현재 경로를 활성으로 표시한다', () => {
    render(<Dock />);

    const links = screen.getAllByRole('link');
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      '/home',
      '/live',
      '/clips',
      '/settings',
    ]);

    expect(screen.getByRole('link', { name: /라이브/ })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: /홈/ })).not.toHaveAttribute('aria-current');
  });
});
