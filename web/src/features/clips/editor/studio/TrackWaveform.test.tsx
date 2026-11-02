import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { TrackWaveform, wavePath } from './TrackWaveform';

describe('wavePath: 칸마다 위아래로 같은 높이의 띠(가운데 0.5 기준)', () => {
  it('큰 소리는 높고, 무음은 가는 평평한 선이다', () => {
    // 10초부터 1초 간격 두 칸: 최대(1) · 무음(0)
    const path = wavePath(Float32Array.from([1, 0]), 10, 1, 1);
    expect(path).toBe('M10,0 L11,0.49 L12,0.49 L12,0.51 L11,0.51 L10,1 Z');
  });

  it('음량을 줄이면 띠도 낮아지고, 끈 트랙은 평평하다', () => {
    const loud = wavePath(Float32Array.from([1]), 0, 1, 1);
    const quiet = wavePath(Float32Array.from([1]), 0, 1, 0.1);
    const off = wavePath(Float32Array.from([1]), 0, 1, 0);
    expect(loud).toBe('M0,0 L1,0 L1,1 L0,1 Z');
    expect(quiet).toBe('M0,0.17 L1,0.17 L1,0.83 L0,0.83 Z');
    expect(off).toBe('M0,0.49 L1,0.49 L1,0.51 L0,0.51 Z');
  });
});

describe('TrackWaveform', () => {
  it('보이는 창을 viewBox로 잘라 그린다(확대·이동에 다시 재지 않는다)', () => {
    const { container } = render(
      <TrackWaveform
        levels={Float32Array.from([0.5, 0.5])}
        startSeconds={10}
        stepSeconds={1}
        gain={1}
        view={{ startSeconds: 9, endSeconds: 13 }}
      />,
    );
    const svg = container.querySelector('svg')!;
    expect(svg.getAttribute('viewBox')).toBe('9 0 4 1');
    expect(svg.getAttribute('preserveAspectRatio')).toBe('none');
    expect(container.querySelector('path')!.getAttribute('d')).toMatch(/^M10,/);
  });
});
