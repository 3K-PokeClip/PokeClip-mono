import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createRecipe, fetchBroadcast, type BroadcastRow, type JumpCard } from '@/api/clipEditor';
import { cardFromList, recordingOf } from './prefetchCardEditor';
import { outputsFor } from './recipeLook';
import { cardDraftDocument, saveCardDraft } from './saveCardDraft';
import { initialRecipe } from './useClipEditorMockState';

vi.mock('@/api/clipEditor', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/clipEditor')>()),
  createRecipe: vi.fn(),
  fetchBroadcast: vi.fn(),
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
  const broadcast = {
    streamId: 'S-1',
    startedAt: '2026-11-04T01:00:00Z',
    endedAt: null,
  } as BroadcastRow;

  beforeEach(() => {
    vi.mocked(createRecipe).mockReset();
    vi.mocked(cardFromList).mockResolvedValue(card);
    vi.mocked(fetchBroadcast).mockResolvedValue(broadcast);
    vi.mocked(recordingOf).mockResolvedValue(null);
  });

  it('카드 번호를 출처로 실어 편집본을 만든다', async () => {
    vi.mocked(createRecipe).mockResolvedValue({ id: 31 } as never);
    await expect(saveCardDraft('S-1', '23')).resolves.toEqual({ id: 31 });
    const [streamId, doc, sourceCardId] = vi.mocked(createRecipe).mock.calls[0]!;
    expect(streamId).toBe('S-1');
    expect(sourceCardId).toBe('23');
    expect(doc.cut).toEqual({ inAtMs: BASE + 40_000, outAtMs: BASE + 100_000 });
  });

  it('카드가 없거나 시작 안 한 방송이면 저장하지 않고 던진다', async () => {
    vi.mocked(cardFromList).mockResolvedValueOnce(null);
    await expect(saveCardDraft('S-1', '99')).rejects.toThrow();
    vi.mocked(fetchBroadcast).mockResolvedValueOnce({ ...broadcast, startedAt: null });
    await expect(saveCardDraft('S-1', '23')).rejects.toThrow();
    expect(createRecipe).not.toHaveBeenCalled();
  });

  it('녹화를 못 읽으면 저장하지 않는다 — 기준점이 물러나 구간이 어긋난다', async () => {
    vi.mocked(recordingOf).mockRejectedValueOnce(new Error('503'));
    await expect(saveCardDraft('S-1', '23')).rejects.toThrow('503');
    expect(createRecipe).not.toHaveBeenCalled();
  });
});
