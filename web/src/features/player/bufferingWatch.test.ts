import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BUFFERING_DELAY_MS, watchBuffering } from './bufferingWatch';

// 끊겨 기다리는 동안 스피너를 띄울지 정한다(POK-439). 미디어 이벤트만 보는 순수 DOM 코드라 영상 노드 하나로 잰다.

function fakeVideo({ paused = false, ended = false, readyState = 2 } = {}) {
  const video = document.createElement('video');
  Object.defineProperty(video, 'paused', { value: paused, configurable: true });
  Object.defineProperty(video, 'ended', { value: ended, configurable: true });
  Object.defineProperty(video, 'readyState', { value: readyState, configurable: true });
  return video;
}

describe('watchBuffering', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it.each(['waiting', 'stalled'])('%s 뒤 400ms가 지나야 띄운다', (event) => {
    const video = fakeVideo();
    const onChange = vi.fn();
    watchBuffering(video, onChange);

    video.dispatchEvent(new Event(event));
    vi.advanceTimersByTime(BUFFERING_DELAY_MS - 1);
    expect(onChange).not.toHaveBeenCalled();

    vi.advanceTimersByTime(1);
    expect(onChange.mock.calls).toEqual([[true]]);
  });

  it('위치를 옮길 때 잠깐 튀는 waiting은 띄우지 않는다', () => {
    const video = fakeVideo();
    const onChange = vi.fn();
    watchBuffering(video, onChange);

    video.dispatchEvent(new Event('waiting'));
    vi.advanceTimersByTime(BUFFERING_DELAY_MS - 1);
    video.dispatchEvent(new Event('playing'));
    vi.advanceTimersByTime(BUFFERING_DELAY_MS);

    expect(onChange).not.toHaveBeenCalled();
  });

  it('이벤트가 이어져도 표시를 늦추지 않는다 — 처음 waiting부터 400ms다', () => {
    const video = fakeVideo();
    const onChange = vi.fn();
    watchBuffering(video, onChange);

    video.dispatchEvent(new Event('waiting'));
    vi.advanceTimersByTime(300);
    video.dispatchEvent(new Event('stalled'));
    vi.advanceTimersByTime(100);

    expect(onChange.mock.calls).toEqual([[true]]);
  });

  it.each(['playing', 'canplay', 'pause', 'ended', 'emptied', 'error'])(
    '%s가 오면 바로 내린다',
    (event) => {
      const video = fakeVideo();
      const onChange = vi.fn();
      watchBuffering(video, onChange);
      video.dispatchEvent(new Event('waiting'));
      vi.advanceTimersByTime(BUFFERING_DELAY_MS);
      onChange.mockClear();

      video.dispatchEvent(new Event(event));

      expect(onChange.mock.calls).toEqual([[false]]);
    },
  );

  it('loadeddata는 표시를 건드리지 않는다 — 첫 프레임만 왔을 뿐 재생은 아직 멎어 있다', () => {
    const video = fakeVideo();
    const onChange = vi.fn();
    watchBuffering(video, onChange);

    video.dispatchEvent(new Event('waiting'));
    video.dispatchEvent(new Event('loadeddata'));
    vi.advanceTimersByTime(BUFFERING_DELAY_MS);
    video.dispatchEvent(new Event('loadeddata'));

    expect(onChange.mock.calls).toEqual([[true]]);
  });

  it('일시정지 중엔 띄우지 않는다 — 자동재생을 거절당하면 멈춘 채로 있다', () => {
    const video = fakeVideo({ paused: true });
    const onChange = vi.fn();
    watchBuffering(video, onChange);

    video.dispatchEvent(new Event('waiting'));
    vi.advanceTimersByTime(BUFFERING_DELAY_MS);

    expect(onChange).not.toHaveBeenCalled();
  });

  it('다 본 영상엔 띄우지 않는다', () => {
    const video = fakeVideo({ ended: true });
    const onChange = vi.fn();
    watchBuffering(video, onChange);

    video.dispatchEvent(new Event('waiting'));
    vi.advanceTimersByTime(BUFFERING_DELAY_MS);

    expect(onChange).not.toHaveBeenCalled();
  });

  it('받아 둔 영상으로 재생이 이어지면 stalled여도 띄우지 않는다 — 띄우면 내릴 이벤트가 안 와 붙박인다', () => {
    const video = fakeVideo({ readyState: 3 });
    const onChange = vi.fn();
    watchBuffering(video, onChange);

    video.dispatchEvent(new Event('stalled'));
    vi.advanceTimersByTime(BUFFERING_DELAY_MS);

    expect(onChange).not.toHaveBeenCalled();
  });

  it('해제하면 걸어 둔 타이머도 듣기도 끝난다 — 화면을 떠난 뒤에 알리지 않는다', () => {
    const video = fakeVideo();
    const onChange = vi.fn();
    const stop = watchBuffering(video, onChange);

    video.dispatchEvent(new Event('waiting'));
    stop();
    vi.advanceTimersByTime(BUFFERING_DELAY_MS);
    video.dispatchEvent(new Event('waiting'));
    vi.advanceTimersByTime(BUFFERING_DELAY_MS);

    expect(onChange).not.toHaveBeenCalled();
  });
});
