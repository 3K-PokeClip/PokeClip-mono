import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PlayerSeekBar } from './PlayerSeekBar';
import { LIVE_WINDOW_SECONDS } from './playerMath';

// 드래그가 놓을 때 딱 한 번만 커밋되는지를 재는 테스트 (계약3 4절 2번 · POK-32).
// jsdom엔 레이아웃이 없어 rect가 전부 0이므로 트랙 폭만 국소 스텁한다 — 좌표 환산 자체는
// playerMath.seekFractionFromPointer에서 단위 테스트한다.
function stubTrackWidth(el: HTMLElement, left: number, width: number) {
  vi.spyOn(el, 'getBoundingClientRect').mockReturnValue({
    left,
    width,
    right: left + width,
    top: 0,
    bottom: 0,
    height: 0,
    x: left,
    y: 0,
    toJSON: () => ({}),
  } as DOMRect);
}

function renderSeekBar(overrides: Partial<Parameters<typeof PlayerSeekBar>[0]> = {}) {
  const props = {
    behindSeconds: 0,
    windowSeconds: LIVE_WINDOW_SECONDS,
    clipMarked: false,
    onSeekToFraction: vi.fn(),
    onSeekBy: vi.fn(),
    onReturnToLive: vi.fn(),
    onSeekingChange: vi.fn(),
    ...overrides,
  };
  render(<PlayerSeekBar {...props} />);
  const slider = screen.getByRole('slider', { name: '라이브 탐색' });
  stubTrackWidth(slider, 0, 1000);
  return { slider, props };
}

describe('PlayerSeekBar 드래그', () => {
  it('드래그 중엔 시킹하지 않고 놓을 때 한 번만 시킹한다', () => {
    const { slider, props } = renderSeekBar();

    fireEvent.pointerDown(slider, { pointerId: 1, button: 0, clientX: 500 });
    fireEvent.pointerMove(slider, { pointerId: 1, clientX: 400 });
    fireEvent.pointerMove(slider, { pointerId: 1, clientX: 250 });

    // 여기가 이 티켓의 핵심 — pointermove마다 currentTime을 쓰면 LL-HLS가 버퍼를 계속 비운다
    expect(props.onSeekToFraction).not.toHaveBeenCalled();
    // 프리뷰는 즉시 따라온다 (0.25 → 시차 2700초)
    expect(slider).toHaveAttribute('aria-valuenow', '-2700');

    fireEvent.pointerUp(slider, { pointerId: 1, clientX: 250 });
    expect(props.onSeekToFraction).toHaveBeenCalledTimes(1);
    expect(props.onSeekToFraction).toHaveBeenCalledWith(0.25);
  });

  it('이동 없는 클릭도 정확히 한 번 시킹된다', () => {
    const { slider, props } = renderSeekBar();

    fireEvent.pointerDown(slider, { pointerId: 1, button: 0, clientX: 750 });
    fireEvent.pointerUp(slider, { pointerId: 1, clientX: 750 });

    expect(props.onSeekToFraction).toHaveBeenCalledTimes(1);
    expect(props.onSeekToFraction).toHaveBeenCalledWith(0.75);
  });

  it('드래그 중 컨트롤 숨김 유보를 알린다', () => {
    const { slider, props } = renderSeekBar();

    fireEvent.pointerDown(slider, { pointerId: 1, button: 0, clientX: 500 });
    expect(props.onSeekingChange).toHaveBeenLastCalledWith(true);

    fireEvent.pointerUp(slider, { pointerId: 1, clientX: 500 });
    expect(props.onSeekingChange).toHaveBeenLastCalledWith(false);
  });

  it('pointercancel은 커밋 없이 프리뷰만 되돌린다', () => {
    const { slider, props } = renderSeekBar({ behindSeconds: 83 });

    fireEvent.pointerDown(slider, { pointerId: 1, button: 0, clientX: 200 });
    expect(slider).toHaveAttribute('aria-valuenow', '-2880');

    fireEvent.pointerCancel(slider, { pointerId: 1, clientX: 200 });
    expect(props.onSeekToFraction).not.toHaveBeenCalled();
    // 실제 재생 위치로 복귀한다
    expect(slider).toHaveAttribute('aria-valuenow', '-83');
  });

  it('다른 손가락의 move는 무시한다', () => {
    const { slider, props } = renderSeekBar();

    fireEvent.pointerDown(slider, { pointerId: 1, button: 0, clientX: 500 });
    fireEvent.pointerMove(slider, { pointerId: 2, clientX: 100 });
    expect(slider).toHaveAttribute('aria-valuenow', '-1800'); // 첫 손가락 위치 그대로

    fireEvent.pointerUp(slider, { pointerId: 1, clientX: 500 });
    expect(props.onSeekToFraction).toHaveBeenCalledWith(0.5);
  });

  it('드래그 중 닿은 두 번째 손가락에게 드래그를 넘기지 않는다', () => {
    const { slider, props } = renderSeekBar();

    fireEvent.pointerDown(slider, { pointerId: 1, button: 0, clientX: 700 });
    // 두 번째 손가락(손바닥 스침 등)이 시크바에 닿는다
    fireEvent.pointerDown(slider, { pointerId: 2, button: 0, clientX: 200 });
    expect(slider).toHaveAttribute('aria-valuenow', '-1080'); // 첫 손가락 위치 유지

    // 첫 손가락의 드래그가 계속 살아 있다
    fireEvent.pointerMove(slider, { pointerId: 1, clientX: 600 });
    expect(slider).toHaveAttribute('aria-valuenow', '-1440');

    fireEvent.pointerUp(slider, { pointerId: 1, clientX: 600 });
    expect(props.onSeekToFraction).toHaveBeenCalledTimes(1);
    expect(props.onSeekToFraction).toHaveBeenCalledWith(0.6);
    expect(props.onSeekingChange).toHaveBeenLastCalledWith(false);
  });

  it('마우스 우클릭은 드래그를 시작하지 않는다', () => {
    const { slider, props } = renderSeekBar();

    fireEvent.pointerDown(slider, { pointerId: 1, pointerType: 'mouse', button: 2, clientX: 250 });
    fireEvent.pointerUp(slider, { pointerId: 1, clientX: 250 });

    expect(props.onSeekToFraction).not.toHaveBeenCalled();
    expect(props.onSeekingChange).not.toHaveBeenCalled();
  });
});

describe('PlayerSeekBar 되감기 창', () => {
  it('창이 짧으면 슬라이더 범위도 그만큼이다 — 좌측 끝이 실제 도달 지점', () => {
    const { slider } = renderSeekBar({ windowSeconds: 600, behindSeconds: 120 });

    expect(slider).toHaveAttribute('aria-valuemin', '-600');
    expect(slider).toHaveAttribute('aria-valuenow', '-120');
  });

  it('짧은 창에서도 좌표를 그 창 기준으로 환산한다', () => {
    const { slider, props } = renderSeekBar({ windowSeconds: 600 });

    fireEvent.pointerDown(slider, { pointerId: 1, button: 0, clientX: 500 });
    expect(slider).toHaveAttribute('aria-valuenow', '-300'); // 창 600의 절반
    fireEvent.pointerUp(slider, { pointerId: 1, clientX: 500 });
    expect(props.onSeekToFraction).toHaveBeenCalledWith(0.5);
  });
});
