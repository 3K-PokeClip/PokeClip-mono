import { describe, expect, it } from 'vitest';
import { elapsedSinceStartMs } from './elapsedSinceStart';

// 방송 안 시각 표기의 0초는 이번 방송 시작이다(POK-562). 카드 · 재생 위치의 ms는 서버 기준점 축이고, 그 기준점은 같은 송출 키로
// 한 앞 방송들의 길이만큼 시작보다 앞설 수 있다(prod 실측 181시간)
const STARTED_AT = Date.parse('2026-11-10T12:00:00Z');
const HOURS_181 = 181 * 3_600_000;
const ORIGIN = STARTED_AT - HOURS_181;

describe('elapsedSinceStartMs — 이번 방송 시작부터 잰 경과', () => {
  it('기준점이 시작보다 181시간 앞이면 축 값에서 그만큼 뺀다', () => {
    expect(elapsedSinceStartMs(HOURS_181 + 42_000, ORIGIN, STARTED_AT)).toBe(42_000);
  });

  it('기준점이 시작과 같으면 축 값 그대로다', () => {
    expect(elapsedSinceStartMs(60_000, STARTED_AT, STARTED_AT)).toBe(60_000);
  });

  it('시작보다 앞선 자리는 0이다', () => {
    expect(elapsedSinceStartMs(1_000, ORIGIN, STARTED_AT)).toBe(0);
  });

  it('기준점이나 시작을 모르면 null이다 — 다른 값으로 대신 재지 않는다', () => {
    expect(elapsedSinceStartMs(42_000, null, STARTED_AT)).toBeNull();
    expect(elapsedSinceStartMs(42_000, ORIGIN, null)).toBeNull();
    // 읽지 못한 시각(Date.parse 의 NaN)도 모르는 것이다
    expect(elapsedSinceStartMs(42_000, Number.NaN, STARTED_AT)).toBeNull();
    expect(elapsedSinceStartMs(42_000, ORIGIN, Number.NaN)).toBeNull();
  });
});
