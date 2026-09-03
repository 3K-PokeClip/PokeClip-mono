import { describe, expect, it } from 'vitest';
import { boundaryAction, type PlaybackBounds } from './editorPlayback';

// 구간 경계는 시뮬레이션과 hls 어댑터가 나눠 쓰는 규칙이다. hls 쪽은 jsdom에 미디어 구현이 없어
// 렌더로 못 재므로, 규칙 자체를 여기서 표로 못박는다.

const RANGE: PlaybackBounds = { startSeconds: 100, endSeconds: 112.4, loop: true };
const NO_LOOP: PlaybackBounds = { ...RANGE, loop: false };

describe('boundaryAction', () => {
  it('구간 안이면 그대로 간다', () => {
    expect(boundaryAction(105, RANGE)).toEqual({ kind: 'continue' });
    expect(boundaryAction(105, NO_LOOP)).toEqual({ kind: 'continue' });
  });

  it('구간 시작 직전은 반복일 때만 끌어온다', () => {
    // 반복을 켜 두고 앞에서 재생하면 클립 밖이 재생된다 — 시작으로 데려온다
    expect(boundaryAction(80, RANGE)).toEqual({ kind: 'seek', toSeconds: 100 });
    // 반복이 꺼져 있으면 앞뒤 맥락을 보려고 일부러 나간 것이라 두고 본다
    expect(boundaryAction(80, NO_LOOP)).toEqual({ kind: 'continue' });
  });

  it('시작점 자체는 구간 안이다', () => {
    expect(boundaryAction(100, RANGE)).toEqual({ kind: 'continue' });
  });

  it('끝점에 닿으면 반복은 되감고 아니면 멈춘다', () => {
    // 끝은 열린 경계다 — 정확히 끝이면 이미 구간 밖이다
    expect(boundaryAction(112.4, RANGE)).toEqual({ kind: 'seek', toSeconds: 100 });
    expect(boundaryAction(112.4, NO_LOOP)).toEqual({ kind: 'stop', atSeconds: 112.4 });
  });

  it('끝을 지나쳤어도 멈추는 자리는 끝이다 — 지나친 만큼 되돌린다', () => {
    expect(boundaryAction(120, NO_LOOP)).toEqual({ kind: 'stop', atSeconds: 112.4 });
  });

  it('반복이면 시작 앞 판정이 끝 판정보다 앞선다', () => {
    // 구간이 뒤집힌 값이 들어와도(끝 < 시작) 무한 루프가 아니라 한쪽으로 결론이 난다
    const inverted: PlaybackBounds = { startSeconds: 112.4, endSeconds: 100, loop: true };
    expect(boundaryAction(50, inverted)).toEqual({ kind: 'seek', toSeconds: 112.4 });
  });
});
