import { describe, expect, it } from 'vitest';
import { linkLockOf } from './linkLock';

const NONE = { linked: false } as const;
const ACTIVE = { linked: true, status: 'ACTIVE' } as const;
const EXPIRED = { linked: true, status: 'EXPIRED' } as const;
const BROKEN = { linked: false, status: 'BROKEN' } as const;
const UNLINKED = { linked: false, status: 'UNLINKED' } as const;

const base = { status: 'live', relation: 'OWNER', chzzk: NONE, soop: NONE } as const;

describe('linkLockOf', () => {
  it('내 방송이 방송 중인데 치지직 · SOOP 둘 다 연동이 없으면 잠근다', () => {
    expect(linkLockOf(base)).toBe('unlinked');
    // 스스로 해제한 연동도 「없음」이다
    expect(linkLockOf({ ...base, chzzk: UNLINKED, soop: UNLINKED })).toBe('unlinked');
  });

  it('하나라도 연동이면 잠그지 않는다: 만료(EXPIRED)는 auth가 갱신해 수집기가 그대로 걷는다', () => {
    expect(linkLockOf({ ...base, chzzk: ACTIVE })).toBeNull();
    expect(linkLockOf({ ...base, soop: ACTIVE })).toBeNull();
    expect(linkLockOf({ ...base, chzzk: EXPIRED })).toBeNull();
    expect(linkLockOf({ ...base, chzzk: BROKEN, soop: ACTIVE })).toBeNull();
  });

  it('끊긴 연동(BROKEN)이 있으면 다시 연결하라는 까닭으로 잠근다', () => {
    expect(linkLockOf({ ...base, chzzk: BROKEN })).toBe('broken');
    expect(linkLockOf({ ...base, soop: BROKEN })).toBe('broken');
  });

  it('연동 조회가 하나라도 답하지 않았으면(읽는 중 · 실패) 잠그지 않는다: 모를 때 멀쩡한 방송을 가리지 않는다', () => {
    expect(linkLockOf({ ...base, chzzk: undefined })).toBeNull();
    expect(linkLockOf({ ...base, soop: undefined })).toBeNull();
  });

  it('위임받은 방송 · 관계를 모르는 방송은 잠그지 않는다: 연동 조회는 보는 사람 자신의 연동이다', () => {
    expect(linkLockOf({ ...base, relation: 'EDITOR' })).toBeNull();
    expect(linkLockOf({ ...base, relation: null })).toBeNull();
  });

  it('방송 중이 아니면 잠그지 않는다: 끝난 방송에는 그때 걷힌 기록이 있을 수 있다', () => {
    expect(linkLockOf({ ...base, status: 'ended' })).toBeNull();
    expect(linkLockOf({ ...base, status: 'unknown' })).toBeNull();
    expect(linkLockOf({ ...base, status: 'offline' })).toBeNull();
  });
});
