import { act } from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ChatPanel } from '@/features/broadcast/livenow/ChatPanel';
import type { ChatPanelMessage } from '@/features/broadcast/livenow/useChatPanelMockState';
import { fakeScrollMetrics } from '@/test/scrollMetrics';

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

describe('ChatPanel: 지난 방송 다시 보기(POK-324)', () => {
  it('영상을 옮기면 위로 올려 읽던 중이어도 그 시점(맨 아래)을 따라간다: 다시 세우지 않아 목록 포커스가 남는다', () => {
    const view = render(<ChatPanel {...PROPS} mode="ended" replayClock="0:10:00" followJump={0} />);
    const list = chatList();
    list.focus();
    fakeScrollMetrics(list, { scrollHeight: 1000, clientHeight: 300, scrollTop: 200 });
    fireEvent.scroll(list);
    expect(screen.getByText(/지난 메시지 보는 중/)).toBeInTheDocument();
    const setScrollTop = fakeScrollMetrics(list, {
      scrollHeight: 1000,
      clientHeight: 300,
      scrollTop: 200,
    });

    view.rerender(
      <ChatPanel
        {...PROPS}
        messages={[...MESSAGES, NEXT_MESSAGE]}
        mode="ended"
        replayClock="0:02:00"
        followJump={1}
      />,
    );
    expect(screen.getByText(/^0:02:00 시점까지 보는 중/)).toBeInTheDocument();
    expect(setScrollTop).toHaveBeenCalled();
    expect(chatList()).toBe(list);
    expect(document.activeElement).toBe(list);
  });

  it('줄은 그대로이고 옮긴 횟수만 바뀌어도 맨 아래로 내린다: 같은 카드를 다시 누를 때', () => {
    // 영상이 없으면 같은 카드를 다시 눌러도 줄이 같다. 바닥 문구만 「따라가는 중」으로 돌고 목록은 위에 머물 수 있었다(리뷰 2라운드)
    const view = render(<ChatPanel {...PROPS} mode="ended" replayClock="0:10:00" followJump={0} />);
    const list = chatList();
    fakeScrollMetrics(list, { scrollHeight: 1000, clientHeight: 300, scrollTop: 200 });
    fireEvent.scroll(list);
    const setScrollTop = fakeScrollMetrics(list, {
      scrollHeight: 1000,
      clientHeight: 300,
      scrollTop: 200,
    });

    view.rerender(<ChatPanel {...PROPS} mode="ended" replayClock="0:10:00" followJump={1} />);

    expect(setScrollTop).toHaveBeenCalledWith(1000);
    expect(screen.getByText(/^0:10:00 시점까지 보는 중/)).toBeInTheDocument();
  });

  it('채팅을 읽는 중·읽지 못함은 머리의 알림 자리에도 싣는다: 목록 안의 줄은 듣는 사람이 훑기 전에는 모른다', () => {
    render(<ChatPanel {...PROPS} mode="ended" statusNote="채팅을 불러오지 못했어요" />);
    // 배지 글과 띄어 싣는다: 붙으면 「지난 채팅채팅을…」으로 읽혔다(리뷰 2라운드)
    expect(screen.getByRole('status').textContent).toBe('지난 채팅 채팅을 불러오지 못했어요');
  });

  it('어느 시점을 보일지 아직 모르면 바닥은 「시점을 확인하는 중」이다: 끝까지 본 것처럼 말하지 않는다', () => {
    // 녹화가 자리를 알리기 전·명부 답 전(리뷰 2라운드)
    render(
      <ChatPanel
        {...PROPS}
        mode="ended"
        replayClock={null}
        replayWaiting
        statusNote="채팅을 불러오고 있어요"
      />,
    );
    expect(screen.queryByText(/마지막 메시지까지 보는 중/)).toBeNull();
    // 바닥 줄은 「키워드 설정」 단추와 같은 칸이다
    const footer = screen.getByRole('button', { name: '키워드 설정' }).parentElement!;
    expect(footer).toHaveTextContent(/^시점을 확인하는 중/);
  });

  it('바닥은 어디까지 보는지만 말한다: 읽는 중·실패 문장은 목록의 상태 줄과 낭독에만 있다', () => {
    // 바닥에도 쓰면 같은 말을 두 번 썼고, 긴 실패 문구가 바닥을 두 줄로 접어 따라가기를 풀 때마다 목록 높이가 바뀌어 맨 아래로
    // 도로 끌려갔다(리뷰 3라운드)
    render(
      <ChatPanel
        {...PROPS}
        mode="ended"
        replayClock={null}
        statusNote="채팅을 불러오지 못했어요. 잠시 뒤 다시 불러와요"
      />,
    );
    const footer = screen.getByRole('button', { name: '키워드 설정' }).parentElement!;
    expect(footer).toHaveTextContent(/^마지막 메시지까지 보는 중/);
    expect(footer).not.toHaveTextContent('불러오지 못했어요');
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

  it('끝난 방송의 분당 수는 「분당 평균」을 천 단위 쉼표와 소수 한 자리로 쓴다: 통계와 같은 꼴이다', () => {
    // 소수 한 자리만 맞추면 큰 방송은 「분당 1234.5」로 라이브 바닥·옆 통계의 쉼표와 갈렸다(리뷰 6라운드)
    render(<ChatPanel {...PROPS} mode="ended" ratePerMinute={1234.5} />);
    expect(screen.getByText('마지막 메시지까지 보는 중 · 분당 평균 1,234.5')).toBeInTheDocument();
  });
});

describe('ChatPanel: 후원 줄(POK-431)', () => {
  it('문구가 빈 후원(SOOP 별풍선)은 문구 자리를 그리지 않는다: 후원마다 빈 줄이 생기지 않게', () => {
    renderPanel({
      messages: [
        { id: 7, kind: 'donation', name: '숲팬', amountLabel: '별풍선 1,000개', text: '' },
        { id: 8, kind: 'donation', name: '도네초코', amountLabel: '치즈 5,000', text: '승급 기원' },
      ],
    });
    expect(screen.getByText('숲팬 · 별풍선 1,000개').closest('li')!.children).toHaveLength(1);
    expect(screen.getByText('도네초코 · 치즈 5,000').closest('li')!.children).toHaveLength(2);
    expect(screen.getByText('승급 기원')).toBeInTheDocument();
  });
});

describe('ChatPanel: 플랫폼 표시(POK-466 · POK-476)', () => {
  it('채팅 · 후원 줄 앞에 플랫폼 로고를 두고 화면 읽기 이름을 단다. 글자는 쓰지 않고, 모르면 그리지 않는다', () => {
    renderPanel({
      messages: [
        { id: 1, kind: 'chat', name: '숲시청자', text: 'ㅋㅋ', colorIndex: 0, platform: 'SOOP' },
        {
          id: 2,
          kind: 'donation',
          name: '도네초코',
          amountLabel: '치즈 5,000',
          text: '',
          platform: 'CHZZK',
        },
        { id: 3, kind: 'chat', name: '옛줄', text: '안녕', colorIndex: 1, platform: null },
        { id: 4, kind: 'chat', name: '새플랫폼', text: '?', colorIndex: 2, platform: 'TWITCH' },
      ],
    });
    const soop = screen.getByText('숲시청자').closest('li')!;
    // 마크는 설정 화면 채널 연동 줄의 것 그대로다: 뷰박스로 어느 로고인지 가른다(뒤바뀌면 빨갛다)
    expect(
      within(soop).getByRole('img', { name: 'SOOP' }).querySelector('svg')?.getAttribute('viewBox'),
    ).toBe('0 0 378.77 215.21');
    expect(soop).toHaveTextContent(/^숲시청자/);
    const chzzk = screen.getByText('도네초코 · 치즈 5,000').closest('li')!;
    expect(
      within(chzzk)
        .getByRole('img', { name: '치지직' })
        .querySelector('svg')
        ?.getAttribute('viewBox'),
    ).toBe('0 0 24 24');
    expect(within(screen.getByText('옛줄').closest('li')!).queryByRole('img')).toBeNull();
    expect(within(screen.getByText('새플랫폼').closest('li')!).queryByRole('img')).toBeNull();
  });
});
