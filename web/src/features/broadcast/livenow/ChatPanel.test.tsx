import { act } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
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

/**
 * jsdom엔 ResizeObserver가 없어 크기 변화 경로가 가드에 걸려 통째로 건너뛴다 — 스텁으로 콜백을
 * 잡아 두고 손으로 발화시킨다. 없으면 그 effect를 지워도 테스트가 전부 초록이다.
 */
function stubResizeObserver() {
  // 해제까지 흉내 낸다 — following이 바뀌면 effect가 관찰자를 다시 만들므로, 끊긴 콜백을 남겨 두면
  // 옛 following을 품은 클로저가 함께 발화해 테스트가 실제와 다른 것을 본다.
  const callbacks = new Set<() => void>();
  vi.stubGlobal(
    'ResizeObserver',
    class {
      callback: () => void;
      constructor(callback: () => void) {
        this.callback = callback;
        callbacks.add(callback);
      }
      observe() {}
      unobserve() {}
      disconnect() {
        callbacks.delete(this.callback);
      }
    },
  );
  // 브라우저 관찰자와 달리 손으로 발화시키므로 act로 감싼다 — 아니면 상태 갱신이 단언 전에 안 흐른다
  return {
    resize: () =>
      act(() => {
        callbacks.forEach((callback) => callback());
      }),
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

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
  it('끝으로 돌아온 뒤 새 줄이 오면 바닥까지 따라 내려간다', () => {
    const { update } = renderPanel();
    // 초기값이 이미 「따라가는 중」이라, 한 번 떨어뜨렸다 되돌려야 onScroll 연결이 실제로 검증된다
    fakeScrollMetrics(chatList(), { scrollHeight: 1000, clientHeight: 300, scrollTop: 200 });
    fireEvent.scroll(chatList());
    expect(screen.getByText(/지난 메시지 보는 중/)).toBeInTheDocument();

    const setScrollTop = fakeScrollMetrics(chatList(), {
      scrollHeight: 1000,
      clientHeight: 300,
      scrollTop: 700,
    });
    fireEvent.scroll(chatList());
    expect(screen.getByText(/최신 메시지 따라가는 중/)).toBeInTheDocument();
    setScrollTop.mockClear();

    update({ messages: [...MESSAGES, NEXT_MESSAGE] });
    // 실제 브라우저는 scrollHeight − clientHeight로 클램프한다 — 정확한 값이 아니라 「바닥 이상」을 본다
    expect(setScrollTop).toHaveBeenCalled();
    expect(setScrollTop.mock.calls.at(-1)?.[0]).toBeGreaterThanOrEqual(700);
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

describe('ChatPanel — 크기 변화', () => {
  it('따라가던 중 목록이 줄어들면 다시 재지 않고 바닥에 붙인다', () => {
    // 회귀: 크기 변화에서 무조건 다시 재면, 창을 줄인 것만으로 바닥을 따라가던 사용자가 이탈했다.
    // 브라우저는 컨테이너가 낮아져도 scrollTop을 그대로 두므로 거리가 줄어든 높이만큼 벌어진다.
    const observer = stubResizeObserver();
    renderPanel();

    const setScrollTop = fakeScrollMetrics(chatList(), {
      scrollHeight: 1000,
      clientHeight: 200,
      scrollTop: 700,
    });
    observer.resize();

    expect(setScrollTop).toHaveBeenCalled();
    expect(screen.getByText(/최신 메시지 따라가는 중/)).toBeInTheDocument();
  });

  it('따라가지 않던 중에는 다시 재어 갇히지 않게 한다', () => {
    const observer = stubResizeObserver();
    renderPanel();

    fakeScrollMetrics(chatList(), { scrollHeight: 1000, clientHeight: 300, scrollTop: 200 });
    fireEvent.scroll(chatList());
    expect(screen.getByText(/지난 메시지 보는 중/)).toBeInTheDocument();

    // 창이 커져 더는 넘치지 않는다 — 스크롤할 것이 없으니 다시 재지 않으면 영영 갇힌다
    fakeScrollMetrics(chatList(), { scrollHeight: 1000, clientHeight: 1000, scrollTop: 0 });
    observer.resize();
    expect(screen.getByText(/최신 메시지 따라가는 중/)).toBeInTheDocument();
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
