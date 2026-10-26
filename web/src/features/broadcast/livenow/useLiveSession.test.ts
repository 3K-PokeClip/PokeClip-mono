import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinnedXResolver, unpinnedX } from './useLiveSession';

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

// 고정 화면의 지금 방송 번호 기억(cx H3) — 늦게 온 옛 물음이 새 답을 덮어 다른 세션 폴링에 재사용되면 foreignLive 가 굳는다
describe('createPinnedXResolver', () => {
  beforeEach(() => {
    vi.useFakeTimers({ now: Date.parse('2026-10-26T03:00:00.000Z') });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  /** 물음마다 시험이 답을 쥐는 조회 흉내 */
  function queue() {
    const pending: ((id: string | null) => void)[] = [];
    const query = vi.fn(
      () =>
        new Promise<string | null>((resolve) => {
          pending.push(resolve);
        }),
    );
    return { query, pending };
  }

  it('10초 안의 폴링은 같은 키의 마지막 답을 다시 쓴다 · LIVE(fresh)는 새로 묻는다', async () => {
    const { query, pending } = queue();
    const resolveX = createPinnedXResolver(query);
    const first = resolveX(true, 'r-A|k');
    pending[0]!('r-A');
    await expect(first).resolves.toEqual({ kind: 'known', id: 'r-A' });
    await expect(resolveX(false, 'r-A|k')).resolves.toEqual({ kind: 'known', id: 'r-A' });
    expect(query).toHaveBeenCalledTimes(1);
    void resolveX(true, 'r-A|k');
    expect(query).toHaveBeenCalledTimes(2);
  });

  it('A 의 늦은 답은 B 세션이 새로 받은 답을 덮지 않는다', async () => {
    const { query, pending } = queue();
    const resolveX = createPinnedXResolver(query);
    const late = resolveX(true, 'r-A|k');
    const fresh = resolveX(true, 'r-B|k');
    pending[1]!('r-B');
    await fresh;
    pending[0]!('r-A');
    await late;
    await expect(resolveX(false, 'r-B|k')).resolves.toEqual({ kind: 'known', id: 'r-B' });
    expect(query).toHaveBeenCalledTimes(2);
  });

  it('같은 키라도 늦게 끝난 옛 물음은 기억에 쓰지 않는다(같은 방송을 다시 연 세션)', async () => {
    const { query, pending } = queue();
    const resolveX = createPinnedXResolver(query);
    const late = resolveX(true, 'r-A|k');
    const fresh = resolveX(true, 'r-A|k');
    pending[1]!('r-B');
    await fresh;
    pending[0]!('r-A');
    await late;
    await expect(resolveX(false, 'r-A|k')).resolves.toEqual({ kind: 'known', id: 'r-B' });
  });

  it('다른 방송|영상 키의 기억은 다시 쓰지 않는다 — 새로 묻는다', async () => {
    const { query, pending } = queue();
    const resolveX = createPinnedXResolver(query);
    const first = resolveX(true, 'r-A|k');
    pending[0]!('r-A');
    await first;
    void resolveX(false, 'r-B|k');
    expect(query).toHaveBeenCalledTimes(2);
  });

  it('조회가 답하지 못하면(null) 모름이다', async () => {
    const { query, pending } = queue();
    const resolveX = createPinnedXResolver(query);
    const answer = resolveX(true, 'r-A|k');
    pending[0]!(null);
    await expect(answer).resolves.toEqual({ kind: 'unknown' });
  });
});
