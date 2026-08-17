import { act } from 'react';
import { render, screen } from '@testing-library/react';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';
import { LoginScreen } from '@/features/auth/LoginScreen';

describe('LoginScreen', () => {
  it('헤드라인과 구글 로그인 링크를 렌더한다', () => {
    render(<LoginScreen />);

    expect(screen.getByRole('heading', { name: '클립 제작, 바로 시작하세요' })).toBeInTheDocument();
    // 목업 로그인 — POK-101에서 OAuth 진입으로 교체
    expect(screen.getByRole('link', { name: /Google로 시작하기/ })).toHaveAttribute(
      'href',
      '/home',
    );
  });

  it('접근성 위반이 없다', async () => {
    const { container } = render(<LoginScreen />);
    // axe 실행 중 Next Link의 비동기 상태 갱신이 발화한다 — act로 감싸 경고 없이 흡수
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});
