import { describe, expect, it } from 'vitest';
import {
  AT_EDGE_THRESHOLD_SECONDS,
  LIVE_EDGE_BACKOFF_SECONDS,
  LIVE_WINDOW_SECONDS,
  behindFromSeekFraction,
  formatBehind,
  formatUptime,
  isAtEdge,
  progressFraction,
} from './playerMath';

describe('formatBehind — 계약3 4절 시차 표기', () => {
  it('분을 2자리로 패딩한다 (-01:23)', () => {
    expect(formatBehind(83)).toBe('-01:23');
    expect(formatBehind(307)).toBe('-05:07');
  });

  it('상한은 -1:00:00이다', () => {
    expect(formatBehind(LIVE_WINDOW_SECONDS)).toBe('-1:00:00');
    expect(formatBehind(LIVE_WINDOW_SECONDS + 999)).toBe('-1:00:00');
    expect(formatBehind(3599)).toBe('-59:59');
  });

  it('음수·0은 -00:00으로 바닥을 친다', () => {
    expect(formatBehind(0)).toBe('-00:00');
    expect(formatBehind(-5)).toBe('-00:00');
  });
});

describe('formatUptime', () => {
  it('1시간 이상은 H:MM:SS', () => {
    expect(formatUptime(5043)).toBe('1:24:03');
  });

  it('1시간 미만은 M:SS', () => {
    expect(formatUptime(754)).toBe('12:34');
    expect(formatUptime(7)).toBe('0:07');
  });
});

describe('behindFromSeekFraction', () => {
  it('클릭 위치를 시차로 환산한다 (우측 끝 = 엣지)', () => {
    expect(behindFromSeekFraction(1)).toBe(0);
    expect(behindFromSeekFraction(0.5)).toBe(LIVE_WINDOW_SECONDS / 2);
  });

  it('엣지 3초 미만은 0으로 스냅한다', () => {
    const nearEdge = 1 - 2 / LIVE_WINDOW_SECONDS; // 시차 2초 지점
    expect(behindFromSeekFraction(nearEdge)).toBe(0);
  });

  it('범위를 벗어난 입력은 0..윈도우로 클램프한다', () => {
    expect(behindFromSeekFraction(-0.2)).toBe(LIVE_WINDOW_SECONDS);
    expect(behindFromSeekFraction(1.7)).toBe(0);
  });
});

describe('progressFraction', () => {
  it('엣지는 1, 윈도우 끝은 0', () => {
    expect(progressFraction(0)).toBe(1);
    expect(progressFraction(LIVE_WINDOW_SECONDS)).toBe(0);
    expect(progressFraction(LIVE_WINDOW_SECONDS * 2)).toBe(0);
  });
});

describe('isAtEdge', () => {
  it('경계값 3초에서 갈린다', () => {
    expect(isAtEdge(0)).toBe(true);
    expect(isAtEdge(AT_EDGE_THRESHOLD_SECONDS - 1)).toBe(true);
    expect(isAtEdge(AT_EDGE_THRESHOLD_SECONDS)).toBe(false);
  });
});

describe('LIVE_EDGE_BACKOFF_SECONDS', () => {
  // 임계값과의 관계는 여기서 검증하지 않는다 — 시차를 백오프 지점 기준으로 재므로
  // 스냅 직후 시차는 0이다. 실제 회귀(스냅 → 재칠)는 dvrWindow.test.ts가 잡는다.
  it('부분 세그먼트를 피하려면 0보다 커야 한다', () => {
    expect(LIVE_EDGE_BACKOFF_SECONDS).toBeGreaterThan(0);
  });
});
