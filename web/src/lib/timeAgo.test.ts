import { describe, expect, it } from 'vitest';
import { timeAgo } from './timeAgo';

describe('timeAgo: 만든 지 얼마나 됐나', () => {
  const NOW = Date.parse('2026-10-11T02:00:00Z');
  const ago = (seconds: number) => new Date(NOW - seconds * 1000).toISOString();

  it('막 뜬 것은 초까지 센다: 라이브에서 방금 감지된 카드다', () => {
    expect(timeAgo(ago(5), NOW)).toBe('방금');
    expect(timeAgo(ago(42), NOW)).toBe('42초 전');
  });

  it('한 시간 안은 분, 하루 안은 시간이다', () => {
    expect(timeAgo(ago(59 * 60 + 59), NOW)).toBe('59분 전');
    expect(timeAgo(ago(3600), NOW)).toBe('1시간 전');
    expect(timeAgo(ago(86_399), NOW)).toBe('23시간 전');
  });

  it('하루가 넘으면 일이다: 지난 방송 카드가 「4275분 전」이 되지 않는다', () => {
    expect(timeAgo(ago(4275 * 60), NOW)).toBe('2일 전');
  });

  it('시계가 어긋나 미래 시각이 와도 「방금」이다', () => {
    expect(timeAgo(new Date(NOW + 5_000).toISOString(), NOW)).toBe('방금');
  });
});
