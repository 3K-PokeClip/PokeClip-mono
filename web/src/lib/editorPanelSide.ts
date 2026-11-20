'use client';

import { useSyncExternalStore } from 'react';

// 편집기 도구 레일 + 패널이 붙는 쪽(시안 1d-a). 미리보기·타임라인은 그대로고 레일과 패널만 좌우로 옮긴다.
//
// 읽고 쓰는 곳이 둘이다 — 편집기 레일 맨 아래 「패널 위치」 버튼(useClipEditorMockState)과 설정 › 편집 환경의
// 「도구 패널 위치」 카드(POK-614). 둘 다 이 모듈을 거쳐 같은 값을 쓰므로 한쪽에서 바꾸면 다른 쪽이 그 값으로 선다.
// 계정 설정 API가 없어 이 브라우저(localStorage)에만 남는다 — 기기마다 따로다. 설정 API가 서면 여기 읽기·쓰기만 갈아끼운다.

export type EditorPanelSide = 'left' | 'right';

/** 아무것도 고른 적 없을 때 — 시안 기본 */
export const DEFAULT_EDITOR_PANEL_SIDE: EditorPanelSide = 'left';

const STORAGE_KEY = 'pc-editor-panel-side';

// 마지막 저장이 실패했을 때(저장소가 막힌 브라우저) 이 탭에서 고른 쪽 — 저장소 값보다 앞선다. 그래서 거기서도 편집기 버튼과
// 설정 카드는 이번 접속 동안 같이 옮겨진다(새로고침하면 사라진다). 이 탭의 저장이 성공하거나, 다른 탭이 저장소를 바꾸면
// (그쪽 저장은 됐다는 뜻이다) 비운다 — 다른 탭의 새 값을 옛 대체값이 가리지 않게(PR #550 2차 리뷰)
let unsavedSide: EditorPanelSide | null = null;

/** 저장에 실패했을 때 편집기 버튼과 설정 카드가 같이 띄우는 안내 */
export const PANEL_SIDE_UNSAVED_TOAST = {
  tone: 'error',
  title: '이 브라우저에 저장하지 못했어요',
  description: '이번 접속 동안만 적용돼요',
} as const;

/** 저장된 쪽. 고른 적 없거나 저장소를 못 읽으면 null */
export function readStoredPanelSide(): EditorPanelSide | null {
  if (typeof window === 'undefined') return null;
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    return raw === 'left' || raw === 'right' ? raw : null;
  } catch {
    return null;
  }
}

const listeners = new Set<() => void>();

/** 고른 쪽을 남긴다. 저장소가 막혀 못 남겼으면 false — 그래도 이번 접속 동안은 그쪽으로 선다 */
export function storePanelSide(side: EditorPanelSide): boolean {
  let saved = true;
  try {
    window.localStorage.setItem(STORAGE_KEY, side);
    unsavedSide = null;
  } catch {
    saved = false;
    unsavedSide = side;
  }
  listeners.forEach((listener) => listener());
  return saved;
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  // 다른 탭에서 바꾼 것(편집기를 옆 탭에 열어 두고 버튼으로 옮긴 경우)도 따라간다
  const onStorage = (event: StorageEvent) => {
    if (event.key !== STORAGE_KEY && event.key !== null) return;
    unsavedSide = null;
    listener();
  };
  window.addEventListener('storage', onStorage);
  return () => {
    listeners.delete(listener);
    window.removeEventListener('storage', onStorage);
  };
}

const snapshot = (): EditorPanelSide =>
  unsavedSide ?? readStoredPanelSide() ?? DEFAULT_EDITOR_PANEL_SIDE;

/** 지금 저장된 쪽 — 설정 카드와 편집기가 같이 쓴다(한쪽에서 바꾸면 다른 쪽이 따라간다, 다른 탭 포함).
 * 서버 렌더와 첫 수화에선 기본값이라 어긋나지 않는다 */
export function useStoredPanelSide(): EditorPanelSide {
  return useSyncExternalStore(subscribe, snapshot, () => DEFAULT_EDITOR_PANEL_SIDE);
}
