import { act, fireEvent, render, screen } from '@testing-library/react';
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
