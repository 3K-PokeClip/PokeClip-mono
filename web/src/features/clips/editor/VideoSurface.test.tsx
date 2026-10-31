import { render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CropResult } from './CropResult';
import { VideoSurface } from './VideoSurface';

// jsdom에는 캔버스도 레이아웃도 영상 프레임도 없다: 그리기 호출을 세어 「언제 그리는가」를 잰다.

type Size = { width: number; height: number };

/** 칸 크기를 손으로 알리는 ResizeObserver */
function stubResizeObserver() {
  const observers: { callback: ResizeObserverCallback; disconnect: () => void }[] = [];
  class FakeResizeObserver {
    disconnect = vi.fn();
    constructor(callback: ResizeObserverCallback) {
      observers.push({ callback, disconnect: this.disconnect });
    }
    observe = vi.fn();
    unobserve = vi.fn();
  }
  vi.stubGlobal('ResizeObserver', FakeResizeObserver);
  return {
    observers,
    resize: ({ width, height }: Size) => {
      for (const { callback } of observers) {
        callback([{ contentRect: { width, height } } as ResizeObserverEntry], {} as ResizeObserver);
      }
    },
  };
}

function stubFrames() {
  let frames: FrameRequestCallback[] = [];
  const raf = vi.fn((cb: FrameRequestCallback) => frames.push(cb));
  vi.stubGlobal('requestAnimationFrame', raf);
  vi.stubGlobal('cancelAnimationFrame', () => {
    frames = [];
  });
  return {
    raf,
    frame: () => {
      const due = frames;
      frames = [];
      for (const cb of due) cb(0);
    },
  };
}

/** 첫 장면까지 받은 영상 노드. 있으면 requestVideoFrameCallback을 손으로 돌린다 */
function videoNode({ paused = true, rvfc = true } = {}) {
  const node = document.createElement('video');
  let isPaused = paused;
  Object.defineProperties(node, {
    readyState: { configurable: true, value: 4 },
    videoWidth: { configurable: true, value: 1920 },
    videoHeight: { configurable: true, value: 1080 },
    paused: { configurable: true, get: () => isPaused },
  });
  let pending: VideoFrameRequestCallback[] = [];
  const request = vi.fn((cb: VideoFrameRequestCallback) => pending.push(cb));
  const cancel = vi.fn(() => {
    pending = [];
  });
  if (rvfc) {
    Object.assign(node, {
      requestVideoFrameCallback: request,
      cancelVideoFrameCallback: cancel,
    });
  }
  return {
    node,
    request,
    cancel,
    setPaused: (value: boolean) => {
      isPaused = value;
      node.dispatchEvent(new Event(value ? 'pause' : 'play'));
    },
    videoFrame: () => {
      const due = pending;
      pending = [];
      for (const cb of due) cb(0, {} as VideoFrameCallbackMetadata);
    },
  };
}

