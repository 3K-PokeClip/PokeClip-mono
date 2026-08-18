import { describe, expect, it } from 'vitest';
import { behindFromCurrentTime, dvrRange, type SeekableLike } from './dvrWindow';

// TimeRanges 호환 plain object — jsdom 미디어 구현 없이 검증한다
function seekable(...ranges: Array<[number, number]>): SeekableLike {
  return {
    length: ranges.length,
    start: (i) => ranges[i]![0],
    end: (i) => ranges[i]![1],
  };
}

describe('dvrRange', () => {
  it('seekable이 비어 있으면 null — 매니페스트 로드 전', () => {
    expect(dvrRange(seekable())).toBeNull();
  });

  it('폭이 0인 범위도 null — 시크할 곳이 없다', () => {
    expect(dvrRange(seekable([120, 120]))).toBeNull();
  });

  it('창보다 좁으면 seekable 그대로 — 스텁(VOD 5분)·방송 초반', () => {
    expect(dvrRange(seekable([0, 300]))).toEqual({ start: 0, end: 300 });
  });

  it('창보다 넓으면 끝에서 1시간으로 자른다 (계약3 4절 4번)', () => {
    expect(dvrRange(seekable([0, 5000]))).toEqual({ start: 1400, end: 5000 });
  });

  it('여러 구간이면 첫 시작·마지막 끝을 쓴다', () => {
    expect(dvrRange(seekable([100, 200], [250, 900]))).toEqual({ start: 100, end: 900 });
  });

  it('창 인자를 줄이면 그만큼만 시크 가능하다', () => {
    expect(dvrRange(seekable([0, 5000]), 600)).toEqual({ start: 4400, end: 5000 });
  });
});

describe('behindFromCurrentTime', () => {
  const range = { start: 0, end: 1000 };

  it('엣지 대비 시차를 정수 초로 반올림한다', () => {
    expect(behindFromCurrentTime(range, 940.4)).toBe(60);
    expect(behindFromCurrentTime(range, 939.4)).toBe(61);
  });

  it('엣지를 넘어선 위치는 0으로 클램프한다', () => {
    expect(behindFromCurrentTime(range, 1000.5)).toBe(0);
  });
});
