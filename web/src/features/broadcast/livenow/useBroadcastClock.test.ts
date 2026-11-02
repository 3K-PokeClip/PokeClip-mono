import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchBroadcast, type BroadcastRow } from '@/api/clipEditor';
import { useBroadcastClock } from './useBroadcastClock';

vi.mock('@/api/clipEditor', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/clipEditor')>()),
  fetchBroadcast: vi.fn(),
}));

// 방송 시계의 주기: 끝난 방송이고 시각이 다 왔으면 1분으로 늦춘다. 끝난 뒤에 늦게 채워지는 기준점은 올 때까지 빠르게 묻는다.
// 멈추지는 않는다: 서버가 끝 시각을 고치고, 권한이 회수되면 이 주기가 알려야 404로 간다

const POLL_MS = 30_000;
const SETTLED_POLL_MS = 60_000;
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

  it('끝난 방송이고 시작·끝 시각과 기준점이 다 오면 주기를 1분으로 늦춘다', async () => {
    vi.mocked(fetchBroadcast).mockResolvedValue(ENDED);
    const { result } = renderHook(() => useBroadcastClock('7'));
    await settle();

    expect(result.current.status).toBe('ended');
    await tick(POLL_MS);
    expect(fetchBroadcast).toHaveBeenCalledTimes(1);

    await tick(POLL_MS);
    expect(fetchBroadcast).toHaveBeenCalledTimes(2);
    await tick(SETTLED_POLL_MS * 2);
    expect(fetchBroadcast).toHaveBeenCalledTimes(4);
  });

  it('시각이 다 찬 끝난 방송도 고쳐진 끝 시각을 받는다: 무음 종료 뒤 늦게 온 종료 편지가 서버의 끝 시각을 고친다', async () => {
    vi.mocked(fetchBroadcast).mockResolvedValue(ENDED);
    const { result } = renderHook(() => useBroadcastClock('7'));
    await settle();
    expect(result.current.endedAt).toBe(Date.parse('2026-10-31T02:00:00Z'));

    vi.mocked(fetchBroadcast).mockResolvedValue({ ...ENDED, endedAt: '2026-10-31T02:07:00Z' });
    await tick(SETTLED_POLL_MS);

    expect(result.current.endedAt).toBe(Date.parse('2026-10-31T02:07:00Z'));
  });

  it('시각이 다 찬 끝난 방송도 명부에서 사라지면 알린다: 권한이 회수되면 상세 화면이 404로 간다', async () => {
    vi.mocked(fetchBroadcast).mockResolvedValue(ENDED);
    const { result } = renderHook(() => useBroadcastClock('7'));
    await settle();
    expect(result.current.missingStreamId).toBeNull();

    vi.mocked(fetchBroadcast).mockResolvedValue(null);
    await tick(SETTLED_POLL_MS);

    expect(result.current.missingStreamId).toBe('7');
  });

  it('기준점이 아직 없는 끝난 방송은 빠르게 묻다가, 오면 늦춘다', async () => {
    vi.mocked(fetchBroadcast).mockResolvedValue({ ...ENDED, timelineOriginAt: null });
    renderHook(() => useBroadcastClock('7'));
    await settle();

    await tick(POLL_MS);
    await tick(POLL_MS);
    expect(fetchBroadcast).toHaveBeenCalledTimes(3);

    vi.mocked(fetchBroadcast).mockResolvedValue(ENDED);
    await tick(POLL_MS);
    expect(fetchBroadcast).toHaveBeenCalledTimes(4);
    await tick(POLL_MS);
    expect(fetchBroadcast).toHaveBeenCalledTimes(4);
    await tick(POLL_MS);
    expect(fetchBroadcast).toHaveBeenCalledTimes(5);
  });

  it('방송 중이면 빠르게 묻고, 끝나면 늦춘다', async () => {
    vi.mocked(fetchBroadcast).mockResolvedValue(ROW);
    const { result } = renderHook(() => useBroadcastClock('7'));
    await settle();
    await tick(POLL_MS);
    expect(fetchBroadcast).toHaveBeenCalledTimes(2);

    vi.mocked(fetchBroadcast).mockResolvedValue(ENDED);
    await tick(POLL_MS);
    expect(result.current.status).toBe('ended');
    await tick(POLL_MS);
    expect(fetchBroadcast).toHaveBeenCalledTimes(3);

    await tick(POLL_MS);
    expect(fetchBroadcast).toHaveBeenCalledTimes(4);
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
    await tick(POLL_MS);
    expect(fetchBroadcast).toHaveBeenCalledTimes(1);

    vi.mocked(fetchBroadcast).mockResolvedValue({ ...ROW, streamId: '8' });
    rerender({ id: '8' });
    await settle();
    await tick(POLL_MS);

    expect(fetchBroadcast).toHaveBeenCalledTimes(3);
  });
});
