import { render, screen } from '@testing-library/react';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';
import { GlobalHeader } from '@/components/app-shell/GlobalHeader';

describe('GlobalHeader', () => {
  it('워드마크·알림 버튼·아바타를 렌더한다', () => {
    render(<GlobalHeader />);

    expect(screen.getByText('PokeClip')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '알림' })).toBeInTheDocument();
    // Avatar는 name 이니셜 폴백으로 렌더된다 (목업 사용자, POK-101에서 교체)
    expect(screen.getByText('게임')).toBeInTheDocument();
  });

  it('접근성 위반이 없다', async () => {
    const { container } = render(<GlobalHeader />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
