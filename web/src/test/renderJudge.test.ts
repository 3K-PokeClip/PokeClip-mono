import { describe, expect, it } from 'vitest';
import {
  clipLocate,
  clipLocateEnd,
  newPathFirstFrame,
  newPathLastFrame,
  oldPathFirstFrame,
  oldPathLastFrame,
  oldPathRange,
  type JudgeMedia,
  type JudgeRow,
} from './renderJudge';

// 판정 함수 자체가 원본 규칙(LedgerTimeline · RenderTimeline · 옛 -ss -t · checkRange)을 옮겼는지 본다 — 편집기 시험이
// 이 함수로 렌더 결과를 기대하므로 판정이 틀리면 편집기 시험이 거짓으로 초록이 된다. 기대값은 원본 주석 · 경계 답의 식에서
// 손으로 옮겼다.

const ROWS: readonly JudgeRow[] = [
  { seq: 1, pdtMs: 0, durationMs: 4000, continuesPrevious: false },
  { seq: 2, pdtMs: 4000, durationMs: 4000, continuesPrevious: true },
  { seq: 3, pdtMs: 9000, durationMs: 4000, continuesPrevious: false },
];

/** 30fps 프레임 n 개 — PTS = (D0 틱 + c 틱 + 3000·i)/90000 */
function frames(d0Ticks: number, cTicks: number, count: number): number[] {
  return Array.from({ length: count }, (_, i) => (d0Ticks + cTicks + 3000 * i) / 90_000);
}

describe('clip 자리 판정', () => {
  it('시작은 F 규칙으로 덮는 조각 · 이어진 틈은 뒤 조각 머리 · 끊긴 틈은 공백 · 마지막 뒤는 거절이다', () => {
    expect(clipLocate(ROWS, 4500)).toEqual({ kind: 'media', seq: 2, offsetMs: 500 });
    expect(clipLocate(ROWS, 8500)).toEqual({ kind: 'gap', beforeSeq: 2, afterSeq: 3 });
    expect(clipLocate(ROWS, 13_000)).toEqual({ kind: 'afterLast' });
    expect(clipLocate(ROWS, -1)).toEqual({ kind: 'beforeFirst' });
  });

  it('끝은 이어진 이웃 시작과 같으면 앞 조각 끝 · 끊긴 틈은 공백 · 마지막 뒤는 거절이다', () => {
    expect(clipLocateEnd(ROWS, 4000)).toEqual({ kind: 'media', seq: 1, offsetMs: 4000 });
    expect(clipLocateEnd(ROWS, 8500)).toEqual({ kind: 'gap', beforeSeq: 2, afterSeq: 3 });
    expect(clipLocateEnd(ROWS, 13_001)).toEqual({ kind: 'afterLast' });
  });
});

describe('렌더 새 경로', () => {
  const seg: JudgeMedia = { videoDts: 0, m: 0, e: 0, frames: frames(0, 0, 120) };
  const media = new Map([[1, seg]]);
  const rows: readonly JudgeRow[] = [ROWS[0]!];

  it('시작은 PTS − D0 ≤ x′ 인 마지막 프레임이다', () => {
    expect(newPathFirstFrame(rows, media, 1033, 'VIDEO_START')?.pts).toBeCloseTo(1.0, 9);
    expect(newPathFirstFrame(rows, media, 1000, 'VIDEO_START')?.pts).toBeCloseTo(1.0, 9);
  });

  it('끝은 PTS − D0 < x′ 인 프레임까지다', () => {
    expect(newPathLastFrame(rows, media, 1066, 'VIDEO_START')?.pts).toBeCloseTo(31 / 30, 9);
    expect(newPathLastFrame(rows, media, 1067, 'VIDEO_START')?.pts).toBeCloseTo(32 / 30, 9);
  });

  it('FILE_HEAD 는 x 에서 머리 앞섬 m 을 빼고 판다', () => {
    const lead = new Map([[1, { ...seg, m: 0.2 }]]);
    expect(newPathFirstFrame(rows, lead, 1200, 'FILE_HEAD')?.pts).toBeCloseTo(1.0, 9);
  });
});

describe('렌더 옛 경로', () => {
  const seg: JudgeMedia = { videoDts: 0, m: 0, e: 0.04, frames: frames(0, 6000, 120) };

  it('0점은 Hp 이고 시작은 목표 이후 첫 프레임이다', () => {
    expect(oldPathFirstFrame(seg, 0, 960)).toBeCloseTo(1.0, 9);
    expect(oldPathFirstFrame(seg, 0, 961)).toBeCloseTo(31 / 30, 9);
  });

  it('끝은 목표보다 앞인 마지막 프레임이다', () => {
    expect(oldPathLastFrame(seg, 0, 960)).toBeCloseTo(29 / 30, 9);
  });

  it('덮음이 100ms 넘게 모자라거나 조각 사이가 500ms 넘게 비면 SOURCE_RANGE 다', () => {
    expect(oldPathRange(ROWS, 1000, 8100)).toBe('ok');
    expect(oldPathRange(ROWS, 1000, 8101 + 1000)).toBe('SOURCE_RANGE');
    expect(oldPathRange([ROWS[0]!], 1000, 4101)).toBe('SOURCE_RANGE');
  });
});
