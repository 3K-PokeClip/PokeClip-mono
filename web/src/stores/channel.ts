'use client';

import { create } from 'zustand';

// 지금 고른 채널(POK-390 채널 스위처): 편집자가 맡은 남의 채널과 내 채널을 오간다.
// 영속화는 onboarding과 같은 수동 localStorage 패턴이고, 초기값은 정적이다(모듈에서 읽으면
// 서버 HTML과 첫 렌더가 어긋난다). 회원 번호를 열쇠로 둔다: 같은 브라우저에서 다른 계정으로
// 로그인했을 때 앞 사람의 선택을 남의 채널 번호로 쓰면 404가 나거나, 더 나쁘면 그 사람도 맡은
// 채널이라 엉뚱한 화면이 열린다.
//
// 이 store는 「무엇을 골랐나」만 기억한다. 그 번호가 아직 볼 수 있는 채널인지는 모른다:
// 그 판정은 useActiveChannel이 as-editor 목록과 맞대어 한다.

const STORAGE_KEY = 'pc-channel';

type StoredChoices = Record<string, number>;

interface ChannelState {
  /** hydrate한 회원. null이면 아직 아무 회원으로도 안 읽었다. */
  userId: number | null;
  /** 고른 스트리머 번호. null = 내 채널. */
  selected: number | null;
  /** selected가 바뀔 때마다 새 값이 된다: 같은 번호를 다시 골라도 효과가 다시 돌게 하는 표시일 뿐, 시각으로 비교하지 않는다. */
  selectedAt: number;
  /**
   * 고를 때 이미 받아 둔 as-editor 목록의 갱신 횟수(react-query `dataUpdateCount`). 목록이 없었으면 null.
   * 이 횟수 이하의 목록에 그 채널이 없는 것은 끊김의 근거가 못 된다: 다른 탭이 방금 수락하고 고른 채널은 이 탭의
   * 낡은 목록에 아직 없다(감사 2회차 지적 1). 처음엔 시각(Date.now)으로 비교했는데, 고른 시각과 목록을 받은 시각이
   * 같은 밀리초면 낡은 목록을 새것으로 읽어 거짓 끊김이 났다(시험 20회 중 1회, 시계를 고정하면 매번).
   */
  selectedAgainst: number | null;
  /** 그 회원의 저장값을 읽는다. 같은 회원으로 다시 부르면 아무것도 안 한다(쓰는 자리가 여럿이다). */
  hydrate: (userId: number) => void;
  /** null이면 내 채널로: 저장된 줄을 지운다. */
  select: (userId: number, streamerId: number | null) => void;
}

function readChoices(): StoredChoices {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return {};
    return parsed as StoredChoices;
  } catch {
    /* 손상 JSON·프라이빗 모드: 고른 것 없음으로 */
    return {};
  }
}

function readChoice(userId: number): number | null {
  const v = readChoices()[String(userId)];
  // 문자열 '3'은 받지 않는다: 우리가 쓴 값이 아니고, 받으면 채널 비교가 === 에서 갈린다.
  return typeof v === 'number' && Number.isSafeInteger(v) && v > 0 ? v : null;
}

function writeChoice(userId: number, streamerId: number | null) {
  try {
    const choices = readChoices();
    if (streamerId === null) delete choices[String(userId)];
    else choices[String(userId)] = streamerId;
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(choices));
  } catch {
    /* 저장 실패: 이 탭의 메모리로만 기억한다(새로고침하면 내 채널로 돌아간다) */
  }
}

let listening = false;

/** 지금 as-editor 목록의 갱신 횟수를 읽는 함수. react-query는 React 안에 있어 useActiveChannel이 넣어 준다. */
let readAsEditorCount: () => number | null = () => null;

export function setAsEditorCountReader(read: () => number | null) {
  readAsEditorCount = read;
}

export const useChannelStore = create<ChannelState>()((set, get) => {
  /** 다른 탭에서 고르면 따라간다. storage 이벤트는 바꾼 탭에서는 안 와서 저장 루프가 안 생긴다. */
  const listenOtherTabs = () => {
    if (listening || typeof window === 'undefined') return;
    listening = true;
    window.addEventListener('storage', (e) => {
      // key가 null이면 localStorage.clear(): 우리 줄도 사라졌다.
      if (e.key !== STORAGE_KEY && e.key !== null) return;
      const { userId } = get();
      if (userId === null) return;
      set({
        selected: readChoice(userId),
        selectedAt: Date.now(),
        selectedAgainst: readAsEditorCount(),
      });
    });
  };

  return {
    userId: null,
    selected: null,
    selectedAt: 0,
    selectedAgainst: null,
    hydrate: (userId) => {
      listenOtherTabs();
      if (get().userId === userId) return;
      set({
        userId,
        selected: readChoice(userId),
        selectedAt: Date.now(),
        selectedAgainst: readAsEditorCount(),
      });
    },
    select: (userId, streamerId) => {
      set({
        userId,
        selected: streamerId,
        selectedAt: Date.now(),
        selectedAgainst: readAsEditorCount(),
      });
      writeChoice(userId, streamerId);
    },
  };
});
