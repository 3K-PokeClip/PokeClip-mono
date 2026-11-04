import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createRecipe, fetchBroadcast, type BroadcastRow, type JumpCard } from '@/api/clipEditor';
import { lookupRecordingSpans } from '@/api/mediaPlayback';
import { cardFromList, recordingOf } from './prefetchCardEditor';
import { outputsFor } from './recipeLook';
import { CardDraftNotReadyError, cardDraftDocument, saveCardDraft } from './saveCardDraft';
import { initialRecipe } from './useClipEditorMockState';

vi.mock('@/api/clipEditor', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/clipEditor')>()),
  createRecipe: vi.fn(),
  fetchBroadcast: vi.fn(),
}));
vi.mock('@/api/mediaPlayback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/mediaPlayback')>()),
  lookupRecordingSpans: vi.fn(),
}));
vi.mock('./prefetchCardEditor', () => ({ cardFromList: vi.fn(), recordingOf: vi.fn() }));

const BASE = Date.parse('2026-11-04T01:00:00Z');

describe('cardDraftDocument: 카드에서 바로 저장하는 편집본', () => {
  it('편집기를 카드에서 처음 연 것과 같다: 카드 구간 · 처음 모양 · 첫 소리 트랙', () => {
    const doc = cardDraftDocument('S-1', BASE, { startMs: 40_000, endMs: 100_000 });
    expect(doc).toEqual({
      schemaVersion: 2,
      streamId: 'S-1',
      cut: { inAtMs: BASE + 40_000, outAtMs: BASE + 100_000 },
      outputs: outputsFor(initialRecipe({ startSeconds: 40, endSeconds: 100 }), null),
      audio: { tracks: [{ trackId: 0, gain: 1 }] },
    });
    // 자막은 편집기가 만들지 않는다: 칸을 뺀다(계약6 생략 = 자막 없음)
    expect('subtitles' in doc).toBe(false);
  });

  it('5초보다 짧으면 뒤를 늘리고 3분보다 길면 뒤를 자른다 — 저장 문이 그 밖을 거절한다', () => {
    expect(cardDraftDocument('S-1', BASE, { startMs: 0, endMs: 2_000 }).cut).toEqual({
      inAtMs: BASE,
      outAtMs: BASE + 5_000,
    });
    expect(cardDraftDocument('S-1', BASE, { startMs: 0, endMs: 600_000 }).cut).toEqual({
      inAtMs: BASE,
      outAtMs: BASE + 180_000,
    });
  });
});

