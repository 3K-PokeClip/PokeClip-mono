'use client';

import { useEffect } from 'react';
import { create } from 'zustand';

// 인증 토큰 스토어 (POK-101) — 백엔드는 토큰을 JSON 바디로만 주고받는 SPA 계약이라
// (refresh도 본문으로만 — AuthController 주석) 쿠키가 없다. 저장 분담:
//   access(30분)  → 메모리 전용. XSS로 localStorage가 읽혀도 지속 탈취 면을 줄인다.
//   refresh(14일) → localStorage. XSS 노출은 트레이드오프로 인정한다 — 회전식이라
//                   탈취 토큰이 한 번이라도 재사용되면 서버가 도난으로 보고 전 세션을 끊는다.
// 영속화는 onboarding 스토어의 수동 localStorage 패턴을 따른다 (zustand/persist 선례 없음).
// 초기값은 항상 정적 기본값 — 모듈 스코프에서 localStorage를 읽으면 서버 HTML과
// 클라 첫 렌더가 어긋난다.

const STORAGE_KEY = 'pc-auth';

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
}

interface AuthState {
  /** API 호출용 30분 JWT — 새로고침하면 사라지고 refresh 회전으로 다시 받는다. */
  accessToken: string | null;
  refreshToken: string | null;
  /** localStorage 읽기 완료 — 가드·역가드는 이 값이 서기 전엔 판단하지 않는다. */
  hydrated: boolean;
  hydrate: () => void;
  setTokens: (pair: TokenPair) => void;
  clearTokens: () => void;
}

function readStoredRefreshToken(): string | null {
  if (typeof window === 'undefined') return null;
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null) return null;
    const token = (parsed as Record<string, unknown>).refreshToken;
    return typeof token === 'string' && token !== '' ? token : null;
  } catch {
    /* 손상 JSON·프라이빗 모드 — 비로그인으로 진행 */
    return null;
  }
}

function persistRefreshToken(refreshToken: string | null) {
  try {
    if (refreshToken === null) window.localStorage.removeItem(STORAGE_KEY);
    else window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ v: 1, refreshToken }));
  } catch {
    /* 저장 실패는 무시 — 새로고침 시 로그인이 다시 필요해지는 것까지만 감수한다 */
  }
}

/**
 * 다른 탭이 회전·로그아웃한 refresh 토큰을 이 탭에 반영한다. storage 이벤트는
 * 변경을 만든 탭에서는 발화하지 않으므로 persist 루프가 생기지 않는다.
 * 두 탭이 "동시에" refresh하면 한쪽이 재사용 감지(전 세션 폐기)에 걸리는데,
 * 서버는 이를 도난과 구분할 수 없다 — 로그인 화면 복귀가 계약상 올바른 동작이라
 * 알려진 한계로 남긴다.
 */
let storageSyncBound = false;

function bindStorageSync() {
  if (storageSyncBound || typeof window === 'undefined') return;
  storageSyncBound = true;
  window.addEventListener('storage', (e) => {
    if (e.key !== STORAGE_KEY && e.key !== null) return;
    const stored = readStoredRefreshToken();
    const { refreshToken, accessToken } = useAuthStore.getState();
    if (stored === refreshToken) return;
    // access는 유지한다 — 다른 탭의 회전은 refresh만 갈아끼우고, 이 탭의 access는
    // 남은 수명 동안 여전히 유효하다. 단 로그아웃(null)은 세션 종료이므로 함께 비운다.
    useAuthStore.setState({
      refreshToken: stored,
      accessToken: stored === null ? null : accessToken,
    });
  });
}

export const useAuthStore = create<AuthState>()((set, get) => ({
  accessToken: null,
  refreshToken: null,
  hydrated: false,
  hydrate: () => {
    bindStorageSync();
    if (get().hydrated) return; // StrictMode 이중 이펙트 안전
    set({ refreshToken: readStoredRefreshToken(), hydrated: true });
  },
  setTokens: ({ accessToken, refreshToken }) => {
    // 방금 서버가 준 값이 정본이다 — localStorage를 읽을 필요가 없으니 hydrated도 세운다
    // (로그인 콜백처럼 hydrate 없이 진입하는 경로 대비).
    set({ accessToken, refreshToken, hydrated: true });
    persistRefreshToken(refreshToken);
  },
  clearTokens: () => {
    set({ accessToken: null, refreshToken: null, hydrated: true });
    persistRefreshToken(null);
  },
}));

/** 마운트 후 1회 hydrate — 세션 상태를 쓰는 화면이 호출한다. */
export function useAuthHydration() {
  const hydrate = useAuthStore((s) => s.hydrate);
  useEffect(() => {
    hydrate();
  }, [hydrate]);
}
