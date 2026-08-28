'use client';

import { useEffect, useRef, useState } from 'react';

// 실시간 채팅 패널(시안 1b)의 목업.
//
// 플레이어 안 오버레이(useSimulatedChat)와 따로 두는 이유는 담는 것이 다르기 때문이다 —
// 저쪽은 닉네임·본문뿐이고, 이쪽은 후원과 시스템 알림(하이라이트 감지)을 함께 세운다.
//
// 교체 시점도 다르다: 이 패널은 POK-180(카드 SSE)의 범위가 아니다. 채팅 원문을 화면에
// 흘려보내는 창구가 아직 어느 계약에도 없어(status.md 열린 미결) 실연동 티켓이 생길 때까지
// 목업으로 남는다. 수집이 살아 있는지(끊김 배지)는 동결 계약의 chatWarning이 답한다.

export const CHAT_PANEL_INTERVAL_MS = 3500;
const KEEP_LAST = 12;

export interface ChatSurge {
  keyword: string;
  count: number;
}

export type ChatPanelMessage =
  | {
      id: number;
      kind: 'chat';
      name: string;
      text: string;
      /** 닉네임 색 로테이션 인덱스 (LiveScreen.module.css .chatName*) */
      colorIndex: number;
    }
  | { id: number; kind: 'donation'; name: string; amountLabel: string; text: string }
  /** 하이라이트 감지처럼 서비스가 끼워 넣는 줄 */
  | { id: number; kind: 'system'; text: string };

export interface ChatPanelMockState {
  surges: ChatSurge[];
  messages: ChatPanelMessage[];
}

const MOCK_SURGES: ChatSurge[] = [
  { keyword: 'ㅋㅋㅋㅋ', count: 214 },
  { keyword: '미쳤다', count: 86 },
  { keyword: '클러치', count: 41 },
];

// 시안 1b 채팅 패널에 그려진 줄 그대로 — 테스트가 이 문구를 단언한다
const INITIAL: ChatPanelMessage[] = [
  { id: 1, kind: 'chat', name: '수면부족', text: '새벽에 이걸 보고 있네 ㅋㅋ', colorIndex: 0 },
  { id: 2, kind: 'chat', name: '겜돌이', text: '상대 정글 울겠다', colorIndex: 1 },
  { id: 3, kind: 'chat', name: '별사탕', text: '승급전 마지막판 가보자', colorIndex: 2 },
  { id: 4, kind: 'chat', name: '야옹이22', text: '방금 궁 타이밍 뭐임 ㄷㄷ', colorIndex: 3 },
  { id: 5, kind: 'chat', name: '초코송이', text: '클립각 클립각', colorIndex: 4 },
  {
    id: 6,
    kind: 'donation',
    name: '도네초코',
    amountLabel: '치즈 5,000',
    text: '승급 기원!! 가즈아',
  },
  { id: 7, kind: 'chat', name: '겜돌이', text: '한타 각 나온다 집중', colorIndex: 1 },
  { id: 8, kind: 'chat', name: '별사탕', text: '1v3 클러치 실화냐', colorIndex: 2 },
  { id: 9, kind: 'chat', name: '초코송이', text: 'ㅋㅋㅋㅋㅋㅋㅋ 개쩐다', colorIndex: 4 },
  { id: 10, kind: 'chat', name: '다이아가자', text: '미쳤다미쳤다미쳤다', colorIndex: 5 },
  { id: 11, kind: 'system', text: '하이라이트 감지 · 1:24:03 구간이 카드로 만들어졌어요' },
];

const POOL: ReadonlyArray<Omit<Extract<ChatPanelMessage, { kind: 'chat' }>, 'id'>> = [
  { kind: 'chat', name: '하늘바람', text: '방금 그거 다시 보여주세요', colorIndex: 0 },
  { kind: 'chat', name: '클립장인', text: '지금 클립 각이다', colorIndex: 3 },
  { kind: 'chat', name: 'bbibu', text: 'ㅋㅋㅋㅋㅋㅋ', colorIndex: 1 },
  { kind: 'chat', name: '밤도둑', text: '오늘 폼 미쳤네', colorIndex: 2 },
  { kind: 'chat', name: '빙수가게', text: '승급 각 보인다', colorIndex: 4 },
];

export function useChatPanelMockState(enabled: boolean): ChatPanelMockState {
  const [messages, setMessages] = useState<ChatPanelMessage[]>(INITIAL);
  const counter = useRef(INITIAL.length);

  useEffect(() => {
    if (!enabled) return;
    // 무작위 대신 순번으로 고른다 — 목업이라도 렌더가 결정적이어야 테스트가 흔들리지 않는다
    const tick = window.setInterval(() => {
      const pick = POOL[counter.current % POOL.length];
      if (!pick) return;
      counter.current += 1;
      setMessages((prev) => [...prev.slice(-(KEEP_LAST - 1)), { ...pick, id: counter.current }]);
    }, CHAT_PANEL_INTERVAL_MS);
    return () => window.clearInterval(tick);
  }, [enabled]);

  return { surges: MOCK_SURGES, messages };
}
