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
//
// 다중 탭 (POK-211) — 정본은 localStorage의 refresh이고, 탭 사이 전파는 BroadcastChannel이다.
//   로그인(login)·로그아웃(logout)은 다른 탭이 이전 세션의 캐시를 비우게 하고, 같은 세션의
//   회전(rotate)은 access까지 메모리로 이어받되 캐시는 건드리지 않는다 — 회전마다 access를
//   비우고 캐시를 지우면 상대 탭이 헤더 없는 me 401 → 재회전으로 되받아쳐 두 탭이 끝없이
//   핑퐁한다. storage 이벤트는 채널이 없거나 메시지를 놓친 경우의 폴백으로만 남고, 탭이 다시
//   보이는 순간과 회전 직전(api/client)에는 정본을 즉시 다시 읽어 묵은 토큰으로 회전하지 않게 한다.
//   BroadcastChannel이 없는 환경(지원 브라우저에는 전부 있다 — Chrome 54+, Firefox 38+,
//   Safari 15.4+)과 구버전 번들 탭이 섞인 창에서는 폴백 = 이전 계약이라 핑퐁이 재발할 수 있다.
//   감수하는 한계로 적어 둔다.

const STORAGE_KEY = 'pc-auth';
const CHANNEL_NAME = 'pc-auth';
/** storage 이벤트 뒤 채널 메시지를 기다리는 시간 — 같은 기기 탭 간 전달은 수 ms다. */
const STORAGE_RECHECK_DELAY_MS = 100;

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
  /**
   * 직전 회전에서 소모된 refresh. doRefresh가 "내가 보낸 토큰을 옆 탭이 먼저 회전해 이 탭이
   * 이어받았다"(그 access로 재시도해도 된다)와 "로그아웃·다른 계정 로그인"(이 요청은 실패로
   * 끝내야 한다)을 구분하는 근거다. 로그인·로그아웃·폴백 동기화는 null로 되돌린다.
   */
  rotatedFrom: string | null;
  hydrate: () => void;
  /** 로그인 — 새 세션의 시작. 다른 탭은 이전 세션의 캐시를 비우고 이 쌍을 받는다. */
  setTokens: (pair: TokenPair) => void;
  /** 같은 세션의 refresh 회전. 다른 탭은 캐시를 유지한 채 토큰만 이어받는다. */
  rotateTokens: (pair: TokenPair) => void;
  clearTokens: () => void;
}

type CrossTabMessage =
  | { v: 1; type: 'login'; pair: TokenPair }
  | { v: 1; type: 'rotate'; prev: string; pair: TokenPair }
  | { v: 1; type: 'logout' };

/**
 * 정본(localStorage)의 refresh를 읽는다. "없음"은 null, "읽을 수 없음"(쿠키 전면 차단 등으로
 * 접근 자체가 throw)은 undefined — 둘을 구분해야 읽을 수 없는 탭의 메모리 세션을 비우지 않는다.
 */
function readStoredRefreshToken(): string | null | undefined {
  if (typeof window === 'undefined') return undefined;
  let raw: string | null;
  try {
    raw = window.localStorage.getItem(STORAGE_KEY);
  } catch {
    return undefined;
  }
  if (!raw) return null;
  try {
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null) return null;
    const token = (parsed as Record<string, unknown>).refreshToken;
    return typeof token === 'string' && token !== '' ? token : null;
  } catch {
    /* 손상 JSON — 비로그인으로 진행 */
    return null;
  }
}

// 마지막 persist가 실패했는가 — 그랬다면 정본에는 이 탭의 세션이 없으므로 정본과 맞추는 순간
// 메모리 세션이 사라진다. "저장 실패는 새로고침 시 재로그인까지만 감수"라는 계약을 지키려면
// 그 탭은 정본 동기화에서 빠져야 한다.
let persistFailed = false;

function persistRefreshToken(refreshToken: string | null) {
  try {
    if (refreshToken === null) window.localStorage.removeItem(STORAGE_KEY);
    else window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ v: 1, refreshToken }));
    persistFailed = false;
  } catch {
    /* 저장 실패는 무시 — 새로고침 시 로그인이 다시 필요해지는 것까지만 감수한다 */
    persistFailed = true;
  }
}

/** 서버 응답 객체가 그대로 들어와도 두 토큰만 남긴다 — 스토어·채널에 딴 필드가 새지 않게. */
function pickPair({ accessToken, refreshToken }: TokenPair): TokenPair {
  return { accessToken, refreshToken };
}

