import { act } from 'react';
import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/testProviders';
import { LibraryScreen } from './LibraryScreen';

function cards() {
  return within(screen.getByRole('list', { name: '편집본 목록' })).getAllByRole('button');
}

function panel() {
  // 닫힌 패널은 inert라 접근 트리에서 빠질 수 있다 — hidden까지 찾는다
  return screen.getByRole('complementary', { name: '편집본 상세', hidden: true });
}

function chipGroup() {
  return within(screen.getByRole('group', { name: '상태 필터' }));
}

describe('LibraryScreen — 헤더·칩', () => {
  it('제목·검색·정렬(최근 편집순 기본)을 보여준다', () => {
    renderWithProviders(<LibraryScreen />);

    expect(screen.getByRole('heading', { name: '보관함' })).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: '편집본 검색' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: '정렬' })).toHaveTextContent('최근 편집순');
  });

  it('스트리머 칩 4개가 수를 달고 있다 — 전체 8 · 작업 중 4 · 업로드 대기 2 · 발행됨 2', () => {
    renderWithProviders(<LibraryScreen />);

    const chips = chipGroup().getAllByRole('button');
    expect(chips.map((chip) => chip.textContent)).toEqual([
      '전체 8',
      '작업 중 4',
      '업로드 대기 2',
      '발행됨 2',
    ]);
    expect(chips[0]).toHaveAttribute('aria-pressed', 'true');
    expect(chipGroup().queryByRole('button', { name: /반려됨/ })).toBeNull();
  });

  it('스트리머에겐 승인 대기 배너가 승인 대기함으로 간다', () => {
    renderWithProviders(<LibraryScreen />);

    const banner = screen.getByRole('link', { name: /승인 대기 1건/ });
    expect(banner).toHaveAttribute('href', '/clips/approvals');
    expect(banner).toHaveTextContent('편집자 요청은 승인 대기함에서 검토해요');
  });

  it('칩을 누르면 목록이 좁혀지고 눌림이 옮겨 간다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen />);

    await user.click(chipGroup().getByRole('button', { name: '업로드 대기 2' }));
    expect(cards()).toHaveLength(2);
    expect(chipGroup().getByRole('button', { name: '업로드 대기 2' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(chipGroup().getByRole('button', { name: '전체 8' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
  });
});

describe('LibraryScreen — 검색', () => {
  it('검색어가 목록을 좁힌다 — 「랭크」는 1장', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen />);

    await user.type(screen.getByRole('searchbox', { name: '편집본 검색' }), '랭크');
    expect(cards()).toHaveLength(1);
    expect(cards()[0]).toHaveAccessibleName(/새벽 랭크 · 승급 확정/);
  });

  it('조건에 없으면 문장으로 말한다 — 빈 상태 카드가 아니다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen />);

    await user.type(screen.getByRole('searchbox', { name: '편집본 검색' }), '없는 제목');
    expect(screen.getByRole('status')).toHaveTextContent('조건에 맞는 편집본이 없어요.');
    expect(screen.queryByRole('list', { name: '편집본 목록' })).toBeNull();
    expect(screen.queryByText('아직 보관한 편집본이 없어요')).toBeNull();
  });
});

describe('LibraryScreen — 카드', () => {
  it('카드 이름에 제목·상태·길이가 있다', () => {
    renderWithProviders(<LibraryScreen />);

    expect(cards()).toHaveLength(8);
    expect(
      screen.getByRole('button', { name: '보스 막타 · 역전 순간 · 편집 중 · 길이 1:22' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', {
        name: '시청자 도네 반응 모음 · 승인 대기 · 길이 0:44 · 편집자 감자대장',
      }),
    ).toBeInTheDocument();
  });

  it('렌더 실패 카드는 길이를 말하지 않는다', () => {
    renderWithProviders(<LibraryScreen />);

    expect(
      screen.getByRole('button', { name: '팀원 미스 · 웃참 실패 · 렌더 실패' }),
    ).toBeInTheDocument();
  });
});