describe('VideoSurface', () => {
  const drawImage = vi.fn();

  beforeEach(() => {
    drawImage.mockClear();
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
      drawImage,
    } as unknown as CanvasRenderingContext2D);
  });
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('멈춘 영상은 프레임을 돌리지 않는다: 첫 크기 알림에 한 번 그리고 끝이다', () => {
    const { resize } = stubResizeObserver();
    const { raf, frame } = stubFrames();
    const { node, request } = videoNode();
    render(<VideoSurface video={node} />);

    resize({ width: 320, height: 180 });
    frame();
    frame();

    expect(drawImage).toHaveBeenCalledOnce();
    expect(raf).not.toHaveBeenCalled();
    expect(request).not.toHaveBeenCalled();
  });

  it('크기는 ResizeObserver로 받는다: 그릴 때 clientWidth를 읽으면 프레임마다 레이아웃을 강제한다', () => {
    const { resize } = stubResizeObserver();
    const { node, videoFrame, setPaused } = videoNode();
    const clientWidth = vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get');
    const { container } = render(<VideoSurface video={node} />);

    resize({ width: 320, height: 180 });
    setPaused(false);
    videoFrame();
    videoFrame();

    expect(clientWidth).not.toHaveBeenCalled();
    const canvas = container.querySelector('canvas')!;
    expect(canvas.width).toBe(320);
    expect(canvas.height).toBe(180);
    expect(drawImage.mock.lastCall!.slice(5)).toEqual([0, 0, 320, 180]);
  });

  it('멈춘 동안 옮기기 끝 · 첫 장면 · 크기 변경마다 한 번씩만 그린다', () => {
    const { resize } = stubResizeObserver();
    const { node } = videoNode();
    render(<VideoSurface video={node} />);
    resize({ width: 320, height: 180 });
    drawImage.mockClear();

    node.dispatchEvent(new Event('seeked'));
    expect(drawImage).toHaveBeenCalledTimes(1);

    node.dispatchEvent(new Event('loadeddata'));
    expect(drawImage).toHaveBeenCalledTimes(2);

    // 캔버스 크기를 바꾸면 그림이 지워지므로 멈춰 있어도 다시 그린다
    resize({ width: 640, height: 360 });
    expect(drawImage).toHaveBeenCalledTimes(3);
  });

  it('멈춘 동안 크롭을 바꾸면 새 자리로 한 번 그린다', () => {
    const { resize } = stubResizeObserver();
    const { node } = videoNode();
    const { rerender } = render(<VideoSurface video={node} crop={{ x: 0, y: 0, w: 1, h: 1 }} />);
    resize({ width: 320, height: 180 });
    drawImage.mockClear();

    rerender(<VideoSurface video={node} crop={{ x: 0.5, y: 0, w: 0.25, h: 1 }} />);

    expect(drawImage).toHaveBeenCalledOnce();
    expect(drawImage.mock.lastCall!.slice(1, 5)).toEqual([960, 0, 480, 1080]);
  });

  it('크롭 값이 그대로면 다시 그리지 않는다: 허브가 새 객체를 줘도 그림은 같다', () => {
    const { resize } = stubResizeObserver();
    const { node } = videoNode();
    const { rerender } = render(<VideoSurface video={node} crop={{ x: 0, y: 0, w: 1, h: 1 }} />);
    resize({ width: 320, height: 180 });
    drawImage.mockClear();

    rerender(<VideoSurface video={node} crop={{ x: 0, y: 0, w: 1, h: 1 }} />);

    expect(drawImage).not.toHaveBeenCalled();
  });

  it('재생 중에는 영상 프레임마다 그리고, 멈추면 마지막 프레임을 그린 뒤 콜백을 끊는다', () => {
    const { resize } = stubResizeObserver();
    const { node, request, cancel, setPaused, videoFrame } = videoNode();
    render(<VideoSurface video={node} />);
    resize({ width: 320, height: 180 });
    drawImage.mockClear();

    setPaused(false);
    expect(request).toHaveBeenCalledOnce();

    videoFrame();
    videoFrame();
    expect(drawImage).toHaveBeenCalledTimes(2);

    setPaused(true);
    expect(cancel).toHaveBeenCalledOnce();
    expect(drawImage).toHaveBeenCalledTimes(3);

    videoFrame();
    expect(drawImage).toHaveBeenCalledTimes(3);
  });

  it('영상이 끝나도 프레임을 멈춘다', () => {
    const { resize } = stubResizeObserver();
    const { node, cancel, setPaused } = videoNode();
    render(<VideoSurface video={node} />);
    resize({ width: 320, height: 180 });
    setPaused(false);

    node.dispatchEvent(new Event('ended'));

    expect(cancel).toHaveBeenCalledOnce();
  });

  it('재생 중에 새로 붙은 칸은 play 이벤트 없이도 프레임을 돈다: 도구를 바꾸면 원본 판이 그렇게 붙는다', () => {
    const { resize } = stubResizeObserver();
    const { node, request, videoFrame } = videoNode({ paused: false });
    render(<VideoSurface video={node} />);
    resize({ width: 320, height: 180 });
    drawImage.mockClear();

    expect(request).toHaveBeenCalledOnce();
    videoFrame();
    expect(drawImage).toHaveBeenCalledOnce();
  });

  it('requestVideoFrameCallback이 없으면 재생 중에만 rAF로 그린다', () => {
    const { resize } = stubResizeObserver();
    const { raf, frame } = stubFrames();
    const { node, setPaused } = videoNode({ rvfc: false });
    render(<VideoSurface video={node} />);
    resize({ width: 320, height: 180 });
    drawImage.mockClear();
    expect(raf).not.toHaveBeenCalled();

    setPaused(false);
    frame();
    frame();
    expect(drawImage).toHaveBeenCalledTimes(2);

    setPaused(true);
    drawImage.mockClear();
    frame();
    expect(drawImage).not.toHaveBeenCalled();
  });

  it('ResizeObserver가 없으면 그릴 때 잰다: 첫 그림도 알림을 기다리지 않는다', () => {
    vi.stubGlobal('ResizeObserver', undefined);
    const { node } = videoNode();
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(200);
    vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(100);

    const { container } = render(<VideoSurface video={node} />);

    expect(drawImage).toHaveBeenCalledOnce();
    expect(container.querySelector('canvas')!.width).toBe(200);
  });

  it('첫 장면 전에는 그리지 않는다', () => {
    const { resize } = stubResizeObserver();
    const { node } = videoNode();
    Object.defineProperty(node, 'readyState', { configurable: true, value: 1 });
    render(<VideoSurface video={node} />);

    resize({ width: 320, height: 180 });

    expect(drawImage).not.toHaveBeenCalled();
  });

  it('내려가면 듣기와 프레임을 모두 끊는다', () => {
    const { resize, observers } = stubResizeObserver();
    const { node, cancel, setPaused } = videoNode();
    const { unmount } = render(<VideoSurface video={node} />);
    resize({ width: 320, height: 180 });
    setPaused(false);
    drawImage.mockClear();

    unmount();
    node.dispatchEvent(new Event('seeked'));

    expect(cancel).toHaveBeenCalledOnce();
    expect(observers[0]!.disconnect).toHaveBeenCalledOnce();
    expect(drawImage).not.toHaveBeenCalled();
  });

  it('중앙 모드의 흐린 바탕도 같은 길이다: 멈춘 동안 프레임을 돌리지 않는다', () => {
    const { resize } = stubResizeObserver();
    const { raf, frame } = stubFrames();
    const { node, request } = videoNode();
    const { container } = render(
      <CropResult
        label="결과"
        video={node}
        crop={{ x: 0, y: 0, w: 1, h: 1 }}
        placement={{ kind: 'contain', aspect: 16 / 9, fill: { kind: 'blur', strength: 50 } }}
      />,
    );
    // 흐린 바탕 + 가운데 영상
    expect(container.querySelectorAll('canvas')).toHaveLength(2);

    resize({ width: 320, height: 180 });
    frame();

    expect(drawImage).toHaveBeenCalledTimes(2);
    expect(raf).not.toHaveBeenCalled();
    expect(request).not.toHaveBeenCalled();
  });
});
