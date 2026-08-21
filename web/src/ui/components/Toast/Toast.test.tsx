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

/** 보이는 카드만. 접힌 카드는 마운트는 유지하되 화면에서 빠진다. */
function cards() {
  return Array.from(document.querySelectorAll<HTMLElement>('[data-tone]:not([data-collapsed])'));
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
    // 접힌 카드는 마운트는 유지하되(재마운트 = 바 되감김·경고 재낭독) 화면에선 빠진다
    expect(screen.getByText('1번').closest('[data-tone]')).toHaveAttribute('data-collapsed');
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

    await user.click(screen.getByRole('button', { name: '닫기' }));
    expect(screen.queryByText('업로드에 실패했습니다')).not.toBeInTheDocument();
    expect(onDismiss).not.toHaveBeenCalled();

    act(() => {
      api.toast({
        tone: 'error',
        title: '다시 실패했습니다',
        action: { label: '재인증', onClick: onAction },
      });
    });
    await user.click(screen.getByRole('button', { name: '재인증' }));
    expect(onAction).toHaveBeenCalledTimes(1);
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

  // 4차 리뷰 #101 — 진행 토스트는 저마다 다른 작업이다. 톤으로 합치면 동시에
  // 도는 업로드 둘이 한 카드와 한 id를 공유해 서로의 진행률을 덮어쓴다.
  it('동시에 도는 진행 토스트 둘은 합쳐지지 않고 각자 id를 받는다', () => {
    setup();
    let a = '';
    let b = '';
    act(() => {
      a = api.toast({ tone: 'progress', title: '클립 A 업로드 중', progress: 0 });
      b = api.toast({ tone: 'progress', title: '클립 B 업로드 중', progress: 0 });
    });

    expect(a).not.toBe(b);
    expect(cards()).toHaveLength(2);

    act(() => api.update(a, { progress: 50 }));
    expect(screen.getByText('클립 A 업로드 중')).toBeInTheDocument();
    expect(screen.getByText('클립 B 업로드 중')).toBeInTheDocument();
  });

  it('dedupeKey를 밝히면 진행 토스트도 합쳐진다', () => {
    setup();
    act(() => {
      api.toast({ tone: 'progress', title: '첫 갱신', dedupeKey: 'upload-1' });
      api.toast({ tone: 'progress', title: '두 번째 갱신', dedupeKey: 'upload-1' });
    });

    expect(cards()).toHaveLength(1);
    expect(screen.getByText('두 번째 갱신')).toBeInTheDocument();
  });

  // 4차 리뷰 #101 — 끝난 일에 「취소」가 남으면 안 된다.
  it('update로 액션을 뗄 수 있다', () => {
    setup();
    let id = '';
    act(() => {
      id = api.toast({
        tone: 'progress',
        title: '업로드 중',
        action: { label: '취소', onClick: vi.fn() },
      });
    });
    expect(screen.getByRole('button', { name: '취소' })).toBeInTheDocument();

    act(() => api.update(id, { tone: 'success', title: '업로드를 마쳤습니다', action: null }));
    expect(screen.queryByRole('button', { name: '취소' })).not.toBeInTheDocument();
    expect(screen.getByText('업로드를 마쳤습니다')).toBeInTheDocument();
  });

  // 4차 리뷰 #101 — 진행 토스트는 갱신마다 카드 전체가 다시 읽히면 안 된다.
  it('진행 토스트만 aria-atomic이 꺼진다', () => {
    setup();
    act(() => {
      api.toast({ tone: 'progress', title: '업로드 중', dedupeKey: 'p' });
      api.toast({ tone: 'success', title: '발행 완료', dedupeKey: 's' });
    });

    expect(screen.getByText('업로드 중').closest('[data-tone]')).toHaveAttribute(
      'aria-atomic',
      'false',
    );
    expect(screen.getByText('발행 완료').closest('[data-tone]')).toHaveAttribute(
      'aria-atomic',
      'true',
    );
  });

  // 4차 리뷰 #101 — 포커스를 들고 있던 카드를 닫으면 포커스가 body로 떨어져
  // 다음 Tab이 문서 처음부터 시작한다. 남은 카드로 넘겨 자리를 지킨다.
  it('포커스한 토스트를 닫으면 남은 토스트로 포커스가 넘어간다', () => {
    setup();
    act(() => {
      api.toast({ tone: 'error', title: '먼저', dedupeKey: 'a' });
      api.toast({ tone: 'error', title: '나중', dedupeKey: 'b' });
    });

    const closers = screen.getAllByRole('button', { name: '닫기' });
    const last = closers[closers.length - 1]!;
    act(() => last.focus());
    act(() => last.click());

    expect(screen.queryByText('나중')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '닫기' }));
  });

  // 5차 리뷰 #101 — 4차의 elementFromPoint 되짚기가 낡은 좌표로 새 토스트를
  // 유령 호버로 잡아 전역 정지를 고착시켰다. 되짚기를 걷어냈으니 다시 나면 안 된다.
  it('마우스로 닫은 뒤 뜬 새 토스트는 유령 호버에 걸리지 않는다', () => {
    // jsdom에는 elementFromPoint가 없어 그 분기가 아예 안 돌았다 — 브라우저처럼 심는다
    (document as unknown as { elementFromPoint: unknown }).elementFromPoint = () =>
      document.querySelector('[data-toast-id]');
    try {
      setup();
      act(() => {
        api.toast({ tone: 'success', title: '첫 토스트', dedupeKey: 'a' });
      });
      fireEvent.pointerOver(card(0), { clientX: 100, clientY: 200 });
      fireEvent.click(screen.getByRole('button', { name: '닫기' }));

      act(() => {
        api.toast({ tone: 'success', title: '두 번째 토스트', dedupeKey: 'b' });
      });
      advance(5000);
      expect(screen.queryByText('두 번째 토스트')).not.toBeInTheDocument();
    } finally {
      delete (document as unknown as { elementFromPoint?: unknown }).elementFromPoint;
    }
  });

  // 5차 리뷰 #101 — undefined는 "안 준 것"이다. 스프레드가 덮어써 톤을 지우면
  // 카드가 역할·아이콘·자동 닫힘을 통째로 잃는다.
  it('update에 undefined를 줘도 톤이 지워지지 않는다', () => {
    setup();
    let id = '';
    act(() => {
      id = api.toast({ tone: 'success', title: '발행 완료' });
    });
    act(() => api.update(id, { tone: undefined, title: '갱신됨' }));

    const el = screen.getByText('갱신됨').closest('[data-tone]');
    expect(el).toHaveAttribute('data-tone', 'success');
    expect(el).toHaveAttribute('role', 'status');
  });

  it('progress: null로 진행 바를 뗀다', () => {
    setup();
    let id = '';
    act(() => {
      id = api.toast({ tone: 'progress', title: '업로드 중', progress: 40 });
    });
    expect(screen.getByRole('progressbar')).toBeInTheDocument();

    act(() => api.update(id, { tone: 'success', title: '마쳤습니다', progress: null }));
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
  });

  // 5차 리뷰 #101 — 접혔다 돌아온 토스트를 재마운트하면 바가 되감기고 경고가 다시 읽힌다.
  it('접혔다 다시 보여도 같은 DOM 노드를 유지한다', () => {
    setup();
    act(() => {
      ['a', 'b', 'c'].forEach((k) => api.toast({ tone: 'error', title: `${k}번`, dedupeKey: k }));
    });
    const nodeA = screen.getByText('a번').closest('[data-tone]');

    act(() => {
      api.toast({ tone: 'error', title: 'd번', dedupeKey: 'd' });
    });
    expect(nodeA).toHaveAttribute('data-collapsed');

    act(() => api.dismiss(card(cards().length - 1).getAttribute('data-toast-id')!));
    expect(screen.getByText('a번').closest('[data-tone]')).toBe(nodeA);
  });

  // 5차 리뷰 #101 — 마지막 하나를 닫으면 넘겨줄 카드가 없어 포커스가 body로 떨어졌다.
  it('마지막 토스트를 닫으면 들어오기 전 자리로 포커스가 돌아간다', () => {
    const { getByText } = setup(<button type="button">바깥 버튼</button>);
    const outside = getByText('바깥 버튼');
    act(() => outside.focus());

    act(() => {
      api.toast({ tone: 'error', title: '유일한 토스트' });
    });
    const close = screen.getByRole('button', { name: '닫기' });
    act(() => close.focus());
    act(() => close.click());

    expect(document.activeElement).toBe(outside);
  });

  // 5차 리뷰 #101 — 액션을 눌러도 안 닫히면 같은 동작을 두 번 실행할 수 있다.
  it('액션을 누르면 토스트가 닫힌다', () => {
    const undo = vi.fn();
    setup();
    act(() => {
      api.toast({ tone: 'success', title: '기본 편집자로 변경했어요', undo });
    });

    fireEvent.click(screen.getByRole('button', { name: '되돌리기' }));
    expect(undo).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('기본 편집자로 변경했어요')).not.toBeInTheDocument();
  });

  it('보조설명 전문을 title로 볼 수 있다', () => {
    setup();
    const long = '1분에 3회까지만 발급할 수 있어요. 잠시 후 다시 시도해 주세요.';
    act(() => {
      api.toast({ tone: 'error', title: '코드 발급이 잠시 제한됐어요', description: long });
    });

    expect(screen.getByText(long)).toHaveAttribute('title', long);
  });

  // 6차 리뷰 #101 — 카드는 그대로인데 안쪽 버튼만 사라지면 blur가 안 온다.
  // 카드 id만 보면 정지가 영영 안 풀려 이후 토스트까지 전부 멈춘다.
  it('포커스한 액션이 update로 사라져도 정지가 풀린다', () => {
    setup();
    let id = '';
    act(() => {
      id = api.toast({
        tone: 'progress',
        title: '업로드 중',
        action: { label: '취소', onClick: vi.fn() },
      });
    });
    act(() => screen.getByRole('button', { name: '취소' }).focus());

    act(() => api.update(id, { tone: 'success', title: '마쳤습니다', action: null }));

    // 포커스는 같은 카드의 닫기로 옮겨간다 — 스택 안이니 정지는 규칙대로 유지된다
    const close = screen.getByRole('button', { name: '닫기' });
    expect(document.activeElement).toBe(close);
    advance(10_000);
    expect(screen.getByText('마쳤습니다')).toBeInTheDocument();

    // 사용자가 스택을 벗어나면 남은 시간부터 이어서 닫힌다.
    // 고치기 전에는 포커스가 허공(body)에 있는데도 정지가 걸린 채라 영영 안 닫혔다.
    act(() => close.blur());
    advance(5000);
    expect(screen.queryByText('마쳤습니다')).not.toBeInTheDocument();
  });

  // 6차 리뷰 #101 — 톤만으로 합치면 내용이 다른 오류가 앞 오류를 통째로 덮어쓴다.
  it('내용이 다른 오류는 합쳐지지 않는다', () => {
    setup();
    act(() => {
      api.toast({
        tone: 'error',
        title: '코드 발급이 잠시 제한됐어요',
        description: '1분에 3회까지만 발급할 수 있어요.',
      });
      api.toast({ tone: 'error', title: '코드 발급에 실패했어요' });
    });

    expect(cards()).toHaveLength(2);
    expect(screen.getByText('코드 발급이 잠시 제한됐어요')).toBeInTheDocument();
  });

  it('같은 제목이 반복되면 여전히 하나로 합쳐진다', () => {
    setup();
    act(() => {
      api.toast({ tone: 'error', title: '업로드에 실패했습니다', description: '1번째' });
      api.toast({ tone: 'error', title: '업로드에 실패했습니다', description: '2번째' });
    });

    expect(cards()).toHaveLength(1);
    expect(screen.getByText('2번째')).toBeInTheDocument();
  });

  // 6차 리뷰 #101 — dismissAll도 포커스를 돌려줘야 한다.
  it('dismissAll도 들어오기 전 자리로 포커스를 돌려준다', () => {
    const { getByText } = setup(<button type="button">바깥 버튼</button>);
    const outside = getByText('바깥 버튼');
    act(() => outside.focus());
    act(() => {
      api.toast({ tone: 'error', title: '토스트' });
    });
    act(() => screen.getByRole('button', { name: '닫기' }).focus());

    act(() => api.dismissAll());
    expect(document.activeElement).toBe(outside);
  });

  // 6차 리뷰 #101 — 접힌 카드는 애니메이션이 끊기지 않게 숨겨야 한다.
  it('접힌 카드는 display가 아니라 visibility로 숨는다', () => {
    setup();
    act(() => {
      ['a', 'b', 'c', 'd'].forEach((k) =>
        api.toast({ tone: 'error', title: `${k}번`, dedupeKey: k }),
      );
    });

    const collapsed = screen.getByText('a번').closest('[data-tone]') as HTMLElement;
    expect(collapsed).toHaveAttribute('data-collapsed');
    const style = getComputedStyle(collapsed);
    expect(style.visibility).toBe('hidden');
    expect(style.display).not.toBe('none');
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