describe('LibraryScreen — 선택·상세 패널', () => {
  it('카드를 누르면 패널이 열리고 그리드가 촘촘해진다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen />);

    expect(panel()).toHaveAttribute('inert');
    expect(panel()).toHaveAttribute('data-open', 'false');

    const card = screen.getByRole('button', { name: /^보스 막타 · 역전 순간/ });
    await user.click(card);

    expect(card).toHaveAttribute('aria-pressed', 'true');
    expect(panel()).not.toHaveAttribute('inert');
    expect(panel()).toHaveAttribute('data-open', 'true');
    expect(screen.getByRole('list', { name: '편집본 목록' })).toHaveAttribute(
      'data-panel-open',
      'true',
    );
    expect(within(panel()).getByRole('textbox', { name: '클립 제목' })).toHaveValue(
      '보스 막타 · 역전 순간',
    );
  });

  it('같은 카드를 다시 누르면 닫히고 마지막 편집본은 남는다 — 빈 패널이 미끄러지지 않게', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen />);

    const card = screen.getByRole('button', { name: /^보스 막타 · 역전 순간/ });
    await user.click(card);
    await user.click(card);

    expect(card).toHaveAttribute('aria-pressed', 'false');
    expect(panel()).toHaveAttribute('inert');
    expect(screen.getByRole('list', { name: '편집본 목록' })).toHaveAttribute(
      'data-panel-open',
      'false',
    );
    expect(within(panel()).getByDisplayValue('보스 막타 · 역전 순간')).toBeInTheDocument();
  });

  it('카드가 검색으로 빠진 뒤 닫으면 포커스가 목록 첫 카드로 물러선다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen selectedId="lib2-1" />);

    // 선택은 남고(의도된 동작) 그 카드만 목록에서 빠진다 — 돌려줄 카드가 사라진 자리다
    await user.type(screen.getByRole('searchbox', { name: '편집본 검색' }), '랭크');
    expect(panel()).toHaveAttribute('data-open', 'true');
    expect(screen.queryByRole('button', { name: /^보스 막타/ })).toBeNull();

    await user.click(within(panel()).getByRole('button', { name: '선택 해제' }));

    expect(panel()).toHaveAttribute('inert');
    expect(cards()[0]).toHaveFocus();
  });

  it('선택 해제 버튼이 패널을 닫고 카드로 포커스를 돌려준다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen />);

    const card = screen.getByRole('button', { name: /^보스 막타 · 역전 순간/ });
    await user.click(card);
    await user.click(within(panel()).getByRole('button', { name: '선택 해제' }));

    expect(panel()).toHaveAttribute('inert');
    expect(card).toHaveFocus();
  });

  it('다른 카드를 누르면 패널 내용이 바뀐다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen selectedId="lib2-1" />);

    await user.click(screen.getByRole('button', { name: /^채팅 폭발/ }));
    expect(within(panel()).getByRole('textbox', { name: '클립 제목' })).toHaveValue(
      '채팅 폭발 · 3연속 클러치',
    );
    expect(screen.getByRole('button', { name: /^보스 막타/ })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
  });

  it('메타에 원본 방송·원본 보존·템플릿·자막·비율이 선다', () => {
    renderWithProviders(<LibraryScreen selectedId="lib2-1" />);
    const inside = within(panel());

    expect(inside.getByText('원본 방송').nextElementSibling).toHaveTextContent('8월 31일 라이브');
    expect(inside.getByText('원본 보존').nextElementSibling).toHaveTextContent('원본 만료 D-58');
    expect(inside.getByText('템플릿').nextElementSibling).toHaveTextContent('기본 쇼츠');
    expect(inside.getByText('자막').nextElementSibling).toHaveTextContent(
      '자동 자막 12줄 · 수정 중',
    );
    expect(inside.getByText('비율').nextElementSibling).toHaveTextContent('9:16 · 1080×1920');
  });
});

