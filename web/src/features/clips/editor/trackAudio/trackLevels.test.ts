import { describe, expect, it } from 'vitest';
import type { TrackPiece } from './loadTrackAudio';
import { levelToHeight, measureLevels } from './trackLevels';

// 가짜 소리 덩어리: 표본 빈도 100, 채널 하나. 값이 그대로 소리 크기다
function piece(startSeconds: number, samples: number[]): TrackPiece {
  const data = Float32Array.from(samples);
  return {
    startSeconds,
    buffer: {
      sampleRate: 100,
      numberOfChannels: 1,
      duration: samples.length / 100,
      getChannelData: () => data,
    } as unknown as AudioBuffer,
  };
}

describe('measureLevels: 트랙마다 일정 간격의 소리 크기(최댓값)', () => {
  it('덩어리 자리에 맞춰 칸을 채우고, 덩어리가 없는 칸은 0이다', () => {
    // 0번 트랙: 10초부터 0.2초 동안 0.5 · 1번 트랙: 무음
    const tracks = [[piece(10, Array(20).fill(0.5))], [piece(10, Array(20).fill(0))]];
    const levels = measureLevels(tracks, 9.9, 10.3, 0.1);
    expect(levels.startSeconds).toBe(9.9);
    expect(levels.stepSeconds).toBe(0.1);
    expect([...levels.tracks[0]!].map((v) => +v.toFixed(2))).toEqual([0, 0.5, 0.5, 0]);
    expect([...levels.tracks[1]!]).toEqual([0, 0, 0, 0]);
  });

  it('음수 표본도 크기로 센다(파형은 위아래로 흔들린다)', () => {
    const levels = measureLevels([[piece(0, [0.1, -0.8, 0.2])]], 0, 0.03, 0.03);
    expect(levels.tracks[0]![0]).toBeCloseTo(0.8);
  });
});

describe('levelToHeight: 소리 크기 → 띠 높이(0~1). 귀처럼 데시벨로 펴고 -60dB 밑은 평평', () => {
  it('무음과 -60dB 밑은 0, 최대는 1, 음량 비율을 곱한다', () => {
    expect(levelToHeight(0, 1)).toBe(0);
    expect(levelToHeight(0.0005, 1)).toBe(0); // 약 -66dB
    expect(levelToHeight(1, 1)).toBe(1);
    expect(levelToHeight(0.1, 1)).toBeCloseTo(2 / 3); // -20dB
    expect(levelToHeight(1, 0.1)).toBeCloseTo(2 / 3); // 음량 10%면 -20dB만큼 작다
    expect(levelToHeight(1, 0)).toBe(0); // 끈 트랙
  });
});