function isTokenPair(value: unknown): value is TokenPair {
  if (typeof value !== 'object' || value === null) return false;
  const { accessToken, refreshToken } = value as Record<string, unknown>;
  return (
    typeof accessToken === 'string' &&
    accessToken !== '' &&
    typeof refreshToken === 'string' &&
    refreshToken !== ''
  );
}

/** 같은 오리진의 스크립트만 보낼 수 있지만 모양은 믿지 않는다 — 구버전 탭·확장 프로그램. */
function parseMessage(data: unknown): CrossTabMessage | null {
  if (typeof data !== 'object' || data === null) return null;
  const m = data as Record<string, unknown>;
  if (m.v !== 1) return null;
  if (m.type === 'logout') return { v: 1, type: 'logout' };
  if (m.type === 'login' && isTokenPair(m.pair)) return { v: 1, type: 'login', pair: m.pair };
  if (m.type === 'rotate' && typeof m.prev === 'string' && isTokenPair(m.pair))
    return { v: 1, type: 'rotate', prev: m.prev, pair: m.pair };
  return null;
}

// 세션 변경 구독 — 스토어는 queryClient를 모르므로, Providers가 이 콜백으로 이전 세션의
// 쿼리 캐시(me·스트림키)를 비운다. 로그인·로그아웃·폴백 동기화에 불리고, 같은 세션의
// 회전에는 불리지 않는다(캐시가 유효하다). (리뷰 #72 · POK-211)
const crossTabListeners = new Set<() => void>();

/** 다른 탭이 바꾼 세션이 이 탭에 반영될 때 불린다. 반환값은 구독 해지 함수. */
export function onCrossTabSessionChange(listener: () => void): () => void {
  crossTabListeners.add(listener);
  return () => {
    crossTabListeners.delete(listener);
  };
}

function notifySessionChanged() {
  crossTabListeners.forEach((listener) => listener());
}

// ── 채널 ─────────────────────────────────────────────────────────────────────
// 모듈 스코프에서 만들지 않는다: 'use client' 모듈도 SSR에서 실행되고, Node에는 전역
// BroadcastChannel이 있어 typeof 검사만으로는 서버에서 핸들이 열린다. window 검사가 먼저다.
let channel: BroadcastChannel | null = null;

function ensureChannel(): BroadcastChannel | null {
  if (channel !== null) return channel;
  if (typeof window === 'undefined' || typeof window.BroadcastChannel === 'undefined') return null;
  try {
    channel = new window.BroadcastChannel(CHANNEL_NAME);
  } catch {
    return null;
  }
  channel.onmessage = (e: MessageEvent<unknown>) => {
    const msg = parseMessage(e.data);
    if (msg !== null) receive(msg);
  };
  return channel;
}

function post(msg: CrossTabMessage) {
  try {
    ensureChannel()?.postMessage(msg);
  } catch {
    /* 닫힌 채널 등 — storage 폴백이 수렴시킨다 */
  }
}

function receive(msg: CrossTabMessage) {
  switch (msg.type) {
    case 'login':
      // 현재 상태와 무관하게 적용한다 — 로그아웃 메시지를 놓친(프리즈) 탭도 이전 계정의
      // access·캐시로 이어가면 안 된다. (리뷰 #72)
      useAuthStore.setState({ ...pickPair(msg.pair), hydrated: true, rotatedFrom: null });
      notifySessionChanged();
      return;
    case 'rotate':
      // 내 토큰의 직계 후속만 받는다. 다른 사슬이면 이미 소모된 토큰일 수 있고(재사용 감지),
      // 그 경우 storage 폴백이 정본으로 맞춘다. 같은 세션이라 캐시는 유효하다 — 여기서
      // 비우면 헤더 없는 me 401 → 재회전의 핑퐁이 된다.
      if (msg.prev !== useAuthStore.getState().refreshToken) return;
      useAuthStore.setState({ ...pickPair(msg.pair), hydrated: true, rotatedFrom: msg.prev });
      return;
    case 'logout':
      useAuthStore.setState({
        accessToken: null,
        refreshToken: null,
        hydrated: true,
        rotatedFrom: null,
      });
      notifySessionChanged();
  }
}

// ── storage 폴백 ──────────────────────────────────────────────────────────────
let recheckTimer: ReturnType<typeof setTimeout> | null = null;

