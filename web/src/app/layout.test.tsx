import { Children, isValidElement, type ReactElement, type ReactNode } from 'react';
import { describe, expect, it } from 'vitest';
import { SmallScreenNotice } from '@/components/app-shell/SmallScreenNotice';
import RootLayout, { metadata } from './layout';

describe('루트 레이아웃 metadata', () => {
  it('대시보드 전체를 색인에서 뺀다 — 지우면 로그인 화면·개발 데모가 검색에 나온다(POK-349)', () => {
    // Next는 하위 page의 metadata.robots가 없으면 이 값을 물려준다(oauth 콜백도 같은 값을 따로 둔다)
    expect(metadata.robots).toEqual({ index: false, follow: false });
  });
});

describe('루트 레이아웃: 휴대폰 크기 화면 안내 (POK-610)', () => {
  type WithChildren = ReactElement<{ children?: ReactNode }>;
  const childrenOf = (el: WithChildren) =>
    Children.toArray(el.props.children).filter((c): c is WithChildren => isValidElement(c));

  it('안내를 <body> 바로 아래에 둔다 — 감싸면 CSS가 감싼 요소째 가려 휴대폰에서 빈 화면이 된다', () => {
    // small-screen.css는 `body > :not(.pc-small-screen)`으로 나머지를 가린다: 안내가 <body>의 자식이어야 살아남는다
    const html = RootLayout({ children: null }) as WithChildren;
    const body = childrenOf(html).find((el) => el.type === 'body');

    expect(body).toBeDefined();
    expect(childrenOf(body!).filter((el) => el.type === SmallScreenNotice)).toHaveLength(1);
  });
});
