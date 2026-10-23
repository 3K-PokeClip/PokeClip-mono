import { create } from 'zustand';
import type { ChannelRelation } from './useActiveChannel';

// 채널 전환 화면(시안 비교 B안). 채널을 고르면 화면 전체를 잠깐 덮고 「○○ 채널로 전환 중」을 보인 뒤 걷는다:
// 사이드바·본문이 한꺼번에 툭 바뀌고 빈 칸이 깜빡이던 것을 가린다. 언제 걷을지는 화면(ChannelSwitchCurtain)이 정하고,
// 여기는 덮개를 세우고 다 덮은 뒤에 채널을 바꾸는 일만 한다. 바꾸는 일을 화면에 맡기지 않는 이유: 화면이 안 떠 있는
// 자리(시험·독 밖)에서도 전환은 돼야 한다.

/** 덮개가 다 덮는 데 걸리는 시간. 그 뒤에 채널을 바꾼다: 덮는 동안 뒤가 바뀌는 모습이 비쳐 보이지 않게 */
export const COVER_IN_MS = 140;

export interface SwitchCurtain {
  /** 전환마다 새 번호: 연달아 바꾸면 앞 덮개의 걷기 타이머가 새 덮개를 걷지 않게 */
  key: number;
  name: string;
  relation: ChannelRelation;
  photo: string | null;
  shownAt: number;
}

interface SwitchCurtainState {
  curtain: SwitchCurtain | null;
  /** 덮개를 세우고 다 덮은 뒤 commit(채널 바꾸기)을 부른다. 연달아 부르면 마지막 것만 바뀐다. */
  cover: (target: Omit<SwitchCurtain, 'key' | 'shownAt'>, commit: () => void) => void;
  /** 이 번호의 덮개를 걷는다(이미 새 덮개로 바뀌었으면 그대로 둔다) */
  clear: (key: number) => void;
  /** 시험용: 덮개와 걸린 전환을 모두 버린다 */
  reset: () => void;
}

let pendingCommit: ReturnType<typeof setTimeout> | undefined;

export const useSwitchCurtain = create<SwitchCurtainState>()((set) => ({
  curtain: null,
  cover: (target, commit) => {
    clearTimeout(pendingCommit);
    set((s) => ({ curtain: { ...target, key: (s.curtain?.key ?? 0) + 1, shownAt: Date.now() } }));
    pendingCommit = setTimeout(commit, COVER_IN_MS);
  },
  clear: (key) => set((s) => (s.curtain?.key === key ? { curtain: null } : s)),
  reset: () => {
    clearTimeout(pendingCommit);
    set({ curtain: null });
  },
}));
