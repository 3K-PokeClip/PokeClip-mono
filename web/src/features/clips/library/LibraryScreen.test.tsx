import { act, useState } from 'react';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';
import { ClipDetailPanel } from './ClipDetailPanel';
import { LibraryScreen } from './LibraryScreen';
import { LibrarySkeleton } from './LibrarySkeleton';
import { LIBRARY_FIXTURE } from './libraryFixture';
import type { ClipSnapshot, LibraryStatus } from '@/api/clipEditor';
import { toLibraryClip } from './useLibraryMockState';

function cards() {
  return within(screen.getByRole('list', { name: '편집본 목록' })).getAllByRole('button');
}

function panel() {
  // 닫힌 패널은 inert라 접근 트리에서 빠질 수 있다 — hidden까지 찾는다
  return screen.getByRole('complementary', { name: '편집본 상세', hidden: true });
}

/** 진행 카드(POK-510)의 지금 단계 줄 — 진행 · 실패 · 확인 필요 중 하나다 */
function currentStep() {
  const card = within(panel()).getByRole('region', { name: '진행 상황' });
  const now = card.querySelector('li[aria-current="step"]');
  if (!(now instanceof HTMLElement)) throw new Error('지금 단계가 없다');
  return now;
}

/**
 * 패널을 열면 저절로 나가는 읽기 — 비율을 읽는 상세(POK-254)와 원본 방송 카드의 방송 목록 · 시점별 기록(POK-511).
 * 시험이 세거나 붙잡는 요청(업로드 · 렌더 · 파일 주소)이 아니다
 */
function isPanelRead(url: string) {
  return (
    url.startsWith('/api/clip/library/') ||
    url.startsWith('/api/clip/broadcasts?') ||
    url.endsWith('/broadcast-info/timeline')
  );
}

function chipGroup() {
  return within(screen.getByRole('group', { name: '상태 필터' }));
}

describe('LibraryScreen — 헤더·칩', () => {
  it('제목·검색·정렬(최근 편집순 기본)을 보여준다', () => {
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} />);

    expect(screen.getByRole('heading', { name: '보관함' })).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: '편집본 검색' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: '정렬' })).toHaveTextContent('최근 편집순');
  });

  it('스트리머 칩 4개가 수를 달고 있다 — 전체 8 · 작업 중 3 · 업로드 대기 3 · 발행됨 2', () => {
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} />);

    const chips = chipGroup().getAllByRole('button');
    expect(chips.map((chip) => chip.textContent)).toEqual([
      '전체 8',
      '작업 중 3',
      '업로드 대기 3',
      '발행됨 2',
    ]);
    expect(chips[0]).toHaveAttribute('aria-pressed', 'true');
    expect(chipGroup().queryByRole('button', { name: /반려됨/ })).toBeNull();
  });

  // 업로드 승인 게이트가 없다(POK-236 폐기): 승인 대기·반려를 말하는 칩·배지·배너·링크가 없다
  it('승인 대기·반려 칩·배지·배너가 없다', () => {
    const { container } = renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} />);

    expect(chipGroup().queryByRole('button', { name: /반려|승인/ })).toBeNull();
    expect(screen.queryByText(/승인 대기|반려/)).toBeNull();
    expect(screen.queryByRole('link', { name: /승인 대기/ })).toBeNull();
    expect(container.querySelector('a[href="/clips/approvals"]')).toBeNull();
    for (const card of cards()) expect(card).not.toHaveAccessibleName(/승인 대기|반려/);
  });

  it('칩을 누르면 목록이 좁혀지고 눌림이 옮겨 간다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} />);

    await user.click(chipGroup().getByRole('button', { name: '업로드 대기 3' }));
    expect(cards()).toHaveLength(3);
    expect(chipGroup().getByRole('button', { name: '업로드 대기 3' })).toHaveAttribute(
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
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} />);

    await user.type(screen.getByRole('searchbox', { name: '편집본 검색' }), '랭크');
    expect(cards()).toHaveLength(1);
    expect(cards()[0]).toHaveAccessibleName(/새벽 랭크 · 승급 확정/);
  });

  it('조건에 없으면 문장으로 말한다 — 빈 상태 카드가 아니다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} />);

    await user.type(screen.getByRole('searchbox', { name: '편집본 검색' }), '없는 제목');
    expect(screen.getByRole('status')).toHaveTextContent('조건에 맞는 편집본이 없어요.');
    expect(screen.queryByRole('list', { name: '편집본 목록' })).toBeNull();
    expect(screen.queryByText('아직 보관한 편집본이 없어요')).toBeNull();
  });
});

describe('LibraryScreen — 카드', () => {
  it('카드 이름에 제목·상태·길이가 있다', () => {
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} />);

    expect(cards()).toHaveLength(8);
    expect(
      screen.getByRole('button', { name: '보스 막타 · 역전 순간 · 편집 중 · 길이 1:22' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', {
        name: '시청자 도네 반응 모음 · 업로드 대기 · 길이 0:44 · 편집자 감자대장',
      }),
    ).toBeInTheDocument();
  });

  it('렌더 실패 카드는 길이를 말하지 않는다', () => {
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} />);

    expect(
      screen.getByRole('button', { name: '팀원 미스 · 웃참 실패 · 렌더 실패' }),
    ).toBeInTheDocument();
  });
});

describe('LibraryScreen — 선택·상세 패널', () => {
  it('카드를 누르면 패널이 열리고 그리드가 촘촘해진다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} />);

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
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} />);

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
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-1" />);

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
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} />);

    const card = screen.getByRole('button', { name: /^보스 막타 · 역전 순간/ });
    await user.click(card);
    await user.click(within(panel()).getByRole('button', { name: '선택 해제' }));

    expect(panel()).toHaveAttribute('inert');
    expect(card).toHaveFocus();
  });

  it('다른 카드를 누르면 패널 내용이 바뀐다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-1" />);

    await user.click(screen.getByRole('button', { name: /^채팅 폭발/ }));
    expect(within(panel()).getByRole('textbox', { name: '클립 제목' })).toHaveValue(
      '채팅 폭발 · 3연속 클러치',
    );
    expect(screen.getByRole('button', { name: /^보스 막타/ })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
  });

  it('원본 방송 카드와 원본 보존·만든 사람·마지막 편집·비율이 서고, 늘 같은 값이던 템플릿·자막 칸은 없다(POK-511)', () => {
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-1" />);
    const inside = within(panel());

    // 목업 줄은 서버 방송이 없어 방송 제목 대신 원본 방송 표기가 선다
    expect(inside.getByRole('region', { name: '원본 방송' })).toHaveTextContent('8월 31일 라이브');
    expect(inside.getByText('원본 보존').nextElementSibling).toHaveTextContent('원본 만료 D-58');
    expect(inside.getByText('만든 사람').nextElementSibling).toHaveTextContent('게임하는너구리');
    // 목업 시계(9월 2일 20:00)와 같은 날이라 「오늘」이다
    expect(inside.getByText('마지막 편집').nextElementSibling).toHaveTextContent('오늘 14:20');
    // 목업 줄은 레시피가 없어 비율을 지어내지 않는다(POK-254)
    expect(inside.getByText('비율').nextElementSibling).toHaveTextContent('—');
    expect(inside.queryByText('템플릿')).toBeNull();
    expect(inside.queryByText('자막')).toBeNull();
  });
});

