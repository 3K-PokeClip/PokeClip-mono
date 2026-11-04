'use client';

import { useCallback, useSyncExternalStore } from 'react';
import type { RecipeSnapshot } from '@/api/clipEditor';
import { saveCardDraft } from '@/features/clips/editor/saveCardDraft';
import { onSessionAdvance } from '@/stores/auth';

// 카드의 「보관함 저장」 상태 — 이 탭에서 저장한 카드만 기억한다. 🔴 새로고침하면 잊는다: 보관함 목록이 출처 카드 번호를 싣지
// 않아(LibraryEntry) 어느 카드가 이미 저장됐는지 서버에서 알 길이 없다. 서버가 그 칸을 실으면 목록으로 채운다(후속).
// 카드가 다시 그려져도(목록 3초마다) · 패널 필터를 바꿔도 남게 컴포넌트 밖에 둔다.

export type CardSaveState = 'idle' | 'saving' | 'saved';

interface Entry {
  state: Exclude<CardSaveState, 'idle'>;
  /** 저장으로 만든 편집본 번호(저장됨일 때) */
  recipeId?: number;
}

const entries = new Map<string, Entry>();
const listeners = new Set<() => void>();

function set(key: string, entry: Entry | null) {
  if (entry === null) entries.delete(key);
  else entries.set(key, entry);
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** 기억한 저장을 비운다. 계정이 바뀔 때 · 시험 사이에 부른다 */
export function forgetCardSaves(): void {
  entries.clear();
  listeners.forEach((listener) => listener());
}
onSessionAdvance(forgetCardSaves);

/**
 * 카드 하나의 저장 상태와 저장 동작. 저장 중 · 저장됨이면 다시 부르지 않는다(두 번 눌러 편집본이 둘 생기지 않게).
 * 실패하면 저장 전으로 돌려 놓고 던진다(알리는 것은 부르는 쪽 몫).
 */
export function useCardSave(streamId: string, cardId: string) {
  const key = `${streamId}:${cardId}`;
  const entry = useSyncExternalStore(
    subscribe,
    () => entries.get(key),
    () => undefined,
  );
  const save = useCallback(async (): Promise<RecipeSnapshot | null> => {
    if (entries.has(key)) return null;
    set(key, { state: 'saving' });
    try {
      const snap = await saveCardDraft(streamId, cardId);
      set(key, { state: 'saved', recipeId: snap.id });
      return snap;
    } catch (error) {
      set(key, null);
      throw error;
    }
  }, [key, streamId, cardId]);
  return { state: (entry?.state ?? 'idle') as CardSaveState, recipeId: entry?.recipeId, save };
}
