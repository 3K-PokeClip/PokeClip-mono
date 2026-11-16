import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { CUT_MAX_MS, CUT_MIN_MS, createRecipe, type JumpCard } from '@/api/clipEditor';
import { CardNeedsEditorError } from './cardDraftErrors';
import { cardFromList } from './prefetchCardEditor';
import { outputsFor } from './recipeLook';
import { cardDraftDocument, saveCardDraft } from './saveCardDraft';
import { initialRecipe } from './useClipEditorMockState';

vi.mock('@/api/clipEditor', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/clipEditor')>()),
  createRecipe: vi.fn(),
}));
vi.mock('./prefetchCardEditor', () => ({ cardFromList: vi.fn() }));

// 카드 바로 저장(POK-473 E7 · 설계 prw2 3.4) — 편집기를 열지 않으니 고름 · 머리가 없다. clip 이 준 창 두 끝(playerWindow)만
// 저장 값이고, 칸이 없거나 null 이면 「편집기에서 열기」(CardNeedsEditorError)로 대신한다. 녹화 재생 서버(/playback)는 부르지 않는다.

const PDT = Date.parse('2026-11-04T01:00:00Z');

describe('cardDraftDocument: 카드에서 바로 저장하는 편집본', () => {
  it('편집기를 카드에서 처음 연 것과 같다: 받은 컷 · 처음 모양 · 첫 소리 트랙', () => {
    const doc = cardDraftDocument('S-1', { inMs: PDT + 40_000, outMs: PDT + 100_000 });
    expect(doc).toEqual({
      schemaVersion: 2,
      streamId: 'S-1',
      cut: { inAtMs: PDT + 40_000, outAtMs: PDT + 100_000 },
      outputs: outputsFor(initialRecipe({ startSeconds: 40, endSeconds: 100 }), null),
      audio: { tracks: [{ trackId: 0, gain: 1 }] },
    });
    // 자막은 편집기가 만들지 않는다: 칸을 뺀다(계약6 생략 = 자막 없음)
    expect('subtitles' in doc).toBe(false);
  });
});

describe('saveCardDraft', () => {
  /** 판 NULL 카드 — clip 의 창 두 끝은 카드 창(기준점 + window)과 수 초 다르다(래칫) */
  const PLAYER_WINDOW = { startMs: PDT + 43_217, endMs: PDT + 103_901 };
  const cardWith = (playerWindow: unknown) =>
    ({
      id: 23,
      window: { startMs: 40_000, endMs: 100_000 },
      ...(playerWindow === undefined ? {} : { playerWindow }),
    }) as unknown as JumpCard;
  const savedCut = () => vi.mocked(createRecipe).mock.calls[0]![1].cut;
  let fetchSpy: MockInstance<typeof fetch>;

  beforeEach(() => {
    vi.mocked(createRecipe)
      .mockReset()
      .mockResolvedValue({ id: 31 } as never);
    vi.mocked(cardFromList).mockResolvedValue(cardWith(PLAYER_WINDOW));
    fetchSpy = vi.spyOn(globalThis, 'fetch');
  });

  afterEach(() => {
    fetchSpy.mockRestore();
  });

  it('clip 이 준 창 두 끝(playerWindow) 그대로 카드 번호를 출처로 실어 편집본을 만든다', async () => {
    await expect(saveCardDraft('S-1', '23')).resolves.toEqual({ id: 31 });

    const [streamId, , sourceCardId] = vi.mocked(createRecipe).mock.calls[0]!;
    expect(streamId).toBe('S-1');
    expect(sourceCardId).toBe('23');
    expect(savedCut()).toEqual({ inAtMs: PDT + 43_217, outAtMs: PDT + 103_901 });
  });

  it('녹화 재생 서버(/playback)를 부르지 않는다 — 기준점 · 녹화 목록을 묻지 않는다', async () => {
    await saveCardDraft('S-1', '23');

    const urls = fetchSpy.mock.calls.map(([url]) => String(url));
    expect(urls.filter((url) => url.includes('/playback'))).toEqual([]);
  });

  it.each([
    ['null(장면 없음)', null],
    ['칸 없음(옛 clip)', undefined],
  ])(
    'playerWindow %s — 편집기에서 범위를 정하라고 던지고 카드 창으로 대신 저장하지 않는다',
    async (_label, playerWindow) => {
      vi.mocked(cardFromList).mockResolvedValue(cardWith(playerWindow));

      await expect(saveCardDraft('S-1', '23')).rejects.toBeInstanceOf(CardNeedsEditorError);
      expect(createRecipe).not.toHaveBeenCalled();
    },
  );

  it('창 길이가 5–180초 밖이면 몰래 늘리거나 자르지 않고 편집기에서 정하라고 던진다', async () => {
    for (const length of [CUT_MIN_MS - 1, CUT_MAX_MS + 1]) {
      vi.mocked(cardFromList).mockResolvedValue(cardWith({ startMs: PDT, endMs: PDT + length }));
      await expect(saveCardDraft('S-1', '23')).rejects.toBeInstanceOf(CardNeedsEditorError);
    }
    expect(createRecipe).not.toHaveBeenCalled();
  });

  it('카드가 없으면 저장하지 않고 던진다', async () => {
    vi.mocked(cardFromList).mockResolvedValueOnce(null);

    await expect(saveCardDraft('S-1', '99')).rejects.toThrow('card 99 not found');
    expect(createRecipe).not.toHaveBeenCalled();
  });

  it('저장을 시작한 세션이 바뀌었으면 서버에 보내지 않는다 — 다른 계정 이름으로 편집본이 생기지 않게', async () => {
    await expect(saveCardDraft('S-1', '23', { stillCurrent: () => false })).rejects.toThrow(
      'session changed',
    );
    expect(createRecipe).not.toHaveBeenCalled();
  });
});
