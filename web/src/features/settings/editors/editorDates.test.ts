import { describe, expect, it } from 'vitest';
import { expiresLabel, joinedLabel } from './editorDates';

describe('joinedLabel', () => {
  it('위임 생성 시각을 「M월 D일 합류」로 그린다', () => {
    expect(joinedLabel('2026-05-12T09:00:00Z')).toBe('5월 12일 합류');
  });
});

describe('expiresLabel', () => {
  const now = new Date('2026-08-25T09:00:00Z');

  it('남은 기간을 일 단위 ceil로 그린다 — 발송 직후 7일', () => {
    expect(expiresLabel('2026-09-01T09:00:00Z', now)).toBe('7일 후 만료');
  });

  it('하루 미만 잔여도 올림이다 — 23시간 59분은 「1일 후 만료」', () => {
    expect(expiresLabel('2026-08-26T08:59:00Z', now)).toBe('1일 후 만료');
  });

  it('경계와 과거는 「오늘 만료」로 클램프한다 — 조회 후 경과·시계 오차 대비', () => {
    expect(expiresLabel('2026-08-25T09:00:00Z', now)).toBe('오늘 만료');
    expect(expiresLabel('2026-08-25T08:00:00Z', now)).toBe('오늘 만료');
  });
});
