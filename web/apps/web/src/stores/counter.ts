import { create } from 'zustand';

interface CounterState {
  count: number;
  increment: () => void;
  decrement: () => void;
  reset: () => void;
}

// 클라이언트 전역 상태 스토어의 패턴 앵커.
// 서버 데이터는 TanStack Query가 담당하고, zustand는 순수 클라이언트 상태만 든다.
export const useCounterStore = create<CounterState>()((set) => ({
  count: 0,
  increment: () => set((s) => ({ count: s.count + 1 })),
  decrement: () => set((s) => ({ count: s.count - 1 })),
  reset: () => set({ count: 0 }),
}));