describe('LibraryScreen — 상태별 액션 7종', () => {
  it('편집 중 — 이어서 편집 링크 · 보조는 다운로드·삭제뿐', () => {
    renderWithProviders(<LibraryScreen selectedId="lib2-1" />);
    const inside = within(panel());

    expect(inside.getByText('편집 중')).toBeInTheDocument();
    expect(inside.getByRole('link', { name: '이어서 편집' })).toHaveAttribute(
      'href',
      '/clips/editor',
    );
    expect(inside.getAllByRole('link')).toHaveLength(1);
    expect(inside.getByRole('button', { name: '다운로드' })).toBeInTheDocument();
    expect(inside.getByRole('button', { name: '삭제' })).toBeInTheDocument();
  });

  it('업로드 대기 — 업로드 버튼 · 이어서 편집·다운로드·삭제', () => {
    renderWithProviders(<LibraryScreen selectedId="lib2-2" />);
    const inside = within(panel());

    expect(inside.getByRole('button', { name: '업로드' })).toBeInTheDocument();
    expect(inside.getByRole('link', { name: '이어서 편집' })).toHaveAttribute(
      'href',
      '/clips/editor',
    );
    expect(inside.getByRole('button', { name: '다운로드' })).toBeInTheDocument();
  });

  it('승인 대기 — 승인 대기함으로 가는 링크만 · 편집 잠금 · 안내문', () => {
    renderWithProviders(<LibraryScreen selectedId="lib2-3" />);
    const inside = within(panel());

    expect(inside.getByRole('link', { name: '승인 대기함에서 검토' })).toHaveAttribute(
      'href',
      '/clips/approvals',
    );
    expect(inside.queryByRole('link', { name: /편집/ })).toBeNull();
    expect(inside.getByText(/승인 · 반려는 승인 대기함에서 처리해요/)).toBeInTheDocument();
    expect(inside.getByRole('button', { name: '다운로드' })).toBeInTheDocument();
  });

  it('승인 대기는 제목도 잠근다 — 안내문이 편집이 잠겼다고 말한 것을 지킨다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen selectedId="lib2-3" />);
    const input = within(panel()).getByRole('textbox', { name: '클립 제목' });

    expect(input).toHaveAttribute('readonly');
    await user.type(input, '바꿔보기');
    expect(input).toHaveValue('시청자 도네 반응 모음');
  });

  it('승인 대기가 아니면 제목은 잠기지 않는다', () => {
    renderWithProviders(<LibraryScreen selectedId="lib2-1" />);

    expect(within(panel()).getByRole('textbox', { name: '클립 제목' })).not.toHaveAttribute(
      'readonly',
    );
  });

  it('반려됨 — 수정하기 링크 · 반려 사유 카드', () => {
    renderWithProviders(<LibraryScreen selectedId="lib2-5" />);
    const inside = within(panel());

    expect(inside.getByRole('link', { name: '수정하기' })).toHaveAttribute('href', '/clips/editor');
    expect(inside.getByText('반려 사유')).toBeInTheDocument();
    expect(inside.getByText('어제 21:32')).toBeInTheDocument();
    expect(inside.getByText('앞부분 20초 컷')).toBeInTheDocument();
    expect(inside.queryByRole('link', { name: /이어서 편집/ })).toBeNull();
  });

  it('발행됨 — 유튜브 보기(새 창) · 새 버전으로 편집', () => {
    renderWithProviders(<LibraryScreen selectedId="lib2-4" />);
    const inside = within(panel());

    const youtube = inside.getByRole('link', { name: /유튜브 보기/ });
    expect(youtube).toHaveAttribute('target', '_blank');
    expect(youtube).toHaveAttribute('rel', expect.stringContaining('noopener'));
    expect(inside.getByRole('link', { name: '새 버전으로 편집' })).toHaveAttribute(
      'href',
      '/clips/editor',
    );
  });

  it('발행됨 · 원본 만료 — 재편집이 숨고 만료 안내가 선다', () => {
    renderWithProviders(<LibraryScreen selectedId="lib2-6" />);
    const inside = within(panel());

    expect(inside.getByRole('link', { name: /유튜브 보기/ })).toBeInTheDocument();
    expect(inside.queryByRole('link', { name: /편집/ })).toBeNull();
    expect(inside.getByText(/원본 VOD가 만료되어 다시 편집할 수 없어요/)).toBeInTheDocument();
    expect(inside.getByText('원본 만료됨')).toBeInTheDocument();
    expect(inside.getByText('발행됨')).toBeInTheDocument();
  });

  it('렌더 실패 — 렌더 재시도 버튼 · 다운로드 없음 · 길이 없음', () => {
    renderWithProviders(<LibraryScreen selectedId="lib2-8" />);
    const inside = within(panel());

    expect(inside.getByRole('button', { name: '렌더 재시도' })).toBeInTheDocument();
    expect(inside.queryByRole('button', { name: '다운로드' })).toBeNull();
    expect(inside.getByRole('link', { name: '이어서 편집' })).toBeInTheDocument();
    expect(inside.getByText('자막').nextElementSibling).toHaveTextContent('—');
  });
});

