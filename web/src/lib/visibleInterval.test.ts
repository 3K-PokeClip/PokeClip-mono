import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setVisibleInterval } from './visibleInterval';

function setVisibility(state: DocumentVisibilityState) {
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: state });
  document.dispatchEvent(new Event('visibilitychange'));
}

describe('setVisibleInterval', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    setVisibility('visible');
    vi.useRealTimers();
  });

  it('보이는 동안은 주기마다 돈다', () => {
    const run = vi.fn();
    const stop = setVisibleInterval(run, 1000);

    vi.advanceTimersByTime(3000);

    expect(run).toHaveBeenCalledTimes(3);
    stop();
  });

  it('숨으면 멈춘다', () => {
    const run = vi.fn();
    const stop = setVisibleInterval(run, 1000);

    setVisibility('hidden');
    vi.advanceTimersByTime(60_000);

    expect(run).not.toHaveBeenCalled();
    stop();
  });

  it('숨은 사이 한 주기가 지났으면 돌아오자마자 한 번 돌고 이어 간다', () => {
    const run = vi.fn();
    const stop = setVisibleInterval(run, 1000);

    setVisibility('hidden');
    vi.advanceTimersByTime(5000);
    setVisibility('visible');
    expect(run).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(1000);
    expect(run).toHaveBeenCalledTimes(2);
    stop();
  });

  it('잠깐 오간 것은 바로 돌지 않는다: 탭을 오갈 때마다 요청이 나가면 안 된다', () => {
    const run = vi.fn();
    const stop = setVisibleInterval(run, 1000);

    setVisibility('hidden');
    vi.advanceTimersByTime(300);
    setVisibility('visible');

    expect(run).not.toHaveBeenCalled();
    stop();
  });

  it('숨은 채 시작하면 보일 때까지 돌지 않는다', () => {
    setVisibility('hidden');
    const run = vi.fn();
    const stop = setVisibleInterval(run, 1000);

    vi.advanceTimersByTime(5000);
    expect(run).not.toHaveBeenCalled();

    setVisibility('visible');
    expect(run).toHaveBeenCalledTimes(1);
    stop();
  });

  it('멈춘 뒤에는 탭이 돌아와도 돌지 않는다', () => {
    const run = vi.fn();
    const stop = setVisibleInterval(run, 1000);
    stop();

    setVisibility('hidden');
    vi.advanceTimersByTime(5000);
    setVisibility('visible');
    vi.advanceTimersByTime(5000);

    expect(run).not.toHaveBeenCalled();
  });
});
