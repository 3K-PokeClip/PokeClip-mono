import { fireEvent, render, screen } from '@testing-library/react';
import { axe } from 'jest-axe';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ThemeProvider } from '@/ui';
import { ThemeCard } from './ThemeCard';

const { track } = vi.hoisted(() => ({ track: vi.fn() }));
vi.mock('@/lib/analytics', () => ({ track }));

function renderCard() {
  return render(
    <ThemeProvider>
      <ThemeCard />
    </ThemeProvider>,
  );
}

beforeEach(() => {
  track.mockClear();
  localStorage.clear();
  delete document.documentElement.dataset.theme;
});

describe('ThemeCard — 설정 › 계정 「화면 테마」', () => {
  it('세 값을 라디오로 보이고, 저장값이 없으면 다크가 골라져 있다', () => {
    renderCard();
    const group = screen.getByRole('radiogroup', { name: '화면 테마' });
    expect(group).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '다크' })).toBeChecked();
    expect(screen.getByRole('radio', { name: '시스템' })).not.toBeChecked();
  });

  it('라이트를 고르면 바로 적용·저장되고 이벤트를 한 번 보낸다', () => {
    renderCard();
    fireEvent.click(screen.getByRole('radio', { name: '라이트' }));
    expect(document.documentElement.dataset.theme).toBe('light');
    expect(localStorage.getItem('pc-theme')).toBe('light');
    expect(track).toHaveBeenCalledTimes(1);
    expect(track).toHaveBeenCalledWith('theme_changed', { theme: 'light' });
  });

  it('저장된 선택을 보여 주고, 같은 값을 다시 고르면 이벤트를 보내지 않는다', () => {
    localStorage.setItem('pc-theme', 'system');
    renderCard();
    expect(screen.getByRole('radio', { name: '시스템' })).toBeChecked();
    fireEvent.click(screen.getByRole('radio', { name: '시스템' }));
    expect(track).not.toHaveBeenCalled();
  });

  it('접근성 위반이 없다', async () => {
    const { container } = renderCard();
    expect(await axe(container)).toHaveNoViolations();
  });
});
