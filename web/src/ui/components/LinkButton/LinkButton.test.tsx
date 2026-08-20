import { forwardRef, type AnchorHTMLAttributes } from 'react';
import { render, screen } from '@testing-library/react';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';
import { LinkButton } from './LinkButton';
import { Button } from '../Button';

describe('LinkButton', () => {
  it('버튼 모양이지만 링크로 렌더된다', () => {
    render(<LinkButton href="/home">홈으로 가기</LinkButton>);

    const link = screen.getByRole('link', { name: '홈으로 가기' });
    expect(link).toHaveAttribute('href', '/home');
  });

  it('같은 variant·size의 Button과 클래스가 한 글자도 다르지 않다', () => {
    // 스타일시트를 공유하는 게 이 컴포넌트의 존재 이유다 — 갈리면 시안이 깨진다
    render(
      <>
        <Button variant="soft" size="lg">
          버튼
        </Button>
        <LinkButton href="/home" variant="soft" size="lg">
          링크
        </LinkButton>
      </>,
    );

    expect(screen.getByRole('link').className).toBe(screen.getByRole('button').className);
  });

  it('variant·size 기본값이 Button과 같다', () => {
    render(<LinkButton href="/home">홈으로 가기</LinkButton>);

    const link = screen.getByRole('link');
    expect(link).toHaveAttribute('data-variant', 'solid');
    expect(link).toHaveAttribute('data-size', 'md');
    expect(link).not.toHaveAttribute('data-full-width');
  });

  it('variant·size·fullWidth를 data 속성으로 넘긴다', () => {
    render(
      <LinkButton href="/home" variant="outline" size="lg" fullWidth>
        홈으로 가기
      </LinkButton>,
    );

    const link = screen.getByRole('link');
    expect(link).toHaveAttribute('data-variant', 'outline');
    expect(link).toHaveAttribute('data-size', 'lg');
    expect(link).toHaveAttribute('data-full-width', 'true');
  });

  it('as로 넘긴 앵커 컴포넌트로 렌더한다', () => {
    // 앱은 next/link를 넘긴다 — DS는 next를 모르므로 여기서도 대역을 쓴다
    const CustomLink = forwardRef<HTMLAnchorElement, AnchorHTMLAttributes<HTMLAnchorElement>>(
      function CustomLink(props, ref) {
        return <a ref={ref} data-custom="" {...props} />;
      },
    );

    render(
      <LinkButton as={CustomLink} href="/home">
        홈으로 가기
      </LinkButton>,
    );

    const link = screen.getByRole('link', { name: '홈으로 가기' });
    expect(link).toHaveAttribute('data-custom', '');
    // 대역이 받아도 스타일·href는 그대로 실려 간다
    expect(link).toHaveAttribute('href', '/home');
    expect(link).toHaveAttribute('data-variant', 'solid');
  });

  it('아이콘은 장식이라 접근성 이름에 섞이지 않는다', () => {
    render(
      <LinkButton href="/home" iconEnd={<svg data-testid="arrow" />}>
        홈으로 가기
      </LinkButton>,
    );

    expect(screen.getByRole('link')).toHaveAccessibleName('홈으로 가기');
    expect(screen.getByTestId('arrow').parentElement).toHaveAttribute('aria-hidden', 'true');
  });

  it('href가 항상 실려 링크 롤과 포커스를 잃지 않는다', () => {
    // href 없는 <a>는 link 롤도 포커스도 없으면서 버튼과 똑같이 보인다.
    // 타입에서 href를 required로 막았고(리뷰 #100), 런타임에서도 고정한다.
    render(<LinkButton href="/home">홈으로 가기</LinkButton>);

    const link = screen.getByRole('link', { name: '홈으로 가기' });
    link.focus();
    expect(link).toHaveFocus();
  });

  it('접근성 위반이 없다', async () => {
    const { container } = render(<LinkButton href="/home">홈으로 가기</LinkButton>);

    expect(await axe(container)).toHaveNoViolations();
  });
});
