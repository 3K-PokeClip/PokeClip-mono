import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchBroadcast, type BroadcastRow } from '@/api/clipEditor';
import { useBroadcastClock } from './useBroadcastClock';

vi.mock('@/api/clipEditor', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/clipEditor')>()),
  fetchBroadcast: vi.fn(),
}));

// 방송 시계의 30초 주기: 끝난 방송이고 시각이 다 왔으면 더 묻지 않는다. 끝난 뒤에 늦게 채워지는 기준점은 올 때까지 묻는다

const POLL_MS = 30_000;
const ROW: BroadcastRow = {
  streamId: '7',
  status: 'live',
  relation: 'OWNER',
  startedAt: '2026-10-31T01:00:00Z',
  endedAt: null,
  vodExpiresAt: null,
  timelineOriginAt: '2026-10-31T01:00:02Z',
};
const ENDED: BroadcastRow = { ...ROW, status: 'ended', endedAt: '2026-10-31T02:00:00Z' };

async function settle() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
}

async function tick(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

describe('useBroadcastClock 주기', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.mocked(fetchBroadcast).mockReset();
  });

  afterEach(() => vi.useRealTimers());

  it('끝난 방송이고 시작·끝 시각과 기준점이 다 오면 주기를 멈춘다', async () => {
    vi.mocked(fetchBroadcast).mockResolvedValue(ENDED);
    const { result } = renderHook(() => useBroadcastClock('7'));
    await settle();

    expect(result.current.status).toBe('ended');
    await tick(POLL_MS * 5);

    expect(fetchBroadcast).toHaveBeenCalledTimes(1);
  });

  it('기준점이 아직 없는 끝난 방송은 계속 묻다가, 오면 멈춘다', async () => {
    vi.mocked(fetchBroadcast).mockResolvedValue({ ...ENDED, timelineOriginAt: null });
    renderHook(() => useBroadcastClock('7'));
    await settle();

    await tick(POLL_MS);
    await tick(POLL_MS);
    expect(fetchBroadcast).toHaveBeenCalledTimes(3);

    vi.mocked(fetchBroadcast).mockResolvedValue(ENDED);
    await tick(POLL_MS);
    expect(fetchBroadcast).toHaveBeenCalledTimes(4);
    await tick(POLL_MS * 3);
    expect(fetchBroadcast).toHaveBeenCalledTimes(4);
  });

  it('방송 중이면 계속 묻고, 끝나면 멈춘다', async () => {
    vi.mocked(fetchBroadcast).mockResolvedValue(ROW);
    const { result } = renderHook(() => useBroadcastClock('7'));
    await settle();
    await tick(POLL_MS);
    expect(fetchBroadcast).toHaveBeenCalledTimes(2);

    vi.mocked(fetchBroadcast).mockResolvedValue(ENDED);
    await tick(POLL_MS);
    expect(result.current.status).toBe('ended');
    await tick(POLL_MS * 3);

    expect(fetchBroadcast).toHaveBeenCalledTimes(3);
  });

  it('첫 읽기는 다른 화면이 읽어 둔 줄을 써도 되고, 주기는 기억을 건너뛴다', async () => {
    vi.mocked(fetchBroadcast).mockResolvedValue(ROW);
    renderHook(() => useBroadcastClock('7'));
    await settle();
    await tick(POLL_MS);

    const [first, second] = vi.mocked(fetchBroadcast).mock.calls;
    expect(first?.[2]).toEqual({ fresh: false });
    expect(second?.[2]).toEqual({ fresh: true });
  });

  it('방송 번호가 바뀌면 새 방송은 처음부터 다시 묻는다', async () => {
    vi.mocked(fetchBroadcast).mockResolvedValue(ENDED);
    const { rerender } = renderHook(({ id }) => useBroadcastClock(id), {
      initialProps: { id: '7' },
    });
    await settle();
    await tick(POLL_MS * 2);
    expect(fetchBroadcast).toHaveBeenCalledTimes(1);

    vi.mocked(fetchBroadcast).mockResolvedValue({ ...ROW, streamId: '8' });
    rerender({ id: '8' });
    await settle();
    await tick(POLL_MS);

    expect(fetchBroadcast).toHaveBeenCalledTimes(3);
  });
});