describe('LibraryScreen — 상태별 액션', () => {
  it('편집 중 — 이어서 편집 링크 · 보조는 다운로드·삭제뿐', () => {
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-1" />);
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
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-2" />);
    const inside = within(panel());

    expect(inside.getByRole('button', { name: '업로드' })).toBeInTheDocument();
    expect(inside.getByRole('link', { name: '이어서 편집' })).toHaveAttribute(
      'href',
      '/clips/editor',
    );
    expect(inside.getByRole('button', { name: '다운로드' })).toBeInTheDocument();
  });

  it('목업의 편집 중 편집본은 제목이 잠기지 않는다', () => {
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-1" />);

    expect(within(panel()).getByRole('textbox', { name: '클립 제목' })).not.toHaveAttribute(
      'readonly',
    );
  });

  it('발행됨 — 유튜브 보기(새 창) · 새 버전으로 편집', () => {
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-4" />);
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
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-6" />);
    const inside = within(panel());

    expect(inside.getByRole('link', { name: /유튜브 보기/ })).toBeInTheDocument();
    expect(inside.queryByRole('link', { name: /편집/ })).toBeNull();
    expect(inside.getByText(/원본 VOD가 만료되어 다시 편집할 수 없어요/)).toBeInTheDocument();
    expect(inside.getByText('원본 만료됨')).toBeInTheDocument();
    expect(inside.getByText('업로드됨')).toBeInTheDocument();
  });

  it('렌더 실패 — 렌더 재시도 버튼 · 다운로드 없음 · 길이 없음', () => {
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-8" />);
    const inside = within(panel());

    expect(inside.getByRole('button', { name: '렌더 재시도' })).toBeInTheDocument();
    expect(inside.queryByRole('button', { name: '다운로드' })).toBeNull();
    expect(inside.getByRole('link', { name: '이어서 편집' })).toBeInTheDocument();
  });
});

describe('LibraryScreen — 제목 인라인 편집', () => {
  it('입력하면 즉시 카드 제목과 이름이 바뀐다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-1" />);

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
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-1" />);

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
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-1" />);

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
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-2" />);
    const inside = within(panel());

    await user.click(inside.getByRole('button', { name: '업로드' }));

    expect(inside.getByText('업로드됨')).toBeInTheDocument();
    expect(inside.getByRole('button', { name: '유튜브 보기' })).toBeDisabled();
    expect(inside.queryByRole('link', { name: /유튜브 보기/ })).toBeNull();
    expect(inside.getByRole('link', { name: '새 버전으로 편집' })).toBeInTheDocument();
  });

  it('업로드로 주 동작이 비활성이 돼도 포커스가 조작부에 남는다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-2" />);

    await user.click(within(panel()).getByRole('button', { name: '업로드' }));

    // ready → published인데 목업 줄은 유튜브 주소가 없어 주 동작이 비활성이다. 포커스가 body로 떨어지지 않고
    // 다음 조작부(새 버전으로 편집)로 옮겨 간다.
    expect(within(panel()).getByRole('button', { name: '유튜브 보기' })).toBeDisabled();
    expect(within(panel()).getByRole('link', { name: '새 버전으로 편집' })).toHaveFocus();
  });

  it('상태 배지는 낭독 영역이라 바뀐 상태가 알려진다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-8" />);

    expect(within(panel()).getByRole('status')).toHaveTextContent('렌더 실패');
    await user.click(within(panel()).getByRole('button', { name: '렌더 재시도' }));
    expect(within(panel()).getByRole('status')).toHaveTextContent('업로드 대기');
  });

  it('렌더 재시도는 업로드 대기로 돌린다 — 길이는 여전히 모른다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-8" />);
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
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-2" />);

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
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-2" />);

    await user.click(within(panel()).getByRole('button', { name: '삭제' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '삭제' }));

    expect(cards()[0]).toHaveFocus();
  });

  it('마지막으로 남은 편집본을 지우면 포커스가 검색창으로 물러선다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} />);
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
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-2" />);

    await user.click(within(panel()).getByRole('button', { name: '삭제' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '취소' }));

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(cards()).toHaveLength(8);
    expect(panel()).not.toHaveAttribute('inert');
  });

  it('발행된 편집본을 지울 때는 유튜브 영상이 남는다고 말한다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-4" />);

    await user.click(within(panel()).getByRole('button', { name: '삭제' }));
    expect(
      within(screen.getByRole('dialog')).getByText('발행된 유튜브 영상은 그대로 남아요.'),
    ).toBeInTheDocument();
  });

  it('다운로드는 준비 중이라고만 알린다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-1" />);

    await user.click(within(panel()).getByRole('button', { name: '다운로드' }));

    expect(await screen.findByText('준비 중인 기능이에요')).toBeInTheDocument();
    expect(within(panel()).getByText('편집 중')).toBeInTheDocument();
  });
});

describe('LibraryScreen — 업로드 라벨', () => {
  it('업로드 대기의 주 동작이 「업로드」다 — 「업로드 요청」이 없다', () => {
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-2" />);
    expect(within(panel()).getByRole('button', { name: '업로드' })).toBeInTheDocument();
    expect(within(panel()).queryByRole('button', { name: '업로드 요청' })).toBeNull();
  });
});

describe('LibraryScreen — 빈 상태', () => {
  it('편집본이 하나도 없으면 빈 상태 카드를 보이고 칩·검색 결과 문장은 없다', () => {
    renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} clips={[]} />);

    expect(screen.getByText('아직 보관한 편집본이 없어요')).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: '상태 필터' })).toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.queryByRole('list', { name: '편집본 목록' })).toBeNull();
  });
});

describe('LibraryScreen — 접근성', () => {
  // axe 실행 중 Next Link의 비동기 상태 갱신이 발화한다 — act로 감싸 경고 없이 흡수
  it('기본(미선택)에 위반이 없다', async () => {
    const { container } = renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} />);
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });

  it('빈 상태에도 위반이 없다', async () => {
    const { container } = renderWithProviders(<LibraryScreen {...LIBRARY_FIXTURE} clips={[]} />);
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });

  it('패널이 열린 상태(남의 편집 중 카드)에도 위반이 없다', async () => {
    const { container } = renderWithProviders(
      <LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-5" />,
    );
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });

  it('삭제 확인이 열린 상태에도 위반이 없다', async () => {
    const user = userEvent.setup();
    const { baseElement } = renderWithProviders(
      <LibraryScreen {...LIBRARY_FIXTURE} selectedId="lib2-2" />,
    );
    await user.click(within(panel()).getByRole('button', { name: '삭제' }));
    await act(async () => {
      expect(await axe(baseElement)).toHaveNoViolations();
    });
  });
});

