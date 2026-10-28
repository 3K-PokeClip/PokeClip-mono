import type { AnchorHTMLAttributes, ReactNode } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { recordPathname, resetInAppHistoryForTests } from '@/lib/inAppHistory';
import { EditorBackLink } from './EditorBackLink';

// 링크가 제 길(보관함)로 가려 했는지 잰다: 편집기 쪽 처리기가 기본 동작을 막았으면 뒤로 가기를 고른 것이다.
// jsdom은 링크 이동을 못 하므로 잰 뒤에는 늘 막는다
const link = vi.hoisted(() => ({ followed: [] as boolean[] }));
vi.mock('next/link', () => ({
  default: ({
    children,
    onClick,
    ...rest
  }: { children?: ReactNode } & AnchorHTMLAttributes<HTMLAnchorElement>) => (
    <a
      {...rest}
      onClick={(e) => {
        onClick?.(e);
        link.followed.push(!e.defaultPrevented);
        e.preventDefault();
      }}
    >
      {children}
    </a>
  ),
}));

describe('EditorBackLink', () => {
  let back: ReturnType<typeof vi.spyOn>;
  beforeEach(() => {
    resetInAppHistoryForTests();
    link.followed = [];
    back = vi.spyOn(window.history, 'back').mockImplementation(() => {});
  });
  afterEach(() => back.mockRestore());

  it('앞 화면이 없으면(새 탭·주소 직접) 링크 그대로 보관함으로 간다', () => {
    recordPathname('/clips/editor/studio');
    render(<EditorBackLink />);

    const anchor = screen.getByRole('link', { name: '이전 화면으로' });
    expect(anchor).toHaveAttribute('href', '/clips/library');
    fireEvent.click(anchor);

    expect(link.followed).toEqual([true]);
    expect(back).not.toHaveBeenCalled();
  });

  it('앱 안에서 들어왔으면 들어온 화면으로 뒤로 간다', () => {
    recordPathname('/broadcast/livenow');
    recordPathname('/clips/editor/studio');
    render(<EditorBackLink />);

    fireEvent.click(screen.getByRole('link', { name: '이전 화면으로' }));

    expect(link.followed).toEqual([false]);
    expect(back).toHaveBeenCalledTimes(1);
  });

  it('보조키·가운데 클릭은 새 탭으로 보관함을 여는 링크 그대로 둔다', () => {
    recordPathname('/broadcast/livenow');
    recordPathname('/clips/editor/studio');
    render(<EditorBackLink />);
    const anchor = screen.getByRole('link', { name: '이전 화면으로' });

    fireEvent.click(anchor, { metaKey: true });
    fireEvent.click(anchor, { button: 1 });

    expect(link.followed).toEqual([true, true]);
    expect(back).not.toHaveBeenCalled();
  });
});
