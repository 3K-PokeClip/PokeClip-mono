import { describe, expect, it } from 'vitest';
import { unpinnedX } from './useLiveSession';

// 고정 안 한 화면의 지금 방송 번호 X(cx H4 · R58 확장) — 다시 고르기가 실패하면 보던 방송이라고 단정하지 않는다

describe('unpinnedX', () => {
  it('마지막 다시 고르기가 답한 방송 번호다(빈 문자열 = 방송 중인 것이 없다)', () => {
    expect(unpinnedX('live-2')).toEqual({ kind: 'known', id: 'live-2' });
    expect(unpinnedX('')).toEqual({ kind: 'known', id: '' });
  });

  it('그 물음이 실패했으면 모름이다 — 세션은 이번 폴링을 건너뛴다', () => {
    expect(unpinnedX(null)).toEqual({ kind: 'unknown' });
  });
});