describe('LibraryScreen — 제목 인라인 편집', () => {
  it('입력하면 즉시 카드 제목과 이름이 바뀐다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen selectedId="lib2-1" />);

    const input = within(panel()).getByRole('textbox', { name: '클립 제목' });
    await user.clear(input);
    await user.type(input, '보스 막타');

    expect(input).toHaveValue('보스 막타');
    expect(
      screen.getByRole('button', { name: '보스 막타 · 편집 중 · 길이 1:22' }),
    ).toBeInTheDocument();
  });

  // 한글 입력기는 조합 중에도 Enter를 보낸다. 그때 blur를 걸면 브라우저가 조합을 강제로
  // 끝내면서 마지막 글자가 한 번 더 들어간다 — jsdom에는 입력기가 없어 그 중복 자체는
  // 재현되지 않으므로, 원인이 되는 자리(조합 중에 편집을 끝내지 않는다)를 고정한다.
  it('조합 중 Enter는 편집을 끝내지 않는다 — 입력기가 글자를 확정하는 Enter다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen selectedId="lib2-1" />);

    const input = within(panel()).getByRole('textbox', { name: '클립 제목' });
    await user.click(input);
    expect(input).toHaveFocus();

    // 크롬·파이어폭스는 isComposing으로 조합 중임을 알린다
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true });
    expect(input).toHaveFocus();

    // isComposing이 비어 오는 브라우저는 keyCode 229로 같은 것을 알린다
    fireEvent.keyDown(input, { key: 'Enter', keyCode: 229 });
    expect(input).toHaveFocus();

    // 조합 중 Escape는 조합을 취소하라는 뜻이라 역시 편집을 끝내지 않는다
    fireEvent.keyDown(input, { key: 'Escape', isComposing: true });
    expect(input).toHaveFocus();
  });

  it('조합이 끝난 Enter·Escape는 편집을 끝낸다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen selectedId="lib2-1" />);

    const input = within(panel()).getByRole('textbox', { name: '클립 제목' });
    await user.click(input);
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(input).not.toHaveFocus();

    await user.click(input);
    fireEvent.keyDown(input, { key: 'Escape' });
    expect(input).not.toHaveFocus();
  });
});

