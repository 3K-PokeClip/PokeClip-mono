import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RecipeSnapshot } from '@/api/clipEditor';
import { CardNeedsEditorError } from '@/features/clips/editor/cardDraftErrors';
import { saveCardDraft } from '@/features/clips/editor/saveCardDraft';
import { captureSession } from '@/stores/auth';
import { forgetCardSaves, useCardSave } from './cardSave';

// 카드 저장은 시작한 세션에 묶인다(PR #480 리뷰): 계정이 바뀐 뒤 늦게 끝난 저장이 다음 사람 화면에 「저장됨」을 남기지 않게

vi.mock('@/features/clips/editor/saveCardDraft', () => ({ saveCardDraft: vi.fn() }));
vi.mock('@/stores/auth', () => ({ captureSession: vi.fn(), onSessionAdvance: vi.fn() }));

const snapshot = { id: 31 } as RecipeSnapshot;

/** 지금 세션이 그대로인가 — 시험이 바꾼다 */
let current = true;

beforeEach(() => {
  forgetCardSaves();
  current = true;
  vi.mocked(captureSession).mockReturnValue(() => current);
  vi.mocked(saveCardDraft).mockReset();
});

describe('useCardSave', () => {
  it('저장하면 「저장됨」과 편집본 번호를 기억하고, 세션 확인 함수를 saveCardDraft에 넘긴다', async () => {
    vi.mocked(saveCardDraft).mockResolvedValue(snapshot);
    const { result } = renderHook(() => useCardSave('S-1', '23'));
    let returned: RecipeSnapshot | null = null;
    await act(async () => {
      returned = await result.current.save();
    });
    expect(returned).toBe(snapshot);
    expect(result.current.state).toBe('saved');
    expect(result.current.recipeId).toBe(31);
    const [, , options] = vi.mocked(saveCardDraft).mock.calls[0]!;
    expect(options?.stillCurrent?.()).toBe(true);
  });

  it('저장 중에 계정이 바뀌면 끝나도 「저장됨」을 되살리지 않고 알리지도 않는다(null)', async () => {
    let finish: (value: RecipeSnapshot) => void = () => undefined;
    vi.mocked(saveCardDraft).mockReturnValue(new Promise((resolve) => (finish = resolve)));
    const { result } = renderHook(() => useCardSave('S-1', '23'));
    let pending: Promise<RecipeSnapshot | null> = Promise.resolve(null);
    act(() => {
      pending = result.current.save();
    });
    expect(result.current.state).toBe('saving');
    // 계정이 바뀌는 순간: 세션 세대가 오르고(captured 함수가 false) 기억을 비운다(onSessionAdvance → forgetCardSaves)
    current = false;
    act(() => forgetCardSaves());
    let returned: RecipeSnapshot | null = snapshot;
    await act(async () => {
      finish(snapshot);
      returned = await pending;
    });
    expect(returned).toBeNull();
    expect(result.current.state).toBe('idle');
  });

  it('세션이 바뀐 뒤의 실패도 던지지 않는다 — 앞 계정의 실패 알림이 다음 사람 화면에 뜨지 않게', async () => {
    let fail: (error: Error) => void = () => undefined;
    vi.mocked(saveCardDraft).mockReturnValue(new Promise((_, reject) => (fail = reject)));
    const { result } = renderHook(() => useCardSave('S-1', '23'));
    let pending: Promise<RecipeSnapshot | null> = Promise.resolve(null);
    act(() => {
      pending = result.current.save();
    });
    current = false;
    act(() => forgetCardSaves());
    await act(async () => {
      fail(new Error('session changed'));
      await expect(pending).resolves.toBeNull();
    });
    expect(result.current.state).toBe('idle');
  });

  it('같은 세션의 실패는 저장 전으로 돌리고 던진다', async () => {
    vi.mocked(saveCardDraft).mockRejectedValue(new Error('503'));
    const { result } = renderHook(() => useCardSave('S-1', '23'));
    await act(async () => {
      await expect(result.current.save()).rejects.toThrow('503');
    });
    expect(result.current.state).toBe('idle');
  });

  it('「편집기에서 열기」 오류도 저장 전으로 돌리고 종류 그대로 던진다 — 알림을 고르는 것은 부르는 쪽이다', async () => {
    vi.mocked(saveCardDraft).mockRejectedValue(new CardNeedsEditorError());
    const { result } = renderHook(() => useCardSave('S-1', '23'));
    await act(async () => {
      await expect(result.current.save()).rejects.toBeInstanceOf(CardNeedsEditorError);
    });
    expect(result.current.state).toBe('idle');
  });
});
