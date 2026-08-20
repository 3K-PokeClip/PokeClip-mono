import { act } from 'react';
import { render, screen } from '@testing-library/react';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';
import { NotFoundScreen } from '@/features/errors/NotFoundScreen';

describe('NotFoundScreen', () => {
  it('시안 문구를 그대로 쓰고 사용자 잘못이라 말하지 않는다', () => {
    render(<NotFoundScreen />);

    expect(
      screen.getByRole('heading', { name: '원하시는 페이지를 찾을 수 없어요' }),
    ).toBeInTheDocument();
    expect(screen.getByText(/주소가 바뀌었거나 삭제된 페이지예요\./)).toBeInTheDocument();
    expect(
      screen.getByText(/홈으로 돌아가면 새 하이라이트가 기다리고 있어요\./),
    ).toBeInTheDocument();
  });

  it('복귀 동선이 「홈으로 가기」 하나뿐이다', () => {
    // 로그인 여부로 가르지 않는다 — 링크가 늘면 시안과 어긋난다 (POK-204)
    render(<NotFoundScreen />);

    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAccessibleName('홈으로 가기');
    expect(links[0]).toHaveAttribute('href', '/home');
    expect(screen.queryAllByRole('button')).toHaveLength(0);
  });

  it('포키 이미지의 대체 텍스트가 비어 있지 않다', () => {
    render(<NotFoundScreen />);

    expect(screen.getByRole('img', { name: '클립을 잃어버린 포키 캐릭터' })).toBeInTheDocument();
  });

  it('장식인 404 숫자는 스크린리더에 읽히지 않는다', () => {
    // 의미는 제목이 전달한다 — 숫자를 또 읽어 주면 소음이다
    render(<NotFoundScreen />);

    expect(screen.getByText('404')).toHaveAttribute('aria-hidden', 'true');
  });

  it('접근성 위반이 없다', async () => {
    // next/link가 마운트 뒤 비동기로 갱신한다 — act로 감싸야 경고가 안 샌다
    const { container } = render(<NotFoundScreen />);
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});
