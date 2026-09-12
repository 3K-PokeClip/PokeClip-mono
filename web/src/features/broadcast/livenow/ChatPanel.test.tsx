import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ChatPanel } from '@/features/broadcast/livenow/ChatPanel';
import type { ChatPanelMessage } from '@/features/broadcast/livenow/useChatPanelMockState';

// 라이브 화면 목업은 수집이 늘 끊긴 상태(useLiveMockState.chatWarning: true)라 상태줄의 정상 분기가
// 화면 테스트에선 한 번도 그려지지 않는다 — 여기서 직접 그린다.

const MESSAGES: ChatPanelMessage[] = [
  { id: 1, kind: 'chat', name: '겜돌이', text: '한타 각', colorIndex: 1 },
  { id: 2, kind: 'system', text: '하이라이트 감지 · 1:24:03 구간이 카드로 만들어졌어요' },
];

const NEXT_MESSAGE: ChatPanelMessage = {
  id: 3,
  kind: 'chat',
  name: '별사탕',
  text: '방금 그거 미쳤다',
  colorIndex: 2,
};

const PROPS = {
  surges: [{ keyword: 'ㅋㅋㅋㅋ', count: 214 }],
  messages: MESSAGES,
  ratePerMinute: 1200,
  collectionWarning: false,
  onCollapse: vi.fn(),
};

function renderPanel(overrides: Partial<typeof PROPS> = {}) {
  const view = render(<ChatPanel {...PROPS} {...overrides} />);
  return {
    ...view,
    /** 같은 패널에 새 메시지가 온 상황 — 마운트가 아니라 갱신이어야 바닥 고정을 볼 수 있다 */
    update: (next: Partial<typeof PROPS>) =>
      view.rerender(<ChatPanel {...PROPS} {...overrides} {...next} />),
  };
}

/**
 * jsdom은 레이아웃이 없어 스크롤 치수가 전부 0이다 — 목록에 값을 꽂아 넘친 상태를 흉내 낸다.
 * scrollTop 세터는 spy로 둔다: 「끝에 있을 때만 바닥으로 붙인다」를 관찰할 유일한 창구다
 * (빈 함수로 두면 그 동작이 통째로 빠져도 테스트가 전부 초록이다).
 */
function fakeScrollMetrics(
  el: HTMLElement,
  { scrollHeight, clientHeight, scrollTop }: Record<string, number>,
) {
  const setScrollTop = vi.fn();
  Object.defineProperty(el, 'scrollHeight', { configurable: true, get: () => scrollHeight });
  Object.defineProperty(el, 'clientHeight', { configurable: true, get: () => clientHeight });
  Object.defineProperty(el, 'scrollTop', {
    configurable: true,
    get: () => scrollTop,
    set: setScrollTop,
  });
  return setScrollTop;
}

function chatList() {
  return screen.getByRole('list', { name: '채팅 메시지' });
}

describe('ChatPanel — 하단 상태줄', () => {
  it('수집 중이면 「따라가는 중」과 천 단위 구분 분당 건수를 말한다', () => {
    renderPanel();
    // 통계 지표('2,310' 식)와 같은 서식 — 한 화면에서 같은 값이 두 서식으로 갈리지 않는다
    expect(screen.getByText('최신 메시지 따라가는 중 · 분당 1,200')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('수집 중');
  });

  it('수집이 끊기면 분당 건수 대신 끊김을 말한다', () => {
    renderPanel({ collectionWarning: true });
    expect(screen.getByText('수집 끊김 · 새 메시지 없음')).toBeInTheDocument();
    expect(screen.queryByText(/분당/)).not.toBeInTheDocument();
  });

  it('위로 올라가 옛 줄을 읽는 동안엔 「지난 메시지 보는 중」이 된다', () => {
    renderPanel();

    fakeScrollMetrics(chatList(), { scrollHeight: 1000, clientHeight: 300, scrollTop: 200 });
    fireEvent.scroll(chatList());
    expect(screen.getByText('지난 메시지 보는 중 · 분당 1,200')).toBeInTheDocument();

    // 바닥 근처(8px 안)로 돌아오면 다시 따라간다
    fakeScrollMetrics(chatList(), { scrollHeight: 1000, clientHeight: 300, scrollTop: 695 });
    fireEvent.scroll(chatList());
    expect(screen.getByText('최신 메시지 따라가는 중 · 분당 1,200')).toBeInTheDocument();
  });
});

describe('ChatPanel — 바닥 고정', () => {
  it('끝에 있으면 새 줄이 올 때 바닥까지 따라 내려간다', () => {
    const { update } = renderPanel();
    // 1000 − 700 − 300 = 0 ≤ 8 → 끝에 있다
    const setScrollTop = fakeScrollMetrics(chatList(), {
      scrollHeight: 1000,
      clientHeight: 300,
      scrollTop: 700,
    });
    fireEvent.scroll(chatList());
    setScrollTop.mockClear();

    update({ messages: [...MESSAGES, NEXT_MESSAGE] });
    expect(setScrollTop).toHaveBeenCalledWith(1000);
  });

  it('위로 올라가 있으면 새 줄이 와도 보던 자리를 지킨다', () => {
    const { update } = renderPanel();
    const setScrollTop = fakeScrollMetrics(chatList(), {
      scrollHeight: 1000,
      clientHeight: 300,
      scrollTop: 200,
    });
    fireEvent.scroll(chatList());
    setScrollTop.mockClear();

    update({ messages: [...MESSAGES, NEXT_MESSAGE] });
    expect(setScrollTop).not.toHaveBeenCalled();
  });
});

describe('ChatPanel — 목록', () => {
  it('시간순 DOM이고 키보드로 들어갈 수 있다', () => {
    renderPanel();
    expect(chatList()).toHaveAttribute('tabindex', '0');
    const items = screen.getAllByRole('listitem');
    expect(items[0]).toHaveTextContent('한타 각');
    expect(items[1]).toHaveTextContent('하이라이트 감지');
  });
});