describe('LibraryScreen — 목업 전이', () => {
  it('업로드를 누르면 발행됨이 되고, 유튜브 보기는 갈 곳이 없어 비활성이다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen selectedId="lib2-2" />);
    const inside = within(panel());

    await user.click(inside.getByRole('button', { name: '업로드' }));

    expect(inside.getByText('발행됨')).toBeInTheDocument();
    expect(inside.getByRole('button', { name: '유튜브 보기' })).toBeDisabled();
    expect(inside.queryByRole('link', { name: /유튜브 보기/ })).toBeNull();
    expect(inside.getByRole('link', { name: '새 버전으로 편집' })).toBeInTheDocument();
  });

  it('업로드로 주 동작이 갈려도 포커스가 조작부에 남는다 — 버튼이 링크로 바뀌는 자리다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen role="editor" selectedId="lib2-2" />);

    await user.click(within(panel()).getByRole('button', { name: '업로드 요청' }));

    // ready → pending이라 button이 anchor로 교체된다. 포커스가 body로 떨어지면 안 된다.
    const moved = within(panel()).getByRole('link', { name: '내 요청 보기' });
    expect(moved).toHaveFocus();
  });

  it('상태 배지는 낭독 영역이라 바뀐 상태가 알려진다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen selectedId="lib2-8" />);

    expect(within(panel()).getByRole('status')).toHaveTextContent('렌더 실패');
    await user.click(within(panel()).getByRole('button', { name: '렌더 재시도' }));
    expect(within(panel()).getByRole('status')).toHaveTextContent('업로드 대기');
  });

  it('편집자가 업로드 요청을 누르면 승인 대기가 된다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen role="editor" selectedId="lib2-2" />);
    const inside = within(panel());

    await user.click(inside.getByRole('button', { name: '업로드 요청' }));

    expect(inside.getByText('승인 대기')).toBeInTheDocument();
    expect(inside.getByRole('link', { name: '내 요청 보기' })).toHaveAttribute(
      'href',
      '/clips/approvals',
    );
    expect(inside.getByText(/승인 대기 중에는 편집이 잠겨요/)).toBeInTheDocument();
  });

  it('렌더 재시도는 업로드 대기로 돌린다 — 길이는 여전히 모른다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen selectedId="lib2-8" />);
    const inside = within(panel());

    await user.click(inside.getByRole('button', { name: '렌더 재시도' }));

    expect(inside.getByText('업로드 대기')).toBeInTheDocument();
    expect(inside.getByRole('button', { name: '업로드' })).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: '팀원 미스 · 웃참 실패 · 업로드 대기' }),
    ).toBeInTheDocument();
  });

  it('삭제는 확인을 거쳐 카드를 없애고 패널을 닫는다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen selectedId="lib2-2" />);

    await user.click(within(panel()).getByRole('button', { name: '삭제' }));
    const dialog = within(screen.getByRole('dialog', { name: '이 편집본을 삭제할까요?' }));
    expect(dialog.getByText('편집본과 렌더 결과가 보관함에서 사라져요.')).toBeInTheDocument();

    await user.click(dialog.getByRole('button', { name: '삭제' }));

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.queryByRole('button', { name: /^채팅 폭발/ })).toBeNull();
    expect(cards()).toHaveLength(7);
    expect(panel()).toHaveAttribute('inert');
  });

  it('삭제를 확정하면 포커스가 목록으로 돌아온다 — 사라진 삭제 버튼에 남지 않는다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen selectedId="lib2-2" />);

    await user.click(within(panel()).getByRole('button', { name: '삭제' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '삭제' }));

    expect(cards()[0]).toHaveFocus();
  });

  it('마지막으로 남은 편집본을 지우면 포커스가 검색창으로 물러선다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen />);
    const search = screen.getByRole('searchbox', { name: '편집본 검색' });

    await user.type(search, '랭크');
    await user.click(cards()[0]!);
    await user.click(within(panel()).getByRole('button', { name: '삭제' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '삭제' }));

    expect(screen.queryByRole('list', { name: '편집본 목록' })).toBeNull();
    expect(search).toHaveFocus();
  });

  it('삭제를 취소하면 그대로다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen selectedId="lib2-2" />);

    await user.click(within(panel()).getByRole('button', { name: '삭제' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '취소' }));

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(cards()).toHaveLength(8);
    expect(panel()).not.toHaveAttribute('inert');
  });

  it('발행된 편집본을 지울 때는 유튜브 영상이 남는다고 말한다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen selectedId="lib2-4" />);

    await user.click(within(panel()).getByRole('button', { name: '삭제' }));
    expect(
      within(screen.getByRole('dialog')).getByText('발행된 유튜브 영상은 그대로 남아요.'),
    ).toBeInTheDocument();
  });

  it('다운로드는 준비 중이라고만 알린다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen selectedId="lib2-1" />);

    await user.click(within(panel()).getByRole('button', { name: '다운로드' }));

    expect(await screen.findByText('준비 중인 기능이에요')).toBeInTheDocument();
    expect(within(panel()).getByText('편집 중')).toBeInTheDocument();
  });
});

