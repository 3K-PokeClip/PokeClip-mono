import { createRef } from 'react';
import { render, screen } from '@testing-library/react';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';
import { LoadingRegion } from './LoadingRegion';
import { Skeleton } from './Skeleton';

describe('LoadingRegion', () => {
  it('숨은 status 한 줄로 무엇을 기다리는지 말하고, 영역은 바쁘다고 알린다', () => {
    render(
      <LoadingRegion label="지난 방송을 불러오는 중" data-testid="region">
        <Skeleton height={14} />
        <Skeleton height={14} />
      </LoadingRegion>,
    );

    // status 역할은 이름을 내용에서 얻지 않는다(ARIA: name from author) — 읽히는 문구는 내용으로 본다
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getByRole('status')).toHaveTextContent('지난 방송을 불러오는 중');
    expect(screen.getByTestId('region')).toHaveAttribute('aria-busy', 'true');
    // status는 busy 영역 밖(형제)이다: busy 안이면 busy를 따르는 낭독기가 영역이 내려갈 때까지 읽기를 미룬다(PR #377 리뷰)
    expect(screen.getByRole('status').closest('[aria-busy="true"]')).toBeNull();
  });

  it('className·나머지 속성·ref가 루트 div로 간다', () => {
    const ref = createRef<HTMLDivElement>();
    render(<LoadingRegion label="불러오는 중" ref={ref} className="custom" data-testid="region" />);

    const root = screen.getByTestId('region');
    expect(root.tagName).toBe('DIV');
    expect(root).toHaveClass('custom');
    expect(ref.current).toBe(root);
  });

  it('목록 모양 스켈레톤을 품어도 접근성 위반이 없다', async () => {
    const { container } = render(
      <LoadingRegion label="지난 방송을 불러오는 중">
        <ul aria-hidden="true">
          <li>
            <Skeleton height={14} />
          </li>
        </ul>
      </LoadingRegion>,
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
