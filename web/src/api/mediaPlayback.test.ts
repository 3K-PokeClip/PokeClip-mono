import { describe, expect, it } from 'vitest';
import { parseSpanStartMs } from './mediaPlayback';

describe('parseSpanStartMs — 녹화 시작을 ms로 올린다(POK-253)', () => {
  it('ms 아래가 있으면 올린다 — 내리면 두 번째 구간 요청이 틈 안으로 들어가 404다(실측)', () => {
    expect(parseSpanStartMs('2026-09-30T01:58:16.372191Z')).toBe(
      Date.parse('2026-09-30T01:58:16.373Z'),
    );
  });

  it('ms 아래가 0이거나 없으면 그대로다', () => {
    expect(parseSpanStartMs('2026-09-30T01:58:16.372000Z')).toBe(
      Date.parse('2026-09-30T01:58:16.372Z'),
    );
    expect(parseSpanStartMs('2026-09-30T01:58:16.372Z')).toBe(
      Date.parse('2026-09-30T01:58:16.372Z'),
    );
    expect(parseSpanStartMs('2026-09-30T01:58:16Z')).toBe(Date.parse('2026-09-30T01:58:16Z'));
  });
});