describe('LibraryScreen — 편집자 시점', () => {
  it('칩이 5개다 — 작업 중 3 · 반려됨 1 — 그리고 배너가 없다', () => {
    renderWithProviders(<LibraryScreen role="editor" />);

    expect(
      chipGroup()
        .getAllByRole('button')
        .map((chip) => chip.textContent),
    ).toEqual(['전체 8', '작업 중 3', '업로드 대기 2', '반려됨 1', '발행됨 2']);
    expect(screen.queryByRole('link', { name: /승인 대기 1건/ })).toBeNull();
  });

  it('업로드 대기의 주 동작이 「업로드 요청」이고 승인 대기는 「내 요청 보기」다', () => {
    const ready = renderWithProviders(<LibraryScreen role="editor" selectedId="lib2-2" />);
    expect(within(panel()).getByRole('button', { name: '업로드 요청' })).toBeInTheDocument();
    ready.unmount();

    renderWithProviders(<LibraryScreen role="editor" selectedId="lib2-3" />);
    expect(within(panel()).getByRole('link', { name: '내 요청 보기' })).toHaveAttribute(
      'href',
      '/clips/approvals',
    );
    expect(within(panel()).getByText(/승인 대기 중에는 편집이 잠겨요/)).toBeInTheDocument();
  });
});

describe('LibraryScreen — 빈 상태', () => {
  it('편집본이 하나도 없으면 빈 상태 카드를 보이고 칩·검색 결과 문장은 없다', () => {
    renderWithProviders(<LibraryScreen clips={[]} />);

    expect(screen.getByText('아직 보관한 편집본이 없어요')).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: '상태 필터' })).toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.queryByRole('list', { name: '편집본 목록' })).toBeNull();
  });
});

describe('LibraryScreen — 접근성', () => {
  // axe 실행 중 Next Link의 비동기 상태 갱신이 발화한다 — act로 감싸 경고 없이 흡수
  it('기본(스트리머 · 미선택)에 위반이 없다', async () => {
    const { container } = renderWithProviders(<LibraryScreen />);
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });

  it('편집자 시점에도 위반이 없다', async () => {
    const { container } = renderWithProviders(<LibraryScreen role="editor" />);
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });

  it('빈 상태에도 위반이 없다', async () => {
    const { container } = renderWithProviders(<LibraryScreen clips={[]} />);
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });

  it('패널이 열린 상태(반려 카드)에도 위반이 없다', async () => {
    const { container } = renderWithProviders(<LibraryScreen selectedId="lib2-5" />);
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });

  it('삭제 확인이 열린 상태에도 위반이 없다', async () => {
    const user = userEvent.setup();
    const { baseElement } = renderWithProviders(<LibraryScreen selectedId="lib2-2" />);
    await user.click(within(panel()).getByRole('button', { name: '삭제' }));
    await act(async () => {
      expect(await axe(baseElement)).toHaveNoViolations();
    });
  });
});
