import { describe, expect, it } from 'vitest';
import {
  AT_EDGE_THRESHOLD_SECONDS,
  LIVE_EDGE_BACKOFF_SECONDS,
  LIVE_WINDOW_SECONDS,
  behindFromSeekFraction,
  formatBehind,
  formatUptime,
  isAtEdge,
  parseClockLabel,
  progressFraction,
  seekFractionFromPointer,
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

describe('parseClockLabel — formatUptime의 역함수', () => {
  it('H:MM:SS·M:SS를 초로 되돌린다', () => {
    expect(parseClockLabel('1:24:03')).toBe(5043);
    expect(parseClockLabel('12:34')).toBe(754);
    expect(parseClockLabel('0:07')).toBe(7);
  });

  it('formatUptime과 왕복한다 — 카드 timestamp가 이 왕복 위에 선다', () => {
    for (const seconds of [0, 7, 754, 5043, 3599, 3600]) {
      expect(parseClockLabel(formatUptime(seconds))).toBe(seconds);
    }
  });

  it('시계 표기가 아니면 null이다 — 호출부가 시킹을 건너뛴다', () => {
    expect(parseClockLabel('방금')).toBeNull();
    expect(parseClockLabel('1:24:03:07')).toBeNull();
    expect(parseClockLabel('5043')).toBeNull();
    expect(parseClockLabel('1:2a:03')).toBeNull();
    // 분·초 칸이 60을 넘으면 시계가 아니다
    expect(parseClockLabel('1:60:03')).toBeNull();
    expect(parseClockLabel('1:24:99')).toBeNull();
  });
});

describe('behindFromSeekFraction', () => {
  it('시크바 위치를 시차로 환산한다 (우측 끝 = 엣지)', () => {
    expect(behindFromSeekFraction(1, LIVE_WINDOW_SECONDS)).toBe(0);
    expect(behindFromSeekFraction(0.5, LIVE_WINDOW_SECONDS)).toBe(LIVE_WINDOW_SECONDS / 2);
  });

  it('엣지 3초 미만은 0으로 스냅한다', () => {
    const nearEdge = 1 - 2 / LIVE_WINDOW_SECONDS; // 시차 2초 지점
    expect(behindFromSeekFraction(nearEdge, LIVE_WINDOW_SECONDS)).toBe(0);
  });

  it('범위를 벗어난 입력은 0..윈도우로 클램프한다', () => {
    expect(behindFromSeekFraction(-0.2, LIVE_WINDOW_SECONDS)).toBe(LIVE_WINDOW_SECONDS);
    expect(behindFromSeekFraction(1.7, LIVE_WINDOW_SECONDS)).toBe(0);
  });

  it('창이 짧으면 그 창 기준으로 환산한다 — POK-32', () => {
    // 방송 10분: 왼쪽 끝이 방송 시작점이어야 한다 (1시간 기준이면 좌측 83%가 죽은 영역)
    expect(behindFromSeekFraction(0, 600)).toBe(600);
    expect(behindFromSeekFraction(0.5, 600)).toBe(300);
  });

  it('창은 계약 상한 1시간으로 잘린다', () => {
    expect(behindFromSeekFraction(0.5, 99999)).toBe(LIVE_WINDOW_SECONDS / 2);
  });

  it('되감을 곳이 없으면 항상 0이다', () => {
    expect(behindFromSeekFraction(0, 0)).toBe(0);
    expect(behindFromSeekFraction(0, -10)).toBe(0);
    expect(behindFromSeekFraction(0, Number.NaN)).toBe(0);
  });
});

describe('progressFraction', () => {
  it('엣지는 1, 윈도우 끝은 0', () => {
    expect(progressFraction(0, LIVE_WINDOW_SECONDS)).toBe(1);
    expect(progressFraction(LIVE_WINDOW_SECONDS, LIVE_WINDOW_SECONDS)).toBe(0);
    expect(progressFraction(LIVE_WINDOW_SECONDS * 2, LIVE_WINDOW_SECONDS)).toBe(0);
  });

  it('창이 짧으면 그 창 기준으로 채운다 — POK-32', () => {
    expect(progressFraction(300, 600)).toBe(0.5);
    expect(progressFraction(600, 600)).toBe(0);
  });

  it('되감을 곳이 없으면 엣지(1)로 둔다 — 0 나눗셈 방어', () => {
    // 방어가 없으면 NaN이 CSS width로 나가 트랙이 사라진다
    expect(progressFraction(0, 0)).toBe(1);
    expect(progressFraction(10, 0)).toBe(1);
    expect(progressFraction(1, Number.NaN)).toBe(1);
  });
});

describe('seekFractionFromPointer', () => {
  it('트랙 안 좌표를 비율로 환산한다', () => {
    expect(seekFractionFromPointer({ left: 100, width: 400 }, 300)).toBe(0.5);
    expect(seekFractionFromPointer({ left: 100, width: 400 }, 100)).toBe(0);
  });

  it('트랙 밖으로 나간 드래그 좌표는 0..1로 클램프한다', () => {
    expect(seekFractionFromPointer({ left: 100, width: 400 }, 40)).toBe(0);
    expect(seekFractionFromPointer({ left: 100, width: 400 }, 900)).toBe(1);
  });

  it('폭이 없으면 계산 불가라 null이다', () => {
    expect(seekFractionFromPointer({ left: 0, width: 0 }, 50)).toBeNull();
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
