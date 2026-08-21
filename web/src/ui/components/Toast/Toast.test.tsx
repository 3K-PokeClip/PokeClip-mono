import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { DismissableLayer } from '../../primitives/DismissableLayer';
import { ToastProvider, useToast } from './Toast';

let api: ReturnType<typeof useToast>;

function Capture() {
  api = useToast();
  return null;
}

function setup(extra?: React.ReactNode) {
  return render(
    <ToastProvider>
      <Capture />
      {extra}
    </ToastProvider>,
  );
}

function cards() {
  return Array.from(document.querySelectorAll<HTMLElement>('[data-tone]'));
}

function card(index: number): HTMLElement {
  const found = cards()[index];
  if (!found) throw new Error(`토스트 카드 ${index}번이 없다`);
  return found;
}

function advance(ms: number) {
  act(() => {
    vi.advanceTimersByTime(ms);
  });
}

describe('Toast', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('닫히는 방식이 톤마다 다르다 — 5초 / 경고 7초 / 오류·진행은 자동으로 안 닫힌다', () => {
    setup();

    act(() => {
      api.toast({ tone: 'success', title: '발행 완료' });
    });
    advance(4999);
    expect(screen.getByText('발행 완료')).toBeInTheDocument();
    advance(1);
    expect(screen.queryByText('발행 완료')).not.toBeInTheDocument();

    act(() => {
      api.toast({ tone: 'warning', title: '연동 만료 임박' });
    });
    advance(5000);
    expect(screen.getByText('연동 만료 임박')).toBeInTheDocument();
    advance(2000);
    expect(screen.queryByText('연동 만료 임박')).not.toBeInTheDocument();

    act(() => {
      api.toast({ tone: 'error', title: '업로드 실패' });
      api.toast({ tone: 'progress', title: '업로드 중' });
    });
    advance(10_000);
    expect(screen.getByText('업로드 실패')).toBeInTheDocument();
    expect(screen.getByText('업로드 중')).toBeInTheDocument();
  });

  it('포인터가 올라가면 타이머가 멈추고, 벗어나면 남은 시간부터 이어서 간다', () => {
    setup();
    act(() => {
      api.toast({ tone: 'success', title: '발행 완료' });
    });

    advance(3000);
    fireEvent.pointerOver(card(0));
    advance(10_000);
    expect(screen.getByText('발행 완료')).toBeInTheDocument();

    fireEvent.pointerOut(card(0), { relatedTarget: document.body });
    advance(1999);
    expect(screen.getByText('발행 완료')).toBeInTheDocument();
    advance(1);
    expect(screen.queryByText('발행 완료')).not.toBeInTheDocument();
  });

  it('포커스에도 타이머가 멈춘다 — 키보드 사용자는 포인터를 올릴 수 없다', () => {
    setup();
    act(() => {
      api.toast({
        tone: 'success',
        title: '발행 완료',
        action: { label: '보기', onClick: vi.fn() },
      });
    });

    advance(1000);
    act(() => screen.getByRole('button', { name: '보기' }).focus());
    advance(10_000);
    expect(screen.getByText('발행 완료')).toBeInTheDocument();

    act(() => screen.getByRole('button', { name: '보기' }).blur());
    advance(4000);
    expect(screen.queryByText('발행 완료')).not.toBeInTheDocument();
  });

  it('동시에 3개까지만 보이고 나머지는 「이전 알림 N개 더」로 접힌다', () => {
    setup();
    act(() => {
      ['1번', '2번', '3번', '4번'].forEach((title, i) =>
        api.toast({ tone: 'error', title, dedupeKey: `k${i}` }),
      );
    });

    expect(cards()).toHaveLength(3);
    expect(screen.getByText('이전 알림 1개 더')).toBeInTheDocument();
    expect(screen.queryByText('1번')).not.toBeInTheDocument();
    // 최신이 아래 — 마지막 카드가 가장 최근 것이다.
    expect(card(2)).toHaveTextContent('4번');
  });

  it('같은 종류가 연속으로 발생하면 새로 쌓지 않고 최신 토스트를 갱신한다', () => {
    setup();
    act(() => {
      api.toast({ tone: 'error', title: '업로드 실패', description: '1번째' });
      api.toast({ tone: 'error', title: '업로드 실패', description: '2번째' });
    });

    expect(cards()).toHaveLength(1);
    expect(screen.getByText('2번째')).toBeInTheDocument();
    expect(screen.queryByText('1번째')).not.toBeInTheDocument();
  });

  it('톤에 맞는 role이 붙는다 — 오류·경고만 하던 말을 끊는다', () => {
    setup();
    act(() => {
      api.toast({ tone: 'success', title: '성공', dedupeKey: 'a' });
      api.toast({ tone: 'warning', title: '경고', dedupeKey: 'b' });
    });

    expect(screen.getByText('성공').closest('[data-tone]')).toHaveAttribute('role', 'status');
    expect(screen.getByText('경고').closest('[data-tone]')).toHaveAttribute('role', 'alert');
  });

  it('Esc로 최신 토스트가 닫힌다', () => {
    setup();
    act(() => {
      api.toast({ tone: 'error', title: '먼저', dedupeKey: 'a' });
      api.toast({ tone: 'error', title: '나중', dedupeKey: 'b' });
    });

    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByText('나중')).not.toBeInTheDocument();
    expect(screen.getByText('먼저')).toBeInTheDocument();
  });

  it('모달·드로어가 열려 있으면 Esc를 그쪽에 양보한다', () => {
    const onDismiss = vi.fn();
    setup(
      <DismissableLayer onDismiss={onDismiss}>
        <div>모달</div>
      </DismissableLayer>,
    );
    act(() => {
      api.toast({ tone: 'error', title: '업로드 실패' });
    });

    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onDismiss).toHaveBeenCalledTimes(1);
    expect(screen.getByText('업로드 실패')).toBeInTheDocument();
  });

  it('되돌리기를 액션 자리에 받는다', () => {
    const undo = vi.fn();
    setup();
    act(() => {
      api.toast({ tone: 'success', title: '기본 편집자로 변경했어요', undo });
    });

    fireEvent.click(screen.getByRole('button', { name: '되돌리기' }));
    expect(undo).toHaveBeenCalledTimes(1);
  });

  it('닫기 버튼으로 닫힌다', () => {
    setup();
    act(() => {
      api.toast({ tone: 'error', title: '업로드 실패' });
    });

    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(screen.queryByText('업로드 실패')).not.toBeInTheDocument();
  });

  // 리뷰 #101 — 정지를 붙들던 카드가 사라지면 pointerout·blur가 오지 않는다.
  // 그때 정지가 안 풀리면 이후 토스트가 영영 자동으로 닫히지 않았다.
  it('포인터를 올린 채 닫아도 다음 토스트는 정상적으로 닫힌다', () => {
    setup();
    act(() => {
      api.toast({ tone: 'success', title: '첫 토스트', dedupeKey: 'a' });
    });
    fireEvent.pointerOver(card(0));
    fireEvent.click(screen.getByRole('button', { name: '닫기' }));

    act(() => {
      api.toast({ tone: 'success', title: '두 번째 토스트', dedupeKey: 'b' });
    });
    advance(5000);
    expect(screen.queryByText('두 번째 토스트')).not.toBeInTheDocument();
  });

  it('닫기 버튼에 포커스를 준 채 닫아도 다음 토스트는 정상적으로 닫힌다', () => {
    setup();
    act(() => {
      api.toast({ tone: 'success', title: '첫 토스트', dedupeKey: 'a' });
    });
    const close = screen.getByRole('button', { name: '닫기' });
    act(() => close.focus());
    act(() => close.click());

    act(() => {
      api.toast({ tone: 'success', title: '두 번째 토스트', dedupeKey: 'b' });
    });
    advance(5000);
    expect(screen.queryByText('두 번째 토스트')).not.toBeInTheDocument();
  });

  // 리뷰 #101 — 내용만 바꾸는 갱신이 타이머 바만 되감아 실제 닫힘 시각과 어긋났다.
  it('내용만 바꾸는 update는 데드라인을 옮기지 않는다', () => {
    setup();
    let id = '';
    act(() => {
      id = api.toast({ tone: 'success', title: '처음 제목' });
    });

    // 타이머 바는 version을 key로 쓴다 — 같은 노드가 유지돼야 되감기지 않는다.
    const barBefore = card(0).lastElementChild?.firstElementChild;
    advance(4000);
    act(() => api.update(id, { title: '바뀐 제목' }));
    expect(screen.getByText('바뀐 제목')).toBeInTheDocument();
    expect(card(0).lastElementChild?.firstElementChild).toBe(barBefore);

    advance(999);
    expect(screen.getByText('바뀐 제목')).toBeInTheDocument();
    advance(1);
    expect(screen.queryByText('바뀐 제목')).not.toBeInTheDocument();
  });

  it('톤을 바꾸는 update는 새 톤의 자동 닫힘으로 타이머를 다시 건다', () => {
    setup();
    let id = '';
    act(() => {
      id = api.toast({ tone: 'progress', title: '업로드 중', progress: 40 });
    });
    advance(10_000);
    expect(screen.getByText('업로드 중')).toBeInTheDocument();

    act(() => api.update(id, { tone: 'success', title: '업로드 완료' }));
    advance(4999);
    expect(screen.getByText('업로드 완료')).toBeInTheDocument();
    advance(1);
    expect(screen.queryByText('업로드 완료')).not.toBeInTheDocument();
  });

  // 리뷰 #101 — 접기는 지운 게 아니라 숨긴 것이라 개수가 사실이어야 한다.
  it('접힘 개수가 실제로 숨은 개수와 같다', () => {
    setup();
    act(() => {
      ['1', '2', '3', '4', '5', '6', '7', '8'].forEach((n) =>
        api.toast({ tone: 'error', title: `${n}번`, dedupeKey: n }),
      );
    });

    expect(cards()).toHaveLength(3);
    expect(screen.getByText('이전 알림 5개 더')).toBeInTheDocument();
  });

  // 재리뷰 #101 — 스택이 밀려 정지를 붙들던 카드가 언마운트되면 정지가 풀린다.
  // 포인터가 여전히 스택 위에 있으면 움직임 한 번으로 다시 잡혀야 한다.
  it('스택이 밀려도 포인터가 움직이면 정지를 되찾는다', () => {
    setup();
    act(() => {
      ['a', 'b', 'c'].forEach((k) => api.toast({ tone: 'success', title: `${k}번`, dedupeKey: k }));
    });
    fireEvent.pointerOver(card(0));
    advance(10_000);
    expect(screen.getByText('a번')).toBeInTheDocument();

    // 4번째가 오면 a가 스택 밖으로 밀린다 — 여기서 정지가 한 번 풀린다
    act(() => {
      api.toast({ tone: 'success', title: 'd번', dedupeKey: 'd' });
    });
    // 포인터가 그대로 스택 위에 있다는 신호
    fireEvent.pointerMove(card(0));

    advance(10_000);
    expect(screen.getByText('d번')).toBeInTheDocument();
  });

  // 재리뷰 #101 — dedupe는 직전 토스트하고만 비교한다. 자동으로 안 닫히는 톤이
  // 번갈아 오면 합쳐지지 않고 쌓이는데, 접힘 개수가 그 사실을 그대로 말해야 한다.
  it('자동으로 안 닫히는 톤이 번갈아 오면 합쳐지지 않고 쌓인다', () => {
    setup();
    act(() => {
      for (let i = 0; i < 4; i++) {
        api.toast({ tone: 'error', title: `실패 ${i}` });
        api.toast({ tone: 'progress', title: `재시도 ${i}` });
      }
    });
    advance(60_000);

    expect(cards()).toHaveLength(3);
    expect(screen.getByText('이전 알림 5개 더')).toBeInTheDocument();
  });

  // 3차 리뷰 #101 — 토스트는 모달·드로어 위에 뜨는 표면이다. 그 레이어들이
  // 토스트 클릭을 "바깥 클릭"으로 읽고 닫히면 사용자의 입력이 날아간다.
  it('열린 모달 위에서 토스트를 눌러도 그 모달이 닫히지 않는다', async () => {
    // 오류 토스트는 자동으로 안 닫히니 가짜 타이머가 필요 없다 — userEvent는 실제
    // 타이머 위에서 쓴다.
    vi.useRealTimers();
    const user = userEvent.setup();
    const onDismiss = vi.fn();
    const onAction = vi.fn();
    setup(
      <DismissableLayer onDismiss={onDismiss}>
        <div>드로어 내용</div>
      </DismissableLayer>,
    );
    act(() => {
      api.toast({
        tone: 'error',
        title: '업로드에 실패했습니다',
        action: { label: '재인증', onClick: onAction },
      });
    });

    await user.click(screen.getByRole('button', { name: '재인증' }));
    expect(onAction).toHaveBeenCalledTimes(1);
    expect(onDismiss).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: '닫기' }));
    expect(screen.queryByText('업로드에 실패했습니다')).not.toBeInTheDocument();
    expect(onDismiss).not.toHaveBeenCalled();
  });

  // 3차 리뷰 #101 — 합치는 키를 저장해 두면 톤이 바뀌어도 옛 톤에 묶인다.
  it('update로 톤을 바꾸면 원래 톤의 다음 토스트가 따로 쌓인다', () => {
    setup();
    let id = '';
    act(() => {
      id = api.toast({ tone: 'progress', title: '업로드 중' });
    });
    act(() => api.update(id, { tone: 'success', title: '업로드를 마쳤습니다' }));

    act(() => {
      api.toast({ tone: 'progress', title: '다음 클립 업로드 중' });
    });

    expect(cards()).toHaveLength(2);
    expect(screen.getByText('업로드를 마쳤습니다')).toBeInTheDocument();
    expect(screen.getByText('다음 클립 업로드 중')).toBeInTheDocument();
  });

  it('접근성 위반이 없다', async () => {
    vi.useRealTimers();
    const { container } = setup();
    act(() => {
      api.toast({ tone: 'error', title: '업로드 실패', description: '재인증이 필요합니다' });
      api.toast({
        tone: 'progress',
        title: '업로드 중',
        progress: 62,
        dedupeKey: 'p',
        action: { label: '취소', onClick: vi.fn() },
      });
    });

    expect(await axe(container.parentElement as HTMLElement)).toHaveNoViolations();
  });
});
