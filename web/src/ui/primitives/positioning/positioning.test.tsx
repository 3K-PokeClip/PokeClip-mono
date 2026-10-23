import { useRef } from 'react';
import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useComposedRefs } from '../hooks/useComposedRefs';
import { useFloating, useMeasureOnAttach } from './positioning';

// jsdom은 배치도 ResizeObserver도 없다: 트리거 위치·콘텐츠 크기를 주고, 관찰자는 손으로 울린다
const observers: Array<{ fire: () => void; disconnect: ReturnType<typeof vi.fn> }> = [];

class FakeResizeObserver {
  disconnect = vi.fn();
  constructor(callback: ResizeObserverCallback) {
    observers.push({ fire: () => callback([], this), disconnect: this.disconnect });
  }
  observe() {}
  unobserve() {}
}

let contentHeight = 100;

beforeEach(() => {
  observers.length = 0;
  contentHeight = 100;
  vi.stubGlobal('ResizeObserver', FakeResizeObserver);
  vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(296);
  vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockImplementation(() => contentHeight);
  // 화면 아래쪽 프로필 블록 같은 트리거(jsdom 창 높이 768)
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
    top: 700,
    bottom: 740,
    left: 20,
    right: 200,
    width: 180,
    height: 40,
    x: 20,
    y: 700,
    toJSON: () => ({}),
  });
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function Floating() {
  const triggerRef = useRef<HTMLButtonElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const { coords, update } = useFloating(triggerRef, contentRef, {
    side: 'top',
    align: 'start',
    offset: 10,
    open: true,
  });
  const measureRef = useMeasureOnAttach(update);
  const ref = useComposedRefs<HTMLDivElement>(contentRef, measureRef);
  return (
    <>
      <button ref={triggerRef} type="button">
        트리거
      </button>
      <div ref={ref} data-testid="content" style={{ top: coords.top }} />
    </>
  );
}

describe('useMeasureOnAttach: 붙은 뒤 크기가 바뀌면 다시 잰다', () => {
  it('위로 여는 팝업이 열린 뒤 길어지면 위 끝을 올려 트리거 위에 머문다 — 아래로 자라 덮지 않는다', () => {
    render(<Floating />);
    const content = screen.getByTestId('content');
    expect(content).toHaveStyle({ top: '590px' }); // 700 - 100 - 10

    contentHeight = 300; // 받은 초대가 늦게 도착해 길어졌다
    act(() => observers.forEach((o) => o.fire()));

    expect(content).toHaveStyle({ top: '390px' }); // 700 - 300 - 10
  });

  it('떨어지면 관찰을 멈춘다', () => {
    const { unmount } = render(<Floating />);
    expect(observers).toHaveLength(1);

    unmount();

    expect(observers[0]!.disconnect).toHaveBeenCalled();
  });
});