describe('saveCardDraft', () => {
  const card = { id: 23, window: { startMs: 40_000, endMs: 100_000 } } as unknown as JumpCard;
  /** 서버 기준점이 있는 방송(지금 서버) — 기준점 = BASE */
  const broadcast = {
    streamId: 'S-1',
    startedAt: '2026-11-04T00:59:30Z',
    endedAt: null,
    timelineOriginAt: '2026-11-04T01:00:00Z',
  } as BroadcastRow;
  /** 서버 기준점이 없는 방송(조각이 아직 없거나 옛 서버) */
  const noOrigin = { ...broadcast, timelineOriginAt: null } as BroadcastRow;
  const ended = { ...noOrigin, endedAt: '2026-11-04T02:00:00Z' } as BroadcastRow;
  const startedAtMs = Date.parse('2026-11-04T00:59:30Z');
  const savedCut = () => vi.mocked(createRecipe).mock.calls[0]![1].cut;

  beforeEach(() => {
    vi.mocked(createRecipe)
      .mockReset()
      .mockResolvedValue({ id: 31 } as never);
    vi.mocked(lookupRecordingSpans).mockReset();
    vi.mocked(cardFromList).mockResolvedValue(card);
    vi.mocked(fetchBroadcast).mockResolvedValue(broadcast);
    vi.mocked(recordingOf).mockResolvedValue(null);
  });

  it('카드 번호를 출처로 실어 편집본을 만든다(서버 기준점이 있으면 녹화가 없어도 그 기준점)', async () => {
    await expect(saveCardDraft('S-1', '23')).resolves.toEqual({ id: 31 });
    const [streamId, , sourceCardId] = vi.mocked(createRecipe).mock.calls[0]!;
    expect(streamId).toBe('S-1');
    expect(sourceCardId).toBe('23');
    expect(savedCut()).toEqual({ inAtMs: BASE + 40_000, outAtMs: BASE + 100_000 });
  });

  it('기준점 없는 방송 줄만 새로 읽고, 기준점이 찬 줄은 기억해 둔 것을 쓴다', async () => {
    await saveCardDraft('S-1', '23');
    const [streamId, , options] = vi.mocked(fetchBroadcast).mock.lastCall!;
    expect(streamId).toBe('S-1');
    const fresh = options?.fresh as (row: BroadcastRow) => boolean;
    // 기준점 없는 줄을 20초 동안 다시 쓰는 사이 녹화가 생기면 녹화 시작이 기준점이 되어 서버 기준점과 어긋난다
    expect(fresh(noOrigin)).toBe(true);
    // 기준점이 찬 줄은 더 바뀌지 않는다 — 끝난 옛 방송을 다시 읽으려 지난 방송 목록을 여러 쪽 넘기지 않는다
    expect(fresh(broadcast)).toBe(false);
  });

  it('서버 기준점이 있으면 녹화를 묻지 않는다 — 녹화 읽기 실패(출입증 등)가 저장을 막지 않게', async () => {
    vi.mocked(recordingOf).mockRejectedValue(new Error('access failed'));
    await expect(saveCardDraft('S-1', '23')).resolves.toEqual({ id: 31 });
    expect(recordingOf).not.toHaveBeenCalled();
    expect(savedCut()).toEqual({ inAtMs: BASE + 40_000, outAtMs: BASE + 100_000 });
  });

  it('카드가 없거나 시작 안 한 방송이면 저장하지 않고 던진다', async () => {
    vi.mocked(cardFromList).mockResolvedValueOnce(null);
    await expect(saveCardDraft('S-1', '99')).rejects.toThrow();
    vi.mocked(fetchBroadcast).mockResolvedValueOnce({ ...broadcast, startedAt: null });
    await expect(saveCardDraft('S-1', '23')).rejects.toThrow();
    expect(createRecipe).not.toHaveBeenCalled();
  });

  it('서버 기준점이 없는 끝난 방송에서 녹화를 못 읽으면 저장하지 않는다', async () => {
    vi.mocked(fetchBroadcast).mockResolvedValue(ended);
    vi.mocked(recordingOf).mockRejectedValueOnce(new Error('503'));
    await expect(saveCardDraft('S-1', '23')).rejects.toThrow('503');
    expect(createRecipe).not.toHaveBeenCalled();
  });

  it('방송 중 서버 기준점도 녹화도 없으면(조각 0개) 기다리게 한다 — 방송 시작으로 물러난 구간을 저장하지 않는다', async () => {
    vi.mocked(fetchBroadcast).mockResolvedValue(noOrigin);
    await expect(saveCardDraft('S-1', '23')).rejects.toBeInstanceOf(CardDraftNotReadyError);
    expect(createRecipe).not.toHaveBeenCalled();
  });

  it('방송 중 서버 기준점이 없으면 녹화가 있어도 기다리게 한다 — 서버 기준점(pdt − pts)은 녹화 시작과 다를 수 있다', async () => {
    vi.mocked(fetchBroadcast).mockResolvedValue(noOrigin);
    vi.mocked(recordingOf).mockResolvedValue({
      startMs: BASE + 5_000,
      durationSeconds: 600,
      pieces: [{ fromSeconds: 0, toSeconds: 600 }],
    });
    await expect(saveCardDraft('S-1', '23')).rejects.toBeInstanceOf(CardDraftNotReadyError);
    expect(createRecipe).not.toHaveBeenCalled();
  });

  it('끝난 방송에서 녹화 목록 묻기가 실패하면(recordingOf는 null로 접는다) 저장하지 않는다', async () => {
    vi.mocked(fetchBroadcast).mockResolvedValue(ended);
    vi.mocked(lookupRecordingSpans).mockResolvedValue(null);
    await expect(saveCardDraft('S-1', '23')).rejects.toThrow('recording lookup failed');
    expect(createRecipe).not.toHaveBeenCalled();
  });

  it('끝난 방송에 녹화가 정말 없으면 방송 시작이 기준점이다(편집기도 그렇게 연다)', async () => {
    vi.mocked(fetchBroadcast).mockResolvedValue(ended);
    vi.mocked(lookupRecordingSpans).mockResolvedValue([]);
    await saveCardDraft('S-1', '23');
    expect(savedCut()).toEqual({ inAtMs: startedAtMs + 40_000, outAtMs: startedAtMs + 100_000 });
  });

  it('기억해 둔 「녹화 없음」이 낡았으면 방금 물은 목록의 녹화 시작이 기준점이다', async () => {
    vi.mocked(fetchBroadcast).mockResolvedValue(ended);
    vi.mocked(lookupRecordingSpans).mockResolvedValue([
      { startMs: BASE + 5_000, durationSeconds: 600 },
    ]);
    await saveCardDraft('S-1', '23');
    expect(savedCut()).toEqual({ inAtMs: BASE + 45_000, outAtMs: BASE + 105_000 });
  });

  it('저장을 시작한 세션이 바뀌었으면 서버에 보내지 않는다 — 다른 계정 이름으로 편집본이 생기지 않게', async () => {
    await expect(saveCardDraft('S-1', '23', { stillCurrent: () => false })).rejects.toThrow(
      'session changed',
    );
    expect(createRecipe).not.toHaveBeenCalled();
  });
});
