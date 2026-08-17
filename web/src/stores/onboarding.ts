'use client';

import { useEffect } from 'react';
import { create } from 'zustand';

// 온보딩(POK-113) 클라이언트 상태 — 웰컴 다이얼로그·코치마크 투어·단계 완료 체크.
// 영속화는 ThemeProvider의 수동 localStorage 패턴을 따른다 (zustand/persist 선례 없음).
// 초기값은 항상 정적 기본값이다 — 모듈 스코프에서 localStorage를 읽으면
// 서버 HTML과 클라 첫 렌더가 어긋난다 (useHomeMockState의 하이드레이션 경고와 같은 함정).

const STORAGE_KEY = 'pc-onboarding';

export interface OnboardingFlags {
  welcomeSeen: boolean;
  tourDone: boolean;
  channelLinked: boolean;
  pluginLinked: boolean;
}

const FLAG_KEYS = ['welcomeSeen', 'tourDone', 'channelLinked', 'pluginLinked'] as const;

interface OnboardingState extends OnboardingFlags {
  /** localStorage 읽기 완료 — 온보딩 UI는 이 값이 서기 전엔 아무것도 그리지 않는다. */
  hydrated: boolean;
  /** null=투어 미실행, 0..5=진행 중. 세션 메모리 전용 — 새로고침 시 오버레이가 강제로 덮이지 않게 비영속. */
  tourStep: number | null;
  hydrate: () => void;
  dismissWelcome: () => void;
  startTour: () => void;
  /** 다음/이전 계산은 tourSteps의 순수 함수가 하고, 결과만 여기로 들어온다. */
  setTourStep: (step: number | null) => void;
  skipTour: () => void;
  completeTour: () => void;
  setChannelLinked: (linked: boolean) => void;
  markPluginLinked: () => void;
}

function readStoredFlags(): Partial<OnboardingFlags> {
  if (typeof window === 'undefined') return {};
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null) return {};
    const out: Partial<OnboardingFlags> = {};
    for (const key of FLAG_KEYS) {
      const v = (parsed as Record<string, unknown>)[key];
      if (typeof v === 'boolean') out[key] = v;
    }
    return out;
  } catch {
    /* 손상 JSON·프라이빗 모드 — 기본값으로 진행 */
    return {};
  }
}

function persistFlags(flags: OnboardingFlags) {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ v: 1, ...flags }));
  } catch {
    /* 저장 실패는 무시 — 다음 방문에 온보딩이 다시 뜨는 것까지만 감수한다 */
  }
}

export const useOnboardingStore = create<OnboardingState>()((set, get) => {
  /** 플래그 변이는 전부 이 경로로 — 상태 갱신과 영속화가 한 몸이다. */
  const update = (partial: Partial<OnboardingFlags> & { tourStep?: number | null }) => {
    set(partial);
    const { welcomeSeen, tourDone, channelLinked, pluginLinked } = get();
    persistFlags({ welcomeSeen, tourDone, channelLinked, pluginLinked });
  };

  return {
    welcomeSeen: false,
    tourDone: false,
    channelLinked: false,
    pluginLinked: false,
    hydrated: false,
    tourStep: null,
    hydrate: () => {
      if (get().hydrated) return; // StrictMode 이중 이펙트 안전
      set({ ...readStoredFlags(), hydrated: true }); // tourStep은 건드리지 않는다
    },
    dismissWelcome: () => update({ welcomeSeen: true }),
    startTour: () => update({ welcomeSeen: true, tourStep: 0 }),
    setTourStep: (step) => set({ tourStep: step }),
    skipTour: () => update({ tourDone: true, tourStep: null }),
    completeTour: () => update({ tourDone: true, tourStep: null }),
    setChannelLinked: (linked) => update({ channelLinked: linked }),
    markPluginLinked: () => update({ pluginLinked: true }),
  };
});

/** 마운트 후 1회 hydrate — 온보딩 상태를 쓰는 화면이 호출한다. */
export function useOnboardingHydration() {
  const hydrate = useOnboardingStore((s) => s.hydrate);
  useEffect(() => {
    hydrate();
  }, [hydrate]);
}
