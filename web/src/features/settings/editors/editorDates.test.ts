import { describe, expect, it } from 'vitest';
import { expiresLabel, joinedLabel } from './editorDates';

describe('joinedLabel', () => {
  it('위임 생성 시각을 「M월 D일 합류」로 그린다', () => {
    expect(joinedLabel('2026-05-12T09:00:00Z')).toBe('5월 12일 합류');
  });
});

describe('expiresLabel', () => {
  const now = new Date('2026-08-25T09:00:00Z');

  it('하루 이상은 일 단위 round다 — 발송 직후(7일 - 몇 분)가 「7일」, 25시간이 「1일」', () => {
    expect(expiresLabel('2026-09-01T08:57:00Z', now)).toBe('7일 후 만료');
    expect(expiresLabel('2026-09-01T09:00:00Z', now)).toBe('7일 후 만료');
    expect(expiresLabel('2026-08-26T10:00:00Z', now)).toBe('1일 후 만료');
  });

  it('하루 미만은 시간 단위 floor다 — 시안 1l의 「18시간 후 만료」', () => {
    expect(expiresLabel('2026-08-26T03:30:00Z', now)).toBe('18시간 후 만료');
    expect(expiresLabel('2026-08-26T08:59:00Z', now)).toBe('23시간 후 만료');
  });

  it('1시간 미만·경계·과거는 「곧 만료」로 클램프한다 — 조회 후 경과·시계 오차 대비', () => {
    expect(expiresLabel('2026-08-25T09:30:00Z', now)).toBe('곧 만료');
    expect(expiresLabel('2026-08-25T09:00:00Z', now)).toBe('곧 만료');
    expect(expiresLabel('2026-08-25T08:00:00Z', now)).toBe('곧 만료');
  });
});
