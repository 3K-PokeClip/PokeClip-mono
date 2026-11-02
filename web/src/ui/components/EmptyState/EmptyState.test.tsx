import { createRef } from 'react';
import { render, screen } from '@testing-library/react';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';
import { EmptyState } from './EmptyState';

const BASE = {
  title: '아직 지난 방송이 없어요',
  description:
    '방송을 켜면 종료 후 VOD가 여기에 쌓여요. VOD는 60일 동안 보관되고, 만료 전에 풀 영상을 내려받을 수 있어요.',
} as const;

const icon = <svg data-testid="icon" />;

describe('EmptyState', () => {
  it('제목·설명을 문단으로 그리고 아이콘은 낭독에서 뺀다', () => {
    render(<EmptyState {...BASE} icon={icon} />);

    expect(screen.getByText('아직 지난 방송이 없어요').tagName).toBe('P');
    expect(screen.getByText(/VOD는 60일 동안 보관되고/).tagName).toBe('P');
    // 아이콘은 장식이다 — 래퍼 span이 aria-hidden으로 숨긴다
    expect(screen.getByTestId('icon').parentElement).toHaveAttribute('aria-hidden', 'true');
  });

  it('아이콘 → 제목 → 설명 순서로 선다', () => {
    const { container } = render(<EmptyState {...BASE} icon={icon} />);

    const root = container.firstElementChild!;
    expect(Array.from(root.children).map((el) => el.tagName)).toEqual(['SPAN', 'P', 'P']);
  });

  it('className·나머지 속성·ref가 루트 div로 가고, title은 HTML 속성으로 새지 않는다', () => {
    const ref = createRef<HTMLDivElement>();
    render(<EmptyState {...BASE} icon={icon} ref={ref} className="custom" data-testid="empty" />);

    const root = screen.getByTestId('empty');
    expect(root.tagName).toBe('DIV');
    expect(root).toHaveClass('custom');
    expect(root).not.toHaveAttribute('title');
    expect(ref.current).toBe(root);
  });

  it('접근성 위반이 없다', async () => {
    const { container } = render(<EmptyState {...BASE} icon={icon} />);

    expect(await axe(container)).toHaveNoViolations();
  });
});
