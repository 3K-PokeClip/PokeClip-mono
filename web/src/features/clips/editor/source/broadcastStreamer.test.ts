import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BroadcastRow } from '@/api/clipEditor';

// 「이 방송의 채널 번호」 판정과 그 번호로만 묻는 지금 방송 번호(계획 prw2 4절 P-9 · 5절 E4b-1 R58 입력 · A3). 편집기는
// ChannelScope 밖이라 채널 번호가 없다 — 섞어서(null) 물으면 다른 채널의 live 첫 줄이 와 R58 이 「다른 방송」 으로 판정한다.
vi.mock('@/api/clipEditor', () => ({
  fetchMeLoose: vi.fn(),
  isBroadcastInChannel: vi.fn(),
}));
vi.mock('@/api/editors', () => ({ fetchDelegationsAsEditor: vi.fn() }));
vi.mock('@/features/broadcast/streamSelection', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/broadcast/streamSelection')>()),
  resolveLiveStreamId: vi.fn(),
}));

const { fetchMeLoose, isBroadcastInChannel } = await import('@/api/clipEditor');
const { fetchDelegationsAsEditor } = await import('@/api/editors');
const { resolveLiveStreamId } = await import('@/features/broadcast/streamSelection');
const { editorXResolver, streamerIdOf } = await import('./broadcastStreamer');

const A = 10;
const B = 20;

function broadcast(
  relation: string,
  streamId = 'round-a',
): Pick<BroadcastRow, 'streamId' | 'relation'> {
  return { streamId, relation };
}

function delegation(streamerId: number) {
  return {
    id: streamerId,
    streamerId,
    streamerName: `s${streamerId}`,
    streamerProfileImageUrl: null,
    grantedAt: '',
  };
}

beforeEach(() => {
  vi.clearAllMocks();
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('streamerIdOf: 이 방송의 채널(스트리머) 번호', () => {
  it('내 방송이면 내 번호다 · 위임은 묻지 않는다', async () => {
    vi.mocked(fetchMeLoose).mockResolvedValue({ id: 7 });

    expect(await streamerIdOf(broadcast('OWNER'))).toBe(7);
    expect(fetchDelegationsAsEditor).not.toHaveBeenCalled();
  });

  it('내 번호가 숫자 글로 와도 번호로 읽고, 숫자가 아니면 모름(null)이다', async () => {
    vi.mocked(fetchMeLoose).mockResolvedValueOnce({ id: '7' }).mockResolvedValueOnce({ id: 'x' });

    expect(await streamerIdOf(broadcast('OWNER'))).toBe(7);
    expect(await streamerIdOf(broadcast('OWNER'))).toBeNull();
  });

  it('편집자이고 위임이 하나면 그 스트리머 번호다 · 채널 대조는 하지 않는다', async () => {
    vi.mocked(fetchDelegationsAsEditor).mockResolvedValue([delegation(A)]);

    expect(await streamerIdOf(broadcast('EDITOR'))).toBe(A);
    expect(isBroadcastInChannel).not.toHaveBeenCalled();
  });

  it('위임이 여럿이면 그 방송이 든 채널(처음 참인 번호)이다', async () => {
    vi.mocked(fetchDelegationsAsEditor).mockResolvedValue([delegation(B), delegation(A)]);
    vi.mocked(isBroadcastInChannel).mockImplementation(async (_id, streamerId) => streamerId === A);

    expect(await streamerIdOf(broadcast('EDITOR'))).toBe(A);
    expect(vi.mocked(isBroadcastInChannel).mock.calls).toEqual([
      ['round-a', B],
      ['round-a', A],
    ]);
  });

  it('위임이 없거나 · 어느 채널에도 없거나 · 조회가 실패하면 모름(null)이다', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.mocked(fetchDelegationsAsEditor)
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([delegation(A), delegation(B)])
      .mockRejectedValueOnce(new Error('500'));
    vi.mocked(isBroadcastInChannel).mockResolvedValue(false);

    expect(await streamerIdOf(broadcast('EDITOR'))).toBeNull();
    expect(await streamerIdOf(broadcast('EDITOR'))).toBeNull();
    expect(await streamerIdOf(broadcast('EDITOR'))).toBeNull();
    // 조회 실패는 오류 이름만 적는다 — 오류 객체를 통째로 싣지 않는다
    expect(warn).toHaveBeenLastCalledWith('[editor] 방송의 채널 번호를 정하지 못했어요(Error)');
  });

  it('오류가 아닌 값(null)이 던져져도 모름(null)이고 경고에는 그 값의 종류만 적는다', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.mocked(fetchDelegationsAsEditor).mockRejectedValueOnce(null);

    expect(await streamerIdOf(broadcast('EDITOR'))).toBeNull();
    expect(warn).toHaveBeenLastCalledWith('[editor] 방송의 채널 번호를 정하지 못했어요(object)');
  });
});

describe('editorXResolver: 그 채널 번호로만 지금 방송 번호를 묻는다', () => {
  it('번호가 있으면 resolveLiveStreamId(번호, true) 로만 묻고 섞어서(null) 묻지 않는다', async () => {
    vi.mocked(resolveLiveStreamId).mockResolvedValue('round-a');
    const resolveX = editorXResolver(Promise.resolve(A), 'key-1');

    expect(await resolveX(true)).toEqual({ kind: 'known', id: 'round-a' });
    expect(vi.mocked(resolveLiveStreamId).mock.calls).toEqual([[A, true]]);
  });

  it('번호를 모르면(null) 늘 모름이고 지금 방송 번호를 묻지 않는다', async () => {
    vi.mocked(resolveLiveStreamId).mockResolvedValue('round-b');
    const resolveX = editorXResolver(Promise.resolve(null), 'key-1');

    expect(await resolveX(true)).toEqual({ kind: 'unknown' });
    expect(await resolveX(false)).toEqual({ kind: 'unknown' });
    expect(resolveLiveStreamId).not.toHaveBeenCalled();
  });

  it('10초 안의 다시 묻기(fresh 거짓)는 앞 답을 다시 쓴다', async () => {
    vi.mocked(resolveLiveStreamId).mockResolvedValue('round-a');
    const resolveX = editorXResolver(Promise.resolve(A), 'key-1');
    await resolveX(false);

    expect(await resolveX(false)).toEqual({ kind: 'known', id: 'round-a' });
    expect(resolveLiveStreamId).toHaveBeenCalledTimes(1);
  });

  it('답이 2초 안에 오지 않으면 모름이다', async () => {
    vi.useFakeTimers();
    vi.mocked(resolveLiveStreamId).mockReturnValue(new Promise(() => {}));
    const resolveX = editorXResolver(Promise.resolve(A), 'key-1');

    const answer = resolveX(true);
    await vi.advanceTimersByTimeAsync(2_000);

    expect(await answer).toEqual({ kind: 'unknown' });
  });
});
