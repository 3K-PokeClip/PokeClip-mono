import { act, render, renderHook, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { withToastProvider } from '@/test/testProviders';
import type { EditorPlaylist, PlaylistSegment } from '../source/editorPlaylist';
import type { HeadCache, HeadEntry } from '../source/fileHeadProbe';
import type { EndpointRange } from '../source/rangeEndpoints';
import type { EditorPick } from '../source/windowPlacement';

// 편집기 저장 · 영상 만들기 앞의 컷 판정(계획 prw2 5절 E4b-3 · 처분 PK1). 저장을 누르면 목록을 한 번 다시 받고 그 목록으로
// savable 을 본다. 막히면 까닭을 알리고, 다시 받으면 풀릴 수 있는 까닭(② 머리 · 목록)에는 「다시 시도」 단추를 단다(cc L2).
// 「다시 시도」 는 처음 받은 컷 · 이어 갈 일을 다시 쓰지 않고 그 순간의 동작(again — 지금 레시피로 저장 · 영상 만들기)을 다시
// 부른다(E4b-3 r1 [cc·cx] HIGH).

vi.mock('../source/editorWindow', () => ({ ensureWindowHeads: vi.fn(async () => undefined) }));
const { ensureWindowHeads } = await import('../source/editorWindow');
const { useSaveCut } = await import('./useSaveCut');

const BASE = 1_000_000;

function row(seq: number): PlaylistSegment {
  return {
    seq,
    uri: `https://cdn.test/dvr/s/seg/${seq}.m4s`,
    mapUri: 'https://cdn.test/dvr/s/init.mp4',
    pdtMs: BASE + (seq - 1) * 4_000,
    durationMs: 4_000,
    timing: null,
    gap: false,
  };
}
const ROWS = [row(1), row(2), row(3)];
const LIST: EditorPlaylist = { runs: [ROWS], rows: ROWS };

/** seq 2 의 고름 — D3 50.2 · 머리 m 0.2 면 원점 50.0 */
const PICK: EditorPick = {
  seq: 2,
  pts: 50.5,
  dur: 0.04,
  videoDts: 50.2,
  pdtMs: BASE + 4_000,
  durationMs: 4_000,
  timing: null,
};
const PICKED: EndpointRange = {
  start: { kind: 'pick', pick: PICK },
  end: { kind: 'exact', ms: BASE + 12_000 },
};

function headsOf(entries: Record<number, HeadEntry>): HeadCache & { map: Map<number, HeadEntry> } {
  const map = new Map(Object.entries(entries).map(([seq, entry]) => [Number(seq), entry]));
  return { map, get: (seq) => map.get(seq), subscribe: () => () => {} };
}
const READY: HeadEntry = { state: 'ready', head: { hd: 0, hp: 0, videoDts: 0.2 } };

function mount(heads: HeadCache, reloadList: () => Promise<EditorPlaylist | null>) {
  const source = {
    list: { mode: 'live' as const, url: 'https://cdn.test/dvr/s/index.m3u8', playlist: LIST },
    reloadList,
  };
  return renderHook(
    () => useSaveCut({ base: BASE, heads, streamId: 'round-a', mediaKey: 'key-1', source }),
    { wrapper: withToastProvider },
  );
}

/** 알림 자리(ToastProvider)는 남기고 훅을 쓰는 화면만 내릴 수 있게 연다 */
function mountSwitchable(heads: HeadCache, reloadList: () => Promise<EditorPlaylist | null>) {
  const source = {
    list: { mode: 'live' as const, url: 'https://cdn.test/dvr/s/index.m3u8', playlist: LIST },
    reloadList,
  };
  let latest: ReturnType<typeof useSaveCut> | null = null;
  function Screen() {
    latest = useSaveCut({ base: BASE, heads, streamId: 'round-a', mediaKey: 'key-1', source });
    return null;
  }
  const view = render(<Screen />, { wrapper: withToastProvider });
  return {
    cutFor: () => latest!,
    leave: () => view.rerender(<></>),
  };
}

describe('useSaveCut', () => {
  beforeEach(() => vi.clearAllMocks());

  it('PK1 — 목록을 한 번 다시 받아 그 목록으로 셈한 컷으로 이어 간다', async () => {
    const reloadList = vi.fn(async () => LIST);
    const proceed = vi.fn();
    const { result } = mount(headsOf({ 2: READY }), reloadList);

    act(() => result.current(PICKED, proceed, vi.fn(), vi.fn()));

    await waitFor(() =>
      expect(proceed).toHaveBeenCalledWith({ inMs: BASE + 4_500, outMs: BASE + 12_000 }),
    );
    expect(reloadList).toHaveBeenCalledOnce();
  });

  it('PK1 — 다시 받기가 실패하면 들고 있는 목록으로 맞추지 않고 막는다: 「다시 시도」 는 그 순간의 동작을 다시 부른다', async () => {
    const user = userEvent.setup();
    const reloadList = vi.fn<() => Promise<EditorPlaylist | null>>(async () => null);
    const proceed = vi.fn();
    const refused = vi.fn();
    const { result } = mount(headsOf({ 2: READY }), reloadList);
    // 부르는 쪽(저장 · 영상 만들기)이 again 으로 자기를 다시 부른다 — 그때의 끝점 · 이어 갈 일로
    const again = vi.fn(() => result.current(PICKED, proceed, refused, again));

    act(() => result.current(PICKED, proceed, refused, again));
    await screen.findByText('목록을 다시 받지 못했어요');
    reloadList.mockResolvedValue(LIST);
    await user.click(screen.getByRole('button', { name: '다시 시도' }));

    await waitFor(() => expect(proceed).toHaveBeenCalledOnce());
    expect(again).toHaveBeenCalledOnce();
    expect(refused).toHaveBeenCalledOnce();
    expect(reloadList).toHaveBeenCalledTimes(2);
  });

  it('r1 HIGH — 「다시 시도」 는 처음 받은 이어 갈 일(proceed)을 다시 쓰지 않는다: 그 사이 바뀐 화면 · 저장이 덮이지 않는다', async () => {
    const user = userEvent.setup();
    const reloadList = vi.fn<() => Promise<EditorPlaylist | null>>(async () => null);
    const firstProceed = vi.fn();
    const again = vi.fn();
    const { result } = mount(headsOf({ 2: READY }), reloadList);

    act(() => result.current(PICKED, firstProceed, vi.fn(), again));
    await screen.findByText('목록을 다시 받지 못했어요');
    reloadList.mockResolvedValue(LIST);
    await user.click(screen.getByRole('button', { name: '다시 시도' }));

    await waitFor(() => expect(again).toHaveBeenCalledOnce());
    expect(firstProceed).not.toHaveBeenCalled();
    // 다시 판정은 again 이 부른 동작이 한다 — 옛 끝점으로 혼자 다시 받지 않는다
    expect(reloadList).toHaveBeenCalledOnce();
  });

  it('r1 HIGH — 새 판정이 시작되면 앞의 거절 알림을 닫는다: 옛 「다시 시도」 가 남지 않는다', async () => {
    const reloadList = vi.fn<() => Promise<EditorPlaylist | null>>(async () => null);
    const { result } = mount(headsOf({ 2: READY }), reloadList);

    act(() => result.current(PICKED, vi.fn(), vi.fn(), vi.fn()));
    await screen.findByText('목록을 다시 받지 못했어요');
    reloadList.mockResolvedValue(LIST);
    const proceed = vi.fn();
    act(() => result.current(PICKED, proceed, vi.fn(), vi.fn()));

    await waitFor(() => expect(proceed).toHaveBeenCalledOnce());
    await waitFor(() => expect(screen.queryByText('목록을 다시 받지 못했어요')).toBeNull());
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
  });

  it('cc L1 — 판정이 끝나기 전 새 판정은 시작하지 않고 바로 막는다(저장 · 영상 만들기가 겹치지 않는다)', async () => {
    let answer: (list: EditorPlaylist) => void = () => undefined;
    const reloadList = vi.fn(
      () => new Promise<EditorPlaylist | null>((resolve) => (answer = resolve)),
    );
    const firstProceed = vi.fn();
    const secondProceed = vi.fn();
    const secondRefused = vi.fn();
    const { result } = mount(headsOf({ 2: READY }), reloadList);

    act(() => result.current(PICKED, firstProceed, vi.fn(), vi.fn()));
    act(() => result.current(PICKED, secondProceed, secondRefused, vi.fn()));
    await act(async () => answer(LIST));

    await waitFor(() => expect(firstProceed).toHaveBeenCalledOnce());
    expect(secondRefused).toHaveBeenCalledOnce();
    expect(secondProceed).not.toHaveBeenCalled();
    expect(reloadList).toHaveBeenCalledOnce();
  });

  it('cc L2 — 머리 판독이 실패한 끝점은 「다시 시도」 가 그 조각 머리를 다시 읽고 나서 그 순간의 동작을 다시 부른다', async () => {
    const user = userEvent.setup();
    const heads = headsOf({ 2: { state: 'failed' } });
    vi.mocked(ensureWindowHeads).mockImplementation(async () => {
      heads.map.set(2, READY);
    });
    const proceed = vi.fn();
    const { result } = mount(heads, async () => LIST);
    const again = vi.fn(() => result.current(PICKED, proceed, vi.fn(), again));

    act(() => result.current(PICKED, proceed, vi.fn(), again));
    await screen.findByText('저장에 필요한 녹화 정보를 받지 못했어요');
    await user.click(screen.getByRole('button', { name: '다시 시도' }));

    await waitFor(() => expect(proceed).toHaveBeenCalledOnce());
    expect(vi.mocked(ensureWindowHeads).mock.calls[0]!.slice(0, 3)).toEqual([
      'round-a',
      'key-1',
      [ROWS[1]],
    ]);
  });

  it('cc L1 — 「다시 시도」 로 다시 부른 판정도 끝나기 전에는 다른 판정(영상 만들기)을 막는다', async () => {
    const user = userEvent.setup();
    let answer: (list: EditorPlaylist | null) => void = () => undefined;
    const reloadList = vi.fn(
      () => new Promise<EditorPlaylist | null>((resolve) => (answer = resolve)),
    );
    const { result } = mount(headsOf({ 2: READY }), reloadList);
    const again = vi.fn(() => result.current(PICKED, vi.fn(), vi.fn(), again));
    act(() => result.current(PICKED, vi.fn(), vi.fn(), again));
    await act(async () => answer(null));
    await user.click(await screen.findByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(reloadList).toHaveBeenCalledTimes(2));

    const uploadRefused = vi.fn();
    act(() => result.current(PICKED, vi.fn(), uploadRefused, vi.fn()));

    expect(uploadRefused).toHaveBeenCalledOnce();
    expect(reloadList).toHaveBeenCalledTimes(2);
  });

  it('cc M — 판정 중 화면을 떠나면 이어 가지도 막지도 알리지도 않는다', async () => {
    let answer: (list: EditorPlaylist) => void = () => undefined;
    const reloadList = vi.fn(
      () => new Promise<EditorPlaylist | null>((resolve) => (answer = resolve)),
    );
    const proceed = vi.fn();
    const refused = vi.fn();
    const view = mountSwitchable(headsOf({}), reloadList);

    act(() => view.cutFor()(PICKED, proceed, refused, vi.fn()));
    view.leave();
    await act(async () => answer(LIST));

    expect(proceed).not.toHaveBeenCalled();
    expect(refused).not.toHaveBeenCalled();
    expect(screen.queryByText('저장에 필요한 녹화 정보를 받지 못했어요')).toBeNull();
  });

  it('cc M — 화면을 떠나면 떠 있던 거절 알림을 닫고, 다시 읽던 ② 머리 요청을 끊는다', async () => {
    const user = userEvent.setup();
    let signal: AbortSignal | undefined;
    vi.mocked(ensureWindowHeads).mockImplementation(async (_s, _k, _rows, given) => {
      signal = given;
      await new Promise(() => undefined);
    });
    const again = vi.fn();
    const view = mountSwitchable(headsOf({ 2: { state: 'failed' } }), async () => LIST);

    act(() => view.cutFor()(PICKED, vi.fn(), vi.fn(), again));
    await user.click(await screen.findByRole('button', { name: '다시 시도' }));
    view.leave();

    await waitFor(() =>
      expect(screen.queryByText('저장에 필요한 녹화 정보를 받지 못했어요')).toBeNull(),
    );
    expect(signal?.aborted).toBe(true);
    expect(again).not.toHaveBeenCalled();
  });

  it('카드의 estimate 끝은 다시 시도 없이 「범위 끝을 직접 정해 주세요」 다', async () => {
    const proceed = vi.fn();
    const { result } = mount(headsOf({}), async () => LIST);
    const estimate: EndpointRange = {
      start: { kind: 'estimate', ms: BASE },
      end: { kind: 'estimate', ms: BASE + 12_000 },
    };

    act(() => result.current(estimate, proceed, vi.fn(), vi.fn()));

    await screen.findByText('이 카드는 범위 끝을 직접 정해 주세요');
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
    expect(proceed).not.toHaveBeenCalled();
  });

  it('outsideList — 끝점이 방송 중 편집 범위 밖이 됐으면 다시 정하라고 알린다', async () => {
    const proceed = vi.fn();
    const { result } = mount(headsOf({ 2: READY }), async () => ({
      runs: [[ROWS[2]!]],
      rows: [ROWS[2]!],
    }));

    act(() => result.current(PICKED, proceed, vi.fn(), vi.fn()));

    await screen.findByText(
      '이 끝점은 방송 중 편집 범위(최근 1시간) 밖이 됐어요 · 다시 정해 주세요',
    );
    expect(proceed).not.toHaveBeenCalled();
  });
});
