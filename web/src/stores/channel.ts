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
   * 고를 때 이미 받아 둔 as-editor 목록: 그 쿼리 객체와 갱신 횟수(react-query `dataUpdateCount`). 목록이 없었으면 null.
   * 이 횟수 이하의 목록에 그 채널이 없는 것은 끊김의 근거가 못 된다: 다른 탭이 방금 수락하고 고른 채널은 이 탭의
   * 낡은 목록에 아직 없다(감사 2회차 지적 1). 처음엔 시각(Date.now)으로 비교했는데, 고른 시각과 목록을 받은 시각이
   * 같은 밀리초면 낡은 목록을 새것으로 읽어 거짓 끊김이 났다(시험 20회 중 1회, 시계를 고정하면 매번).
   * 쿼리 객체까지 묶는 이유: 쿼리가 지워졌다 다시 생기면(queryClient.clear, 수거) 횟수가 0부터 다시 센다. 횟수만
   * 기억하면 새 목록을 옛 목록으로 읽어 영영 준비 안 됨이 된다(감사 4회차 지적 1).
   */
  selectedAgainst: ListSnapshot | null;
  /** 그 회원의 저장값을 읽는다. 같은 회원으로 다시 부르면 아무것도 안 한다(쓰는 자리가 여럿이다). */
  hydrate: (userId: number) => void;
  /** null이면 내 채널로: 저장된 줄을 지운다. */
  select: (userId: number, streamerId: number | null) => void;
  /**
   * 고른 채널의 목록 창구가 404를 줬다(위임이 끊겼을 수 있다)는 신호. 값은 뜻이 없고 바뀌는 것만 뜻이 있다:
   * ChannelScope가 이것을 보고 as-editor를 다시 읽는다. 목록 함수(api)는 react-query를 몰라 신호만 남긴다.
   */
  goneSignal: number;
  /** 마지막 404 신호가 어느 채널 번호로 물은 것이었나. 내 채널이면 끊김이 아니라 세션이 어긋난 것이다(ChannelScope) */
  goneFor: number | null;
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

/** as-editor 목록 한 장: 그 쿼리 객체와 그때까지의 갱신 횟수 */
export interface ListSnapshot {
  list: object;
  count: number;
}

/** 지금 as-editor 목록을 읽는 함수. react-query는 React 안에 있어 useActiveChannel이 넣어 준다. */
let readAsEditorList: () => ListSnapshot | null = () => null;

export function setAsEditorListReader(read: () => ListSnapshot | null) {
  readAsEditorList = read;
}

/**
 * 지금 목록이 고를 때 본 그 목록인가(같은 쿼리 객체이고 그 뒤로 새로 받지 않았다). 참이면 그 목록에 고른 채널이
 * 없어도 끊김의 근거가 못 된다. 쿼리 객체가 다르면 지워졌다 다시 생긴 것이라, 그 뒤 받은 목록은 전부 고른 뒤 것이다.
 */
export function isListSeenWhenSelected(
  against: ListSnapshot | null,
  now: ListSnapshot | null,
): boolean {
  return (
    against !== null && now !== null && now.list === against.list && now.count <= against.count
  );
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
        selectedAgainst: readAsEditorList(),
      });
    });
  };

  return {
    userId: null,
    selected: null,
    selectedAt: 0,
    selectedAgainst: null,
    goneSignal: 0,
    goneFor: null,
    hydrate: (userId) => {
      listenOtherTabs();
      if (get().userId === userId) return;
      set({
        userId,
        selected: readChoice(userId),
        selectedAt: Date.now(),
        selectedAgainst: readAsEditorList(),
      });
    },
    select: (userId, streamerId) => {
      set({
        userId,
        selected: streamerId,
        selectedAt: Date.now(),
        selectedAgainst: readAsEditorList(),
      });
      writeChoice(userId, streamerId);
    },
  };
});

/**
 * 목록 함수가 부른다: 그 채널 번호로 물었는데 404였다. 판정은 ChannelScope가 한다. 남의 채널이면 as-editor를 다시 읽어
 * 끊겼나 보고, 내 채널이면 이 탭의 me가 토큰과 어긋난 것이라(다른 탭에서 다른 계정으로 로그인) me를 다시 읽는다.
 */
export function notifyChannelGone(streamerId: number) {
  useChannelStore.setState((s) => ({ goneSignal: s.goneSignal + 1, goneFor: streamerId }));
}