/**
 * localStorage(정본)와 메모리를 맞춘다 — 채널이 없거나 메시지를 놓친 경우의 수렴 경로.
 * 회전인지 계정 교체인지 알 수 없으므로 access를 비우고 캐시도 비운다(이전 계약 그대로 —
 * 로그아웃을 놓친 탭이 이전 계정 access로 조용히 이어가는 노출면을 막는다, 리뷰 #72).
 * 회전이었다면 다음 401에서 새 refresh로 한 번 더 회전하는 비용만 낸다 — 그 회전은 채널로
 * 전파되어 상대 탭이 조용히 이어받으므로 되받아치지 않는다.
 * 정본을 읽을 수 없거나 이 탭의 저장이 실패한 상태면 건드리지 않는다 — 그 탭에 정본은 없다.
 * api/client가 회전 직전에도 부른다: 묵은 refresh로 회전하면 10초 유예 밖 재사용이라 서버가
 * 전 세션을 끊는다.
 */
export function reconcileSessionWithStorage() {
  if (persistFailed) return;
  const stored = readStoredRefreshToken();
  if (stored === undefined) return;
  if (stored === useAuthStore.getState().refreshToken) return;
  useAuthStore.setState({ refreshToken: stored, accessToken: null, rotatedFrom: null });
  notifySessionChanged();
}

/**
 * storage 이벤트와 채널 메시지의 도착 순서는 보장되지 않는다 — 잠깐 뒤에 다시 읽어 채널이
 * 이기게 한다. 채널이 이미 맞춰 놓았으면 no-op.
 */
function scheduleReconcile() {
  if (recheckTimer !== null) return; // 연속 이벤트는 한 번만 — 발화 시점의 최신값을 읽는다
  recheckTimer = setTimeout(() => {
    recheckTimer = null;
    reconcileSessionWithStorage();
  }, STORAGE_RECHECK_DELAY_MS);
}

let crossTabSyncBound = false;

function bindCrossTabSync() {
  if (crossTabSyncBound || typeof window === 'undefined') return;
  crossTabSyncBound = true;
  ensureChannel();
  // storage 이벤트는 변경을 만든 탭에서는 발화하지 않으므로 persist 루프가 생기지 않는다.
  window.addEventListener('storage', (e) => {
    if (e.key !== STORAGE_KEY && e.key !== null) return;
    scheduleReconcile();
  });
  // 프리즈·bfcache로 메시지를 놓친 탭은 묵은 refresh를 들고 있다. 그걸로 회전하면 10초 유예
  // 밖이라 서버가 전 세션을 끊는다 — 다시 보이는 순간 정본과 맞춘다. 지연 없이 동기로:
  // TanStack focusManager가 같은 visibilitychange(window)에서 stale 쿼리를 즉시 재요청하는데,
  // document 리스너인 여기가 먼저 돌아야 그 재요청이 묵은 토큰으로 나가지 않는다.
  window.addEventListener('pageshow', (e) => {
    if (e.persisted) reconcileSessionWithStorage();
  });
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') reconcileSessionWithStorage();
  });
}

export const useAuthStore = create<AuthState>()((set, get) => ({
  accessToken: null,
  refreshToken: null,
  hydrated: false,
  rotatedFrom: null,
  hydrate: () => {
    bindCrossTabSync();
    if (get().hydrated) return; // StrictMode 이중 이펙트 안전
    set({ refreshToken: readStoredRefreshToken() ?? null, hydrated: true });
  },
  setTokens: (pair) => {
    bindCrossTabSync(); // 로그인 콜백은 hydrate 없이 들어온다 — 그래도 다른 탭의 변경은 받아야 한다
    const next = pickPair(pair);
    // 방금 서버가 준 값이 정본이다 — localStorage를 읽을 필요가 없으니 hydrated도 세운다.
    set({ ...next, hydrated: true, rotatedFrom: null });
    persistRefreshToken(next.refreshToken); // 정본 먼저 — 여기서 탭이 죽어도 새 refresh가 남는다
    post({ v: 1, type: 'login', pair: next });
  },
  rotateTokens: (pair) => {
    bindCrossTabSync();
    const prev = get().refreshToken;
    const next = pickPair(pair);
    set({ ...next, hydrated: true, rotatedFrom: prev });
    persistRefreshToken(next.refreshToken);
    // prev 없는 회전은 없지만, 있다면 사슬을 주장할 수 없으니 로그인으로 알린다.
    post(
      prev === null
        ? { v: 1, type: 'login', pair: next }
        : { v: 1, type: 'rotate', prev, pair: next },
    );
  },
  clearTokens: () => {
    bindCrossTabSync();
    set({ accessToken: null, refreshToken: null, hydrated: true, rotatedFrom: null });
    persistRefreshToken(null);
    post({ v: 1, type: 'logout' });
  },
}));

/** 마운트 후 1회 hydrate — 세션 상태를 쓰는 화면이 호출한다. */
export function useAuthHydration() {
  const hydrate = useAuthStore((s) => s.hydrate);
  useEffect(() => {
    hydrate();
  }, [hydrate]);
}
