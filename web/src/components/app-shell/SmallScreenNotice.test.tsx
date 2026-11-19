import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SMALL_SCREEN_CLASS, SmallScreenNotice } from './SmallScreenNotice';

// 보이고 숨는 것은 CSS 미디어 쿼리가 정한다 — jsdom은 그것을 계산하지 않으므로 여기서는 「무엇을 말하나」와
// 「컴포넌트와 CSS가 같은 이름으로 묶여 있나」만 본다. 실제 폭별 동작은 브라우저로 확인한다(web/README 「작은 화면 계약」).
const css = readFileSync(resolve(process.cwd(), 'src/app/small-screen.css'), 'utf8');
/** 주석을 뺀 규칙만 — 설명 문장 속 선택자에 속지 않게 */
const rules = css.replace(/\/\*[\s\S]*?\*\//g, '');

describe('SmallScreenNotice: 휴대폰 크기 화면 안내 (POK-610)', () => {
  it('큰 화면에서 접속해 달라고 말한다 — 접속 주소는 적지 않는다', () => {
    render(<SmallScreenNotice />);

    const notice = screen.getByRole('main', { name: 'PC나 태블릿에서 접속해 주세요' });
    expect(notice).toHaveTextContent('휴대폰 화면에서는 쓸 수 없어요');
    // 지금 주소를 적어 주던 칸은 뺐다(2026-11-19 사용자 결정) — 로컬·dev에서는 낯선 주소가 그대로 보였다
    expect(notice).not.toHaveTextContent(window.location.host);
    expect(notice).not.toHaveTextContent('pokeclip.com');
    // PC에서 창을 좁혀 이 안내를 본 사람에게는 「PC로 접속하라」가 답이 아니다
    expect(notice).toHaveTextContent('브라우저 창을 넓히거나 화면 확대 배율을 줄여 주세요');
    // 그림은 장식이다
    expect(notice.querySelector('img')).toHaveAttribute('alt', '');
  });

  it('CSS가 찾는 이름을 뿌리에 단다 — 이름이 어긋나면 큰 화면에서도 안내가 보이거나, 휴대폰에서 안내까지 가려진다', () => {
    render(<SmallScreenNotice />);

    expect(screen.getByRole('main')).toHaveClass(SMALL_SCREEN_CLASS);
    // 기본은 숨김
    expect(rules).toMatch(new RegExp(`\\.${SMALL_SCREEN_CLASS}\\s*\\{\\s*display:\\s*none;`));
    // 휴대폰 크기에서는 이 안내를 뺀 <body>의 자식 전부를 가린다 — 창·토스트는 포털로 <body> 바로 아래에 붙는다
    expect(rules).toMatch(
      new RegExp(
        `body > :not\\(\\.${SMALL_SCREEN_CLASS}\\)\\s*\\{\\s*display:\\s*none !important;`,
      ),
    );
  });

  it('휴대폰 세로(폭 600px 미만)와 가로로 눕힌 휴대폰(터치 + 높이 500px 미만)에만 건다', () => {
    // 폭 기준을 DS 중단점 md(768)로 올리면 작은 태블릿 세로(아이패드 미니 744px)가 막힌다.
    // 높이 기준에서 pointer: coarse를 빼면 PC에서 창 높이만 줄여도 앱이 가려진다
    expect(rules).toContain(
      '@media (max-width: 599px), (max-height: 499px) and (pointer: coarse) {',
    );
  });
});