describe('LibraryScreen — 서버 편집본', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('서버 편집본은 지우는 창구가 없어 삭제를 잠근다 — 지운 척하지 않는다', async () => {
    // 화면에서만 빼면 다음 읽기에 되살아나 「지웠다」가 거짓이 된다(PR #200 codex)
    stubFetch((url) =>
      url.startsWith('/api/clip/library')
        ? jsonResponse(200, {
            items: [
              {
                recipeId: 12,
                streamId: 's1',
                creatorId: '9',
                recipeVersion: 1,
                cut: { inAtMs: 0, outAtMs: 30_000 },
                status: 'rendered',
                broadcast: { status: 'ended', startedAt: null, endedAt: null, vodExpiresAt: null },
                latestClip: null,
                createdAt: '2026-09-20T11:00:00Z',
                updatedAt: '2026-09-20T12:00:00Z',
              },
            ],
            nextCursor: null,
          })
        : jsonResponse(200, { id: 9, email: 'me@example.com' }),
    );
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen />);

    await user.click(await screen.findByRole('button', { name: /^편집본 #12/ }));
    expect(within(panel()).getByRole('button', { name: '삭제' })).toBeDisabled();
  });
});

describe('LibraryScreen — 서버 편집본의 비율 (POK-254)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('비율은 고정 글자가 아니라 레시피 출력의 비율이다: 패널을 열면 상세를 읽는다', async () => {
    const entry = {
      recipeId: 12,
      streamId: 's1',
      creatorId: '9',
      recipeVersion: 1,
      cut: { inAtMs: 0, outAtMs: 30_000 },
      status: 'rendered',
      broadcast: { status: 'ended', startedAt: null, endedAt: null, vodExpiresAt: null },
      latestClip: null,
      createdAt: '2026-09-20T11:00:00Z',
      updatedAt: '2026-09-20T12:00:00Z',
    };
    stubFetch((url) =>
      url === '/api/clip/library/12'
        ? jsonResponse(200, {
            ...entry,
            recipe: {
              outputs: [{ outputId: 'o1', aspect: 'SQUARE_1_1', crop: { x: 0, y: 0, w: 1, h: 1 } }],
            },
          })
        : url.startsWith('/api/clip/library')
          ? jsonResponse(200, { items: [entry], nextCursor: null })
          : jsonResponse(200, { id: 9, email: 'me@example.com' }),
    );
    const user = userEvent.setup();
    renderWithProviders(<LibraryScreen />);

    await user.click(await screen.findByRole('button', { name: /^편집본 #12/ }));
    const ratio = within(panel()).getByText('비율').nextElementSibling!;
    await waitFor(() => expect(ratio).toHaveTextContent('1:1'));
    expect(ratio).not.toHaveTextContent('1080×1920');
  });
});

describe('LibraryScreen — 서버 편집본의 비율 다시 읽기 (PR #256 codex)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('상세를 한 번 못 읽어도 「—」로 굳지 않고 다시 읽는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const entry = {
      recipeId: 12,
      streamId: 's1',
      creatorId: '9',
      recipeVersion: 1,
      cut: { inAtMs: 0, outAtMs: 30_000 },
      status: 'rendered',
      broadcast: { status: 'ended', startedAt: null, endedAt: null, vodExpiresAt: null },
      latestClip: null,
      createdAt: '2026-09-20T11:00:00Z',
      updatedAt: '2026-09-20T12:00:00Z',
    };
    let detailCalls = 0;
    stubFetch((url) => {
      if (url === '/api/clip/library/12') {
        detailCalls += 1;
        return detailCalls === 1
          ? jsonResponse(503, { error: 'x' })
          : jsonResponse(200, {
              ...entry,
              recipe: {
                outputs: [
                  { outputId: 'o1', aspect: 'SQUARE_1_1', crop: { x: 0, y: 0, w: 1, h: 1 } },
                ],
              },
            });
      }
      return url.startsWith('/api/clip/library')
        ? jsonResponse(200, { items: [entry], nextCursor: null })
        : jsonResponse(200, { id: 9, email: 'me@example.com' });
    });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithProviders(<LibraryScreen />);
    await user.click(await screen.findByRole('button', { name: /^편집본 #12/ }));
    const ratio = within(panel()).getByText('비율').nextElementSibling!;
    await waitFor(() => expect(detailCalls).toBe(1));
    expect(ratio).toHaveTextContent('—');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    await waitFor(() => expect(ratio).toHaveTextContent('1:1'));
  });
});

describe('LibraryScreen — 서버 편집본의 유튜브 업로드 (POK-111)', () => {
  const NOW = new Date('2026-09-20T12:00:00Z');
  const rendered: ClipSnapshot = {
    id: 5,
    streamId: 's1',
    recipeId: 12,
    recipeVersion: 2,
    requestedBy: '9',
    status: 'rendered',
    progress: null,
    outputs: [{ outputId: 'o1', kind: 'video', s3Key: 'k' }],
    error: null,
    createdAt: '2026-09-20T11:00:00Z',
    updatedAt: '2026-09-20T12:00:00Z',
    upload: null,
  };
  function serverClip(status: LibraryStatus, latestClip: ClipSnapshot | null = rendered) {
    return toLibraryClip(
      {
        recipeId: 12,
        streamId: 's1',
        creatorId: '9',
        recipeVersion: 2,
        cut: { inAtMs: 0, outAtMs: 30_000 },
        status,
        broadcast: { status: 'ended', startedAt: null, endedAt: null, vodExpiresAt: null },
        latestClip,
        createdAt: '2026-09-20T11:00:00Z',
        updatedAt: '2026-09-20T12:00:00Z',
      },
      '9',
    );
  }
  function show(clip: ReturnType<typeof serverClip>) {
    renderWithProviders(<LibraryScreen clips={[clip]} selectedId="12" now={NOW} />);
  }

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('완성본은 「업로드」다', () => {
    show(serverClip('rendered'));
    expect(within(panel()).getByRole('button', { name: '업로드' })).toBeEnabled();
  });

  it('완성본 제목은 올릴 때 쓰는 임시 제목이라고 알리고, 편집 중은 제목을 잠근다(POK-254)', () => {
    show(serverClip('rendered'));
    expect(within(panel()).getByRole('textbox', { name: '클립 제목' })).not.toHaveAttribute(
      'readonly',
    );
    expect(
      within(panel()).getByText('올릴 때 쓰는 제목이에요 · 새로 고침하면 처음 제목으로 돌아가요'),
    ).toBeInTheDocument();
    cleanup();
    show(serverClip('editing', null));
    expect(within(panel()).getByRole('textbox', { name: '클립 제목' })).toHaveAttribute('readonly');
    expect(within(panel()).queryByText(/올릴 때 쓰는 제목이에요/)).toBeNull();
  });

  it('올리는 중에는 진행 카드가 「유튜브 올리기」 단계를 말하고, 같은 말을 하던 꺼진 단추는 없다(POK-510)', () => {
    show(
      serverClip('uploading', {
        ...rendered,
        upload: { ...checkingUpload, status: 'uploading', error: null },
      }),
    );
    const inside = within(panel());
    expect(currentStep()).toHaveTextContent('유튜브 올리기');
    expect(currentStep()).toHaveTextContent(/스트리머 채널에 올리고 있어요/);
    // 배지 · 꺼진 단추 · 회색 상자가 같은 말을 세 번 하던 것을 걷었다
    expect(inside.queryByRole('button', { name: '유튜브에 올리는 중' })).toBeNull();
    expect(inside.queryByRole('button', { name: '업로드' })).toBeNull();
    expect(inside.getByRole('textbox')).toHaveAttribute('readonly');
  });

  it('확인 필요는 다시 올리기를 막고, 진행 카드가 채널을 보라고 하며 결과 단추는 그 카드 바로 아래에 선다(POK-509)', () => {
    show(serverClip('checking', { ...rendered, upload: checkingUpload }));
    const inside = within(panel());
    const card = inside.getByRole('region', { name: '진행 상황' });
    expect(currentStep()).toHaveTextContent('확인 필요');
    expect(
      within(card).getByText(/유튜브 스튜디오에서 영상이 올라갔는지 확인/),
    ).toBeInTheDocument();
    // 카드의 「아래 단추로 알려 주세요」가 가리키는 자리: 카드 밖, 카드 바로 뒤 · 원본 방송 앞(바닥 단추 줄이 아니다)
    const resolved = inside.getByRole('button', { name: '채널에 올라갔어요' });
    expect(resolved).toBeEnabled();
    expect(card.contains(resolved)).toBe(false);
    expect(card.compareDocumentPosition(resolved) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    const source = inside.getByRole('region', { name: '원본 방송' });
    expect(
      resolved.compareDocumentPosition(source) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    const edit = inside.getByRole('link', { name: '이어서 편집' });
    expect(edit.parentElement?.parentElement?.contains(resolved)).toBe(false);
    expect(inside.getByRole('button', { name: '안 올라갔어요' })).toBeEnabled();
    expect(inside.queryByRole('button', { name: '업로드 확인 필요' })).toBeNull();
    expect(inside.queryByRole('button', { name: /다시 시도/ })).toBeNull();
  });

  it('지금 누를 단추와 이어서 편집 · 다운로드 · 삭제는 진행 카드 · 원본 방송보다 뒤, 패널 맨 끝에 모여 있다(바닥 고정 줄, POK-509)', () => {
    show(serverClip('rendered', { ...rendered, upload: failedUpload }));
    const inside = within(panel());
    const retry = inside.getByRole('button', { name: '다시 시도' });
    const dock = retry.parentElement!;
    // 한 줄(같은 부모 아래)에 주 동작과 보조 단추 줄이 함께 있다
    expect(within(dock).getByRole('link', { name: '이어서 편집' })).toBeInTheDocument();
    expect(within(dock).getByRole('button', { name: '다운로드' })).toBeInTheDocument();
    expect(within(dock).getByRole('button', { name: '삭제' })).toBeInTheDocument();
    // 문서 순서상 원본 방송 카드 뒤 — 스크롤되는 내용 아래에 붙는다
    const source = inside.getByRole('region', { name: '원본 방송' });
    expect(source.compareDocumentPosition(dock) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('새 판 영상을 주문해도 이전 판으로 올린 영상이 패널에 남는다: 올림은 유튜브 링크, 확인 필요는 글자(POK-334)', async () => {
    const live = (
      id: number,
      recipeVersion: number,
      title: string,
      status: string,
      videoId: string | null,
    ) => ({
      id,
      clipId: id,
      recipeVersion,
      outputId: 'o1',
      title,
      status,
      videoId,
      createdAt: '2026-09-20T12:00:00Z',
      updatedAt: '2026-09-20T12:00:00Z',
    });
    const clip = toLibraryClip(
      {
        ...serverClip('rendering').entry!,
        latestClip: { ...rendered, status: 'rendering' },
        liveUploads: [
          live(9, 1, '두 번째 올림', 'checking', null),
          live(8, 1, '첫 판', 'uploaded', 'abc'),
        ],
      },
      '9',
    );
    show(clip);
    const inside = within(panel());

    const previous = within(inside.getByRole('region', { name: '이전 판으로 올린 영상' }));
    expect(previous.getAllByText('이전 판 v1')).toHaveLength(2);
    expect(inside.getByRole('link', { name: /첫 판 · 업로드됨/ })).toHaveAttribute(
      'href',
      'https://youtu.be/abc',
    );
    expect(inside.getByText('두 번째 올림 · 확인 필요')).toBeInTheDocument();
    expect(inside.queryByRole('link', { name: /두 번째 올림/ })).toBeNull();
    expect(await axe(panel())).toHaveNoViolations();
  });

  const checkingUpload = {
    id: 7,
    clipId: 5,
    outputId: 'o1',
    title: '보스 막타',
    status: 'checking',
    videoId: null,
    error: { code: 'SESSION_GONE', message: null },
    requestedBy: '9',
    createdAt: '2026-09-20T12:00:00Z',
    updatedAt: '2026-09-20T12:01:00Z',
  };

  /** 패널을 열면 비율을 읽으러 상세를 부른다(POK-254). 이 시험들이 세는 것은 「푸는 문」 호출이다 */
  const resolutionStub = (answer: () => Response) => {
    const spy = stubFetch((url) => (isPanelRead(url) ? new Promise<Response>(() => {}) : answer()));
    return () => spy.mock.calls.filter(([url]) => String(url).endsWith('/resolution'));
  };

  it('확인 필요는 「채널에 올라갔어요」로 영상 주소를 받아 푼다(POK-333)', async () => {
    const calls = resolutionStub(() =>
      jsonResponse(200, { ...checkingUpload, status: 'uploaded', videoId: 'abcDEF12345' }),
    );
    const user = userEvent.setup();
    show(serverClip('checking', { ...rendered, upload: checkingUpload }));

    await user.click(within(panel()).getByRole('button', { name: '채널에 올라갔어요' }));
    const dialog = await screen.findByRole('dialog', { name: '채널에 올라간 영상' });
    const submit = within(dialog).getByRole('button', { name: '발행됨으로 바꾸기' });
    expect(submit).toBeDisabled();
    await user.type(
      within(dialog).getByRole('textbox', { name: '영상 주소' }),
      ' https://youtu.be/abcDEF12345 ',
    );
    await user.click(submit);

    expect(calls()).toHaveLength(1);
    expect(calls()[0]?.[0]).toBe('/api/clip/broadcasts/s1/clips/5/uploads/7/resolution');
    expect(JSON.parse(String(calls()[0]?.[1]?.body))).toEqual({
      result: 'uploaded',
      videoId: 'https://youtu.be/abcDEF12345',
    });
    expect(await screen.findByText('발행됨으로 바꿨어요')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('「안 올라갔어요」는 두 번 올라갈 수 있다는 경고를 거치고, 30분 전이면 사유를 알린다(POK-333)', async () => {
    const calls = resolutionStub(() =>
      jsonResponse(409, { error: 'resolution_too_early', allowedAt: '2026-09-20T12:31:00Z' }),
    );
    const user = userEvent.setup();
    show(serverClip('checking', { ...rendered, upload: checkingUpload }));

    await user.click(within(panel()).getByRole('button', { name: '안 올라갔어요' }));
    const dialog = await screen.findByRole('dialog', { name: '유튜브에 안 올라간 것이 맞나요?' });
    expect(within(dialog).getByText(/같은 영상이 두 개 떠요/)).toBeInTheDocument();
    expect(calls()).toHaveLength(0);
    await user.click(within(dialog).getByRole('button', { name: '안 올라갔어요' }));

    expect(JSON.parse(String(calls()[0]?.[1]?.body))).toEqual({ result: 'not_uploaded' });
    expect(await screen.findByText(/30분이 지난 뒤 다시 눌러/)).toBeInTheDocument();
    expect(
      screen.getByRole('dialog', { name: '유튜브에 안 올라간 것이 맞나요?' }),
    ).toBeInTheDocument();
  });

  it('확인 필요가 아닌 업로드에는 푸는 단추가 없다(POK-333)', () => {
    show(
      serverClip('uploading', {
        ...rendered,
        upload: { ...checkingUpload, status: 'uploading', error: null },
      }),
    );
    expect(within(panel()).queryByRole('button', { name: '채널에 올라갔어요' })).toBeNull();
  });

  it('새 판을 주문해도 옛 판의 확인 필요 줄에 푸는 단추가 붙고 그 업로드 번호로 보낸다(POK-333)', async () => {
    const calls = resolutionStub(() =>
      jsonResponse(200, { ...checkingUpload, id: 3, clipId: 2, status: 'failed' }),
    );
    const user = userEvent.setup();
    const clip = toLibraryClip(
      {
        ...serverClip('rendering').entry!,
        latestClip: { ...rendered, status: 'rendering' },
        liveUploads: [
          {
            id: 3,
            clipId: 2,
            recipeVersion: 1,
            outputId: 'o1',
            title: '첫 판',
            status: 'checking',
            videoId: null,
            createdAt: '2026-09-20T12:00:00Z',
            updatedAt: '2026-09-20T12:00:00Z',
          },
        ],
      },
      '9',
    );
    show(clip);
    const previous = within(within(panel()).getByRole('region', { name: '이전 판으로 올린 영상' }));

    await user.click(previous.getByRole('button', { name: '안 올라갔어요 (이전 판 v1)' }));
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: '안 올라갔어요' }),
    );
    expect(calls()[0]?.[0]).toBe('/api/clip/broadcasts/s1/clips/2/uploads/3/resolution');
    // 다시 시도 단추는 지금 영상에만 있다: 옛 판을 닫았으면 다시 올릴 길을 약속하지 않는다(PR #261 codex)
    expect(
      await screen.findByText('이전 판 업로드라 이 화면에서 다시 올리지는 않아요.'),
    ).toBeInTheDocument();
  });

  it('창이 열린 사이 확인 필요 업로드가 바뀌면 적던 입력을 버린다(PR #261 codex)', async () => {
    resolutionStub(() => jsonResponse(200, {}));
    const user = userEvent.setup();
    const props = {
      status: 'checking' as const,
      now: NOW,
      onDeselect: () => {},
      onTitleChange: () => {},
      onUpload: () => {},
      onRetryRender: () => {},
      onRetryUpload: () => {},
      onDownload: () => {},
      onDelete: () => {},
      onResolveUpload: () => Promise.resolve(true),
    };
    const first = serverClip('checking', { ...rendered, upload: checkingUpload });
    const second = serverClip('checking', {
      ...rendered,
      id: 6,
      upload: { ...checkingUpload, id: 8, clipId: 6 },
    });
    // 목록 새로 읽기가 패널의 편집본을 다른 확인 필요 업로드로 바꾼 것을 흉내 낸다
    let swap: () => void = () => {};
    function Harness() {
      const [clip, setClip] = useState(first);
      swap = () => setClip(second);
      return <ClipDetailPanel clip={clip} {...props} />;
    }
    renderWithProviders(<Harness />);

    await user.click(screen.getByRole('button', { name: '채널에 올라갔어요' }));
    await user.type(
      within(await screen.findByRole('dialog')).getByRole('textbox', { name: '영상 주소' }),
      'https://youtu.be/abcDEF12345',
    );
    act(() => swap());

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  const failedUpload = {
    id: 7,
    clipId: 5,
    outputId: 'o1',
    title: '보스 막타',
    status: 'failed',
    videoId: null,
    error: { code: 'YOUTUBE_UNLINKED', message: null },
    requestedBy: '9',
    createdAt: '2026-09-20T12:00:00Z',
    updatedAt: '2026-09-20T12:01:00Z',
  };

  it('연동이 끊겨 실패한 업로드는 「업로드 실패」 배지와 채널 연동 안내, 「다시 시도」를 보인다', () => {
    show(serverClip('rendered', { ...rendered, upload: failedUpload }));
    expect(within(panel()).getByText('업로드 실패')).toBeInTheDocument();
    expect(within(panel()).getByText(/설정 › 채널 연동에서 다시 연결/)).toBeInTheDocument();
    expect(within(panel()).getByRole('button', { name: '다시 시도' })).toBeEnabled();
    expect(screen.getByRole('button', { name: /^보스 막타 · 업로드 실패/ })).toBeInTheDocument();
  });

  it('「다시 시도」는 저장된 정보로 다시 올리고, 보내는 동안 단추가 잠긴다(POK-291)', async () => {
    let answer: (r: Response) => void = () => {};
    const spy = stubFetch((url) =>
      // 패널을 열면 저절로 나가는 읽기(isPanelRead)는 붙잡지 않는다: 이 시험이 세는 것은 업로드 주문이다
      isPanelRead(url)
        ? new Promise<Response>(() => {})
        : new Promise<Response>((resolve) => (answer = resolve)),
    );
    // 패널을 열면 원본 방송 카드(POK-511)가 방송 목록 · 시점별 기록도 읽는다 — 세는 것은 보낸 요청(POST)뿐이다
    const orders = () => spy.mock.calls.filter(([, init]) => init?.method === 'POST');
    const user = userEvent.setup();
    show(serverClip('rendered', { ...rendered, upload: failedUpload }));

    await user.click(within(panel()).getByRole('button', { name: '다시 시도' }));
    // 누른 단추만 돈다(POK-443): 잠기고 aria-busy, 이름 끝에 「로딩 중」이 붙는다
    const busy = within(panel()).getByRole('button', { name: /^다시 시도/ });
    expect(busy).toBeDisabled();
    expect(busy).toHaveAttribute('aria-busy', 'true');
    await user.click(busy);
    expect(orders()).toHaveLength(1);
    expect(orders()[0]?.[0]).toBe('/api/clip/broadcasts/s1/clips/5/uploads/retry');

    await act(async () =>
      answer(jsonResponse(201, { ...failedUpload, id: 8, status: 'queued', error: null })),
    );
    expect(await within(panel()).findByText('올리는 중')).toBeInTheDocument();
    expect(currentStep()).toHaveTextContent('유튜브 올리기');
    expect(within(panel()).queryByRole('button', { name: /다시 시도/ })).toBeNull();
  });

  it('만드는 중은 진행 카드의 「영상 만들기」 단계에 퍼센트와 막대가 선다(POK-510)', () => {
    show(
      serverClip('rendering', {
        ...rendered,
        status: 'rendering',
        progress: { percent: 42, stage: null, attempt: 1, jobId: 'j' },
      }),
    );
    const inside = within(panel());
    expect(inside.getByText('만드는 중')).toBeInTheDocument();
    expect(currentStep()).toHaveTextContent('영상 만들기');
    expect(currentStep()).toHaveTextContent('42%');
    // 막대는 지금 단계 아래에 하나뿐이다(편집기 작은 창과 같은 이름)
    const bar = within(currentStep()).getByRole('progressbar', { name: '영상 진행' });
    expect(bar).toHaveAttribute('aria-valuenow', '42');
    expect(inside.getAllByRole('progressbar')).toHaveLength(1);
    // 지난 단계(대기)에는 요청한 시각이 붙는다 — 어디까지 왔는지 · 언제 시작했는지가 보인다
    expect(inside.getByRole('region', { name: '진행 상황' })).toHaveTextContent('편집본 v2');
    expect(inside.queryByRole('button', { name: '영상 만드는 중' })).toBeNull();
  });

  it('만드는 중인데 퍼센트가 아직 없으면 0%라고 하지 않고 불확정 막대를 그린다(POK-443)', async () => {
    show(serverClip('rendering', { ...rendered, status: 'rendering', progress: null }));
    const inside = within(panel());
    const bar = within(currentStep()).getByRole('progressbar', { name: '영상 진행' });
    expect(bar).not.toHaveAttribute('aria-valuenow');
    expect(currentStep()).toHaveTextContent('영상 만들기');
    expect(inside.queryByText(/0%/)).toBeNull();
    await act(async () => {
      expect(await axe(panel())).toHaveNoViolations();
    });
  });

  it('요청만 되고 차례를 기다리는 영상은 「대기」 단계가 지금이고 퍼센트를 지어내지 않는다(POK-510)', () => {
    show(serverClip('rendering', { ...rendered, status: 'queued', progress: null }));
    expect(currentStep()).toHaveTextContent('대기');
    expect(currentStep()).toHaveTextContent(/영상을 만들 차례를 기다리고 있어요/);
    // 편집기와 같은 규칙: 서버가 퍼센트를 안 주는 단계는 불확정 막대다(없는 값을 0%로 그리지 않는다)
    expect(within(currentStep()).getByRole('progressbar')).not.toHaveAttribute('aria-valuenow');
    expect(within(panel()).queryByText(/0%/)).toBeNull();
  });

  it('영상은 올라갔는데 썸네일만 실패하면 그렇게 말한다(POK-291)', () => {
    show(
      serverClip('uploaded', {
        ...rendered,
        upload: {
          ...failedUpload,
          status: 'uploaded',
          error: null,
          videoId: 'v1',
          thumbnail: { source: 'file', status: 'failed', errorCode: 'THUMBNAIL_FORBIDDEN' },
        },
      }),
    );
    expect(within(panel()).getByText('업로드됨')).toBeInTheDocument();
    expect(
      within(panel()).getByText(/영상은 올라갔고 썸네일만 못 붙였어요. 채널 전화 인증이 필요해요/),
    ).toBeInTheDocument();
  });

  it('공개로 올린 편집본은 패널에 누가 보는지와 바꾸는 곳을 말한다(POK-291)', () => {
    show(
      serverClip('uploaded', {
        ...rendered,
        upload: {
          ...failedUpload,
          status: 'uploaded',
          error: null,
          videoId: 'v1',
          privacyStatus: 'public',
        },
      }),
    );
    expect(
      within(panel()).getByText(
        '「공개」(누구나 볼 수 있는 범위)로 올렸어요. 올린 뒤에는 스트리머 채널의 유튜브 스튜디오에서만 바꿀 수 있어요.',
      ),
    ).toBeInTheDocument();
  });

  it('업로드 실패 뒤 고쳐 저장한 편집 중 줄은 옛 판의 공개 범위를 말하지 않는다(POK-291)', () => {
    show(
      serverClip('editing', { ...rendered, upload: { ...failedUpload, privacyStatus: 'public' } }),
    );
    expect(within(panel()).getByText('편집 중')).toBeInTheDocument();
    expect(within(panel()).queryByText(/「공개」/)).not.toBeInTheDocument();
  });

  it('미리보기를 누르면 완성 영상을 그 자리에서 튼다', async () => {
    const user = userEvent.setup();
    stubFetch(() =>
      jsonResponse(200, {
        clipId: 5,
        expiresAt: '2026-09-20T13:00:00Z',
        files: [
          { outputId: 'o1', kind: 'video', fileName: 'a.mp4', url: 'https://s3.example/a.mp4' },
        ],
      }),
    );
    show(serverClip('rendered'));

    await user.click(within(panel()).getByRole('button', { name: '미리보기 재생' }));

    const video = await within(panel()).findByLabelText('선택한 편집본 미리보기');
    expect(video.tagName).toBe('VIDEO');
    expect(video).toHaveAttribute('src', 'https://s3.example/a.mp4');
  });

  it('주소가 만료돼 영상을 못 읽으면 재생 단추로 돌아가 새 주소를 받는다', async () => {
    const user = userEvent.setup();
    let n = 0;
    stubFetch((url) => {
      // 패널이 저절로 읽는 것(비율 · 원본 방송 카드)은 주소 번호를 세지 않는다
      if (isPanelRead(url)) return new Promise<Response>(() => {});
      n += 1;
      return jsonResponse(200, {
        clipId: 5,
        expiresAt: '2026-09-20T13:00:00Z',
        files: [
          { outputId: 'o1', kind: 'video', fileName: 'a.mp4', url: `https://s3.example/a${n}.mp4` },
        ],
      });
    });
    show(serverClip('rendered'));

    await user.click(within(panel()).getByRole('button', { name: '미리보기 재생' }));
    const video = await within(panel()).findByLabelText('선택한 편집본 미리보기');
    fireEvent.error(video);

    await user.click(await within(panel()).findByRole('button', { name: '미리보기 재생' }));
    expect(await within(panel()).findByLabelText('선택한 편집본 미리보기')).toHaveAttribute(
      'src',
      'https://s3.example/a2.mp4',
    );
  });

  it('제목이 틀려 업로드가 안 나가면, 이어서 제목을 고칠 때 포커스를 빼앗지 않는다', async () => {
    const user = userEvent.setup();
    const spy = stubFetch(() => jsonResponse(500));
    show(serverClip('rendered'));
    const title = within(panel()).getByRole('textbox');

    await user.clear(title);
    await user.click(within(panel()).getByRole('button', { name: '업로드' }));
    await user.click(title);
    await user.type(title, 'Best play');

    expect(title).toHaveFocus();
    expect(title).toHaveValue('Best play');
    expect(spy.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0);
  });

  it('업로드 주문을 보내는 동안 제목이 잠긴다 — 보낸 뒤 고친 글자가 조용히 사라지지 않게', async () => {
    const user = userEvent.setup();
    stubFetch(() => new Promise<Response>(() => {}));
    show(serverClip('rendered'));

    await user.click(within(panel()).getByRole('button', { name: '업로드' }));

    expect(within(panel()).getByRole('textbox')).toHaveAttribute('readonly');
    const busy = within(panel()).getByRole('button', { name: /^업로드/ });
    expect(busy).toBeDisabled();
    expect(busy).toHaveAttribute('aria-busy', 'true');
    // 다른 단추에는 번지지 않는다(POK-443): 다운로드는 돌지 않는다
    expect(within(panel()).getByRole('button', { name: '다운로드' })).not.toHaveAttribute(
      'aria-busy',
    );
  });

  it('영상을 못 틀면 까닭을 알린다 — 단추만 조용히 돌아오지 않는다', async () => {
    const user = userEvent.setup();
    stubFetch(() =>
      jsonResponse(200, {
        clipId: 5,
        expiresAt: '2026-09-20T13:00:00Z',
        files: [
          { outputId: 'o1', kind: 'video', fileName: 'a.mp4', url: 'https://s3.example/a.mp4' },
        ],
      }),
    );
    show(serverClip('rendered'));

    await user.click(within(panel()).getByRole('button', { name: '미리보기 재생' }));
    fireEvent.error(await within(panel()).findByLabelText('선택한 편집본 미리보기'));

    expect(await screen.findByText('미리보기를 틀지 못했어요')).toBeInTheDocument();
  });

  it('영상이 없는 편집본은 미리보기가 장식이다', () => {
    show(serverClip('editing', null));
    expect(within(panel()).queryByRole('button', { name: '미리보기 재생' })).toBeNull();
  });

  // ── 누른 단추만 돈다(POK-443) ──

  /** 비율을 읽는 상세 요청(POK-254)은 답하지 않고, 나머지 요청은 시험이 손으로 답한다 */
  function holdRequests() {
    const pending: ((r: Response) => void)[] = [];
    const spy = stubFetch((url) =>
      isPanelRead(url)
        ? new Promise<Response>(() => {})
        : new Promise<Response>((resolve) => pending.push(resolve)),
    );
    return { spy, answer: (r: Response) => pending.shift()?.(r) };
  }
  const fileAccess = {
    clipId: 5,
    expiresAt: '2026-09-20T13:00:00Z',
    files: [{ outputId: 'o1', kind: 'video', fileName: 'a.mp4', url: 'https://s3.example/a.mp4' }],
  };

  it('렌더 재시도는 보내는 동안 그 단추만 돌고 연타해도 한 번만 주문한다 — 답이 오면 바로 만드는 중으로 옮긴다', async () => {
    const { spy, answer } = holdRequests();
    const renders = () => spy.mock.calls.filter(([url]) => String(url).endsWith('/renders'));
    const user = userEvent.setup();
    show(
      serverClip('failed', {
        ...rendered,
        status: 'failed',
        outputs: null,
        error: { code: 'X', message: null },
      }),
    );

    await user.click(within(panel()).getByRole('button', { name: '렌더 재시도' }));
    const busy = within(panel()).getByRole('button', { name: /^렌더 재시도/ });
    expect(busy).toHaveAttribute('aria-busy', 'true');
    expect(busy).toBeDisabled();
    await user.click(busy);
    expect(renders()).toHaveLength(1);
    expect(renders()[0]?.[0]).toBe('/api/clip/broadcasts/s1/recipes/12/renders');

    await act(async () =>
      answer(jsonResponse(201, { ...rendered, id: 6, status: 'queued', outputs: null })),
    );
    // 잠금이 풀린 사이 「렌더 재시도」로 돌아오지 않는다: 받은 영상으로 곧장 만드는 중이다
    await waitFor(() => expect(currentStep()).toHaveTextContent('대기'));
    expect(within(panel()).queryByRole('button', { name: /렌더 재시도/ })).toBeNull();
  });

  it('렌더 재시도가 거절되면 돌기를 멈추고 다시 누를 수 있다', async () => {
    const { answer } = holdRequests();
    const user = userEvent.setup();
    show(
      serverClip('failed', {
        ...rendered,
        status: 'failed',
        outputs: null,
        error: { code: 'X', message: null },
      }),
    );

    await user.click(within(panel()).getByRole('button', { name: '렌더 재시도' }));
    await act(async () => answer(jsonResponse(409, { error: 'source_not_ready' })));

    expect(await screen.findByText('다시 만들기 실패')).toBeInTheDocument();
    const again = within(panel()).getByRole('button', { name: '렌더 재시도' });
    expect(again).toBeEnabled();
    expect(again).not.toHaveAttribute('aria-busy');
  });

  it('다운로드는 주소를 받는 동안만 그 단추가 돌고 연타해도 주소를 한 번만 받는다 — 업로드는 막지 않는다', async () => {
    const { spy, answer } = holdRequests();
    const access = () => spy.mock.calls.filter(([url]) => String(url).endsWith('/file-access'));
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    try {
      const user = userEvent.setup();
      show(serverClip('rendered'));

      await user.click(within(panel()).getByRole('button', { name: '다운로드' }));
      const busy = within(panel()).getByRole('button', { name: /^다운로드/ });
      expect(busy).toHaveAttribute('aria-busy', 'true');
      await user.click(busy);
      expect(access()).toHaveLength(1);
      const upload = within(panel()).getByRole('button', { name: '업로드' });
      expect(upload).toBeEnabled();
      expect(upload).not.toHaveAttribute('aria-busy');

      await act(async () => answer(jsonResponse(200, fileAccess)));
      await waitFor(() => expect(click).toHaveBeenCalledTimes(1));
      expect(within(panel()).getByRole('button', { name: '다운로드' })).not.toHaveAttribute(
        'aria-busy',
      );
    } finally {
      click.mockRestore();
    }
  });

  it('미리보기는 주소를 받는 동안 재생 단추가 돌고 잠긴다', async () => {
    const { answer } = holdRequests();
    const user = userEvent.setup();
    show(serverClip('rendered'));

    await user.click(within(panel()).getByRole('button', { name: '미리보기 재생' }));
    const play = within(panel()).getByRole('button', { name: '미리보기 재생' });
    expect(play).toHaveAttribute('aria-busy', 'true');
    expect(play).toBeDisabled();
    expect(within(panel()).getByRole('button', { name: '다운로드' })).not.toHaveAttribute(
      'aria-busy',
    );

    await act(async () => answer(jsonResponse(200, fileAccess)));
    expect(await within(panel()).findByLabelText('선택한 편집본 미리보기')).toHaveAttribute(
      'src',
      'https://s3.example/a.mp4',
    );
  });

  it('보내는 중 표시는 그 편집본에만 선다 — 다른 편집본의 업로드는 돌지도 잠기지도 않는다', async () => {
    holdRequests();
    const user = userEvent.setup();
    const first = serverClip('rendered');
    const second = toLibraryClip(
      { ...first.entry!, recipeId: 13, latestClip: { ...rendered, id: 6, recipeId: 13 } },
      '9',
    );
    renderWithProviders(<LibraryScreen clips={[first, second]} selectedId="12" now={NOW} />);

    await user.click(within(panel()).getByRole('button', { name: '업로드' }));
    expect(within(panel()).getByRole('button', { name: /^업로드/ })).toHaveAttribute(
      'aria-busy',
      'true',
    );

    await user.click(screen.getByRole('button', { name: /^편집본 #13/ }));
    const other = within(panel()).getByRole('button', { name: '업로드' });
    expect(other).toBeEnabled();
    expect(other).not.toHaveAttribute('aria-busy');

    // 돌아오면 아직 보내는 중이다 — 표시는 패널이 아니라 편집본에 붙어 있다
    await user.click(screen.getByRole('button', { name: /^편집본 #12/ }));
    expect(within(panel()).getByRole('button', { name: /^업로드/ })).toHaveAttribute(
      'aria-busy',
      'true',
    );
  });
});

describe('LibraryScreen — 목록 로딩·실패 (POK-443)', () => {
  const entry = {
    recipeId: 12,
    streamId: 's1',
    creatorId: '9',
    recipeVersion: 1,
    cut: { inAtMs: 0, outAtMs: 30_000 },
    status: 'rendered',
    broadcast: { status: 'ended', startedAt: null, endedAt: null, vodExpiresAt: null },
    latestClip: null,
    createdAt: '2026-09-20T11:00:00Z',
    updatedAt: '2026-09-20T12:00:00Z',
  };

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('첫 응답 전에는 「없다」가 아니라 격자 스켈레톤이다 — 낭독기엔 status 한 줄만 들린다', async () => {
    let answer: (r: Response) => void = () => {};
    stubFetch((url) =>
      url.startsWith('/api/clip/library')
        ? new Promise<Response>((resolve) => (answer = resolve))
        : jsonResponse(200, { id: 9 }),
    );
    const { container } = renderWithProviders(<LibraryScreen />);

    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getByRole('status')).toHaveTextContent('편집본을 불러오는 중');
    // 로드 뒤 격자와 같은 9:16 카드 자리 — 열 수를 못 재는 jsdom에서는 8장
    const region = container.querySelector('[aria-busy="true"]')!;
    expect(region.querySelectorAll('li')).toHaveLength(8);
    expect(region.querySelector('ul')).toHaveAttribute('data-panel-open', 'false');
    // 칩 줄·빈 상태는 편집본이 있어야(없어야) 서는 자리라 미리 그리지 않는다
    expect(screen.queryByRole('group', { name: '상태 필터' })).toBeNull();
    expect(screen.queryByText('아직 보관한 편집본이 없어요')).toBeNull();
    expect(screen.queryByRole('list', { name: '편집본 목록' })).toBeNull();
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });

    await act(async () => answer(jsonResponse(200, { items: [entry], nextCursor: null })));
    expect(await screen.findByRole('list', { name: '편집본 목록' })).toBeInTheDocument();
    expect(container.querySelector('[aria-busy="true"]')).toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('스켈레톤은 실제 격자 열 수를 재어 두 줄을 채운다 — 5열이면 10장', () => {
    const real = window.getComputedStyle.bind(window);
    vi.spyOn(window, 'getComputedStyle').mockImplementation((el, pseudo) =>
      el instanceof HTMLUListElement
        ? ({
            gridTemplateColumns: '213.6px 213.6px 213.6px 213.6px 213.6px',
          } as CSSStyleDeclaration)
        : real(el, pseudo),
    );
    const { container } = renderWithProviders(<LibrarySkeleton panelOpen={false} />);
    expect(container.querySelectorAll('li')).toHaveLength(10);
  });

  it('스켈레톤도 패널이 열린 격자 규칙(data-panel-open)을 탄다', () => {
    const { container } = renderWithProviders(<LibrarySkeleton panelOpen />);
    expect(container.querySelector('ul')).toHaveAttribute('data-panel-open', 'true');
  });

  it('첫 조회가 실패하면 스켈레톤·빈 상태가 아니라 못 읽었다고만 말한다', async () => {
    stubFetch((url) =>
      url.startsWith('/api/clip/library')
        ? jsonResponse(503, { error: 'unavailable' })
        : jsonResponse(200, { id: 9 }),
    );
    renderWithProviders(<LibraryScreen />);

    expect(await screen.findByRole('alert')).toHaveTextContent('편집본 목록을 못 읽었어요');
    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.queryByRole('list', { name: '편집본 목록' })).toBeNull();
    expect(screen.queryByText('아직 보관한 편집본이 없어요')).toBeNull();
  });

  it('한 번 읽은 뒤 다시 읽기가 실패하면 격자를 그대로 두고 위에 한 줄만 알린다 — 다음 읽기가 되면 걷힌다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let down = false;
    stubFetch((url) =>
      url.startsWith('/api/clip/library?') && down
        ? jsonResponse(503, { error: 'unavailable' })
        : url.startsWith('/api/clip/library?')
          ? jsonResponse(200, { items: [entry], nextCursor: null })
          : jsonResponse(200, { id: 9 }),
    );
    renderWithProviders(<LibraryScreen />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    expect(cards()).toHaveLength(1);

    down = true;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5 * 60_000);
    });
    expect(cards()).toHaveLength(1);
    expect(screen.getByRole('alert')).toHaveTextContent('편집본 목록을 새로 읽지 못했어요');
    expect(screen.getByRole('group', { name: '상태 필터' })).toBeInTheDocument();

    down = false;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5 * 60_000);
    });
    expect(cards()).toHaveLength(1);
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

describe('LibraryScreen — 원본 방송 카드 (POK-511)', () => {
  const NOW = new Date('2026-10-30T14:30:00+09:00');
  const entry = {
    recipeId: 12,
    streamId: 's1',
    creatorId: '9',
    recipeVersion: 2,
    // 1:23:45 ~ 1:24:30 — 기준점 19:52 + 1:23:45 = 21:15:45, 카테고리를 바꾼 뒤 장면이다
    cut: { inAtMs: 5_025_000, outAtMs: 5_070_000 },
    status: 'rendered' as const,
    broadcast: {
      status: 'ended',
      startedAt: '2026-10-17T19:52:00+09:00',
      endedAt: '2026-10-17T22:06:00+09:00',
      vodExpiresAt: '2026-12-16T22:06:00+09:00',
    },
    latestClip: null,
    createdAt: '2026-10-30T14:05:00+09:00',
    updatedAt: '2026-10-30T14:20:00+09:00',
  };
  const broadcastRow = {
    streamId: 's1',
    status: 'ended',
    relation: 'OWNER',
    startedAt: entry.broadcast.startedAt,
    endedAt: entry.broadcast.endedAt,
    vodExpiresAt: entry.broadcast.vodExpiresAt,
    title: '마지막 제목',
    category: '마지막 카테고리',
    platform: 'CHZZK',
  };
  const timeline = {
    segments: [
      {
        platform: 'CHZZK',
        from: '2026-10-17T19:52:00+09:00',
        until: '2026-10-17T20:30:00+09:00',
        title: '인사 방송',
        category: '저스트 채팅',
        categoryImageUrl: 'https://p/talk.png',
        tags: [],
      },
      {
        platform: 'CHZZK',
        from: '2026-10-17T20:30:00+09:00',
        until: null,
        title: '[롤] 다이아 승급전',
        category: '리그 오브 레전드',
        categoryImageUrl: 'https://p/lol.png',
        tags: [],
      },
    ],
    truncated: false,
  };

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  function show() {
    return renderWithProviders(
      <LibraryScreen clips={[toLibraryClip(entry, '9')]} selectedId="12" now={NOW} />,
    );
  }

  it('그 장면 때의 방송 제목 · 카테고리 · 플랫폼 · 방송일 · 길이 · 장면 위치와 지난 방송으로 가는 길이 선다', async () => {
    stubFetch((url) => {
      if (url.startsWith('/api/clip/broadcasts?state=past'))
        return jsonResponse(200, { broadcasts: [broadcastRow], nextCursor: null });
      if (url.startsWith('/api/clip/broadcasts?state=live'))
        return jsonResponse(200, { broadcasts: [], nextCursor: null });
      if (url === '/api/clip/broadcasts/s1/broadcast-info/timeline')
        return jsonResponse(200, timeline);
      return new Promise<Response>(() => {});
    });
    const { container } = show();
    const card = within(within(panel()).getByRole('region', { name: '원본 방송' }));

    expect(await card.findByText('[롤] 다이아 승급전')).toBeInTheDocument();
    expect(card.getByText('리그 오브 레전드 · 치지직')).toBeInTheDocument();
    expect(card.getByText('10월 17일 (토) 19:52 · 2시간 14분')).toBeInTheDocument();
    expect(card.getByText('1:23:45 ~ 1:24:30')).toBeInTheDocument();
    expect(card.getByRole('link', { name: '지난 방송 열기' })).toHaveAttribute(
      'href',
      '/broadcast/vod/s1',
    );
    // 포스터는 그 장면 때 카테고리의 것이다(방송 끝 카테고리가 아니다)
    expect(container.querySelector('img[src="https://p/lol.png"]')).not.toBeNull();
    // 늘 같은 값이던 칸은 없다
    expect(within(panel()).queryByText('템플릿')).toBeNull();
    await act(async () => {
      expect(await axe(panel())).toHaveNoViolations();
    });
  });

  it('방송 정보를 못 읽어도 지어내지 않고 원본 방송 날짜로 물러서며, 패널의 나머지는 그대로다', async () => {
    stubFetch((url) =>
      url.startsWith('/api/clip/broadcasts')
        ? jsonResponse(503, { error: 'x' })
        : new Promise<Response>(() => {}),
    );
    show();
    const card = within(within(panel()).getByRole('region', { name: '원본 방송' }));

    expect(await card.findByText('10월 17일 방송')).toBeInTheDocument();
    expect(card.queryByText(/치지직|SOOP/)).toBeNull();
    // 보관함 줄에 있는 방송일 · 장면은 그대로 보인다
    expect(card.getByText('10월 17일 (토) 19:52 · 2시간 14분')).toBeInTheDocument();
    expect(card.getByText('1:23:45 ~ 1:24:30')).toBeInTheDocument();
    expect(within(panel()).getByRole('button', { name: '업로드' })).toBeEnabled();
  });

  it('방송 줄을 찾으면 목록을 더 넘기지 않는다 — 한 줄 때문에 목록 전부를 읽지 않는다(PR #462 1차 리뷰)', async () => {
    const spy = stubFetch((url) => {
      if (url.startsWith('/api/clip/broadcasts?state=past'))
        return jsonResponse(
          200,
          url.includes('cursor=')
            ? { broadcasts: [], nextCursor: null }
            : { broadcasts: [broadcastRow], nextCursor: 'next' },
        );
      if (url.startsWith('/api/clip/broadcasts?state=live'))
        return jsonResponse(200, { broadcasts: [], nextCursor: null });
      if (url === '/api/clip/broadcasts/s1/broadcast-info/timeline')
        return jsonResponse(200, timeline);
      return new Promise<Response>(() => {});
    });
    show();
    const card = within(within(panel()).getByRole('region', { name: '원본 방송' }));

    expect(await card.findByText('[롤] 다이아 승급전')).toBeInTheDocument();
    const pages = spy.mock.calls.map(([url]) => String(url));
    expect(pages.filter((url) => url.startsWith('/api/clip/broadcasts?'))).toHaveLength(2);
    expect(pages.some((url) => url.includes('cursor='))).toBe(false);
  });

  it('방송 중이면 시점별 기록을 다시 읽어, 패널을 연 뒤 바꾼 제목 · 카테고리가 뒤 장면에 보인다(PR #462 1차 리뷰)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const live = { ...entry, broadcast: { ...entry.broadcast, status: 'live', endedAt: null } };
    let reads = 0;
    stubFetch((url) => {
      if (url.startsWith('/api/clip/broadcasts?state=live'))
        return jsonResponse(200, {
          broadcasts: [{ ...broadcastRow, status: 'live', endedAt: null }],
          nextCursor: null,
        });
      if (url.startsWith('/api/clip/broadcasts?state=past'))
        return jsonResponse(200, { broadcasts: [], nextCursor: null });
      if (url === '/api/clip/broadcasts/s1/broadcast-info/timeline') {
        reads += 1;
        // 처음 읽을 때는 카테고리를 바꾸기 전이라 첫 구간이 아직 끝나지 않았다
        return jsonResponse(
          200,
          reads === 1
            ? { segments: [{ ...timeline.segments[0], until: null }], truncated: false }
            : timeline,
        );
      }
      return new Promise<Response>(() => {});
    });
    renderWithProviders(
      <LibraryScreen clips={[toLibraryClip(live, '9')]} selectedId="12" now={NOW} />,
    );
    const card = within(within(panel()).getByRole('region', { name: '원본 방송' }));
    expect(await card.findByText('인사 방송')).toBeInTheDocument();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(await card.findByText('[롤] 다이아 승급전')).toBeInTheDocument();
    expect(card.getByText('리그 오브 레전드 · 치지직')).toBeInTheDocument();
  });
});
