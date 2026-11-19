import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PlayerSeekBar } from './PlayerSeekBar';
import { REWIND_UI_MAX_SECONDS, isAtEdge } from './playerMath';

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
    // 재생기가 원시 시차로 정하는 값 — 시험이 따로 주지 않으면 보이는 시차와 같은 판정이다
    atEdge: isAtEdge(overrides.behindSeconds ?? 0),
    windowSeconds: REWIND_UI_MAX_SECONDS,
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

describe('PlayerSeekBar 다시보기(POK-518)', () => {
  it('End는 라이브 복귀가 아니라 오른쪽 끝으로 간다', () => {
    const onSeekToFraction = vi.fn();
    const onReturnToLive = vi.fn();
    render(
      <PlayerSeekBar
        behindSeconds={60}
        atEdge={false}
        windowSeconds={600}
        clipMarked={false}
        onSeekToFraction={onSeekToFraction}
        onSeekBy={vi.fn()}
        onReturnToLive={onReturnToLive}
        onSeekingChange={vi.fn()}
        replay
      />,
    );

    fireEvent.keyDown(screen.getByRole('slider', { name: '다시보기 탐색' }), { key: 'End' });

    expect(onSeekToFraction).toHaveBeenCalledWith(1);
    expect(onReturnToLive).not.toHaveBeenCalled();
  });
});

describe('PlayerSeekBar 방송이 끝난 되감기(recordingEnded)의 끝 문구', () => {
  it('끝에서는 「실시간」 이 아니라 「녹화 끝」 이다', () => {
    const { slider } = renderSeekBar({ recordingEnded: true });
    expect(slider).toHaveAttribute('aria-valuetext', '녹화 끝');
  });

  it('끝에서 떨어지면 「녹화 끝에서 −MM:SS」 다', () => {
    const { slider } = renderSeekBar({ behindSeconds: 60, recordingEnded: true });
    expect(slider).toHaveAttribute('aria-valuetext', '녹화 끝에서 -01:00');
  });

  it('이름 · End 키는 그대로 라이브 탐색 · 실시간으로다', () => {
    const { slider, props } = renderSeekBar({ behindSeconds: 60, recordingEnded: true });
    fireEvent.keyDown(slider, { key: 'End' });
    expect(props.onReturnToLive).toHaveBeenCalledTimes(1);
    expect(props.onSeekToFraction).not.toHaveBeenCalled();
  });

  it('방송 중(recordingEnded 없음)은 끝에서 「실시간」 그대로다', () => {
    const { slider } = renderSeekBar();
    expect(slider).toHaveAttribute('aria-valuetext', '실시간');
  });
});

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

  it('창을 넘어선 시차는 값과 표기가 함께 좌측 끝으로 잡힌다', () => {
    // 회귀: 시차는 계약 상한(1시간)으로만 잘려서 보존이 그보다 짧은 스트림에선 창을 넘어선다.
    // valuenow에만 클램프를 걸면 스크린리더가 "-10:00" 지점의 썸을 두고 "-33:20"을 읽는다.
    const { slider } = renderSeekBar({ windowSeconds: 600, behindSeconds: 2000 });

    expect(slider).toHaveAttribute('aria-valuenow', '-600');
    expect(slider).toHaveAttribute('aria-valuetext', '실시간에서 -10:00');
  });
});

/** 손잡이(썸)의 가로 자리 — 트랙 안에서 left 를 가진 요소는 손잡이 하나다(클립 마커는 끈 채로 잰다) */
function thumbLeft(slider: HTMLElement): string | undefined {
  return Array.from(slider.querySelectorAll<HTMLElement>('div')).find((el) => el.style.left !== '')
    ?.style.left;
}

/** 손잡이 left 를 % 숫자로 — 단위가 % 가 아니면(예: "100px") 숫자로 읽기 전에 실패한다 */
function thumbPct(slider: HTMLElement): number {
  const left = thumbLeft(slider) ?? '';
  expect(left).toMatch(/%$/);
  const pct = Number(left.slice(0, -1));
  expect(Number.isFinite(pct)).toBe(true);
  return pct;
}

describe('PlayerSeekBar 끝 판정 — 재생기의 atEdge 하나를 손잡이 · 낭독이 함께 쓴다(POK-583 떨림 ③)', () => {
  it('시차 2초 · 창 28초에서 끝이면 손잡이는 오른쪽 끝(100%)이고 낭독은 「실시간」 이다', () => {
    const { slider } = renderSeekBar({ behindSeconds: 2, windowSeconds: 28, atEdge: true });
    expect(thumbLeft(slider)).toBe('100%');
    expect(slider).toHaveAttribute('aria-valuetext', '실시간');
  });

  it('시차 3초는 끝이 아니다 — 손잡이가 창 비율 자리이고 낭독은 시차다', () => {
    const { slider } = renderSeekBar({ behindSeconds: 3, windowSeconds: 28, atEdge: false });
    expect(thumbPct(slider)).toBeCloseTo((25 / 28) * 100, 6);
    expect(slider).toHaveAttribute('aria-valuetext', '실시간에서 -00:03');
  });

  it('보이는 숫자가 2초여도 재생기가 끝이 아니라고 하면 끝이 아니다 — LIVE 단추와 같은 판정', () => {
    const { slider } = renderSeekBar({ behindSeconds: 2, windowSeconds: 28, atEdge: false });
    expect(thumbPct(slider)).toBeCloseTo((26 / 28) * 100, 6);
    expect(slider).toHaveAttribute('aria-valuetext', '실시간에서 -00:02');
  });

  it('끝이면 ARIA 값도 손잡이 · 낭독과 같은 0 이다 — 보이는 시차 2초를 읽지 않는다', () => {
    const { slider } = renderSeekBar({ behindSeconds: 2, windowSeconds: 28, atEdge: true });
    expect(slider).toHaveAttribute('aria-valuenow', '0');
  });

  it('끝이 아니면 ARIA 값은 −시차다', () => {
    const { slider } = renderSeekBar({ behindSeconds: 2, windowSeconds: 28, atEdge: false });
    expect(slider).toHaveAttribute('aria-valuenow', '-2');
  });

  it('드래그 프리뷰는 놓을 자리로 끝을 판정한다 — 재생기의 atEdge 를 읽지 않는다', () => {
    const { slider } = renderSeekBar({ behindSeconds: 0, windowSeconds: 28, atEdge: true });
    fireEvent.pointerDown(slider, { pointerId: 1, button: 0, clientX: 500 });
    expect(slider).toHaveAttribute('aria-valuetext', '실시간에서 -00:14');
    expect(thumbLeft(slider)).toBe('50%');
  });
});
