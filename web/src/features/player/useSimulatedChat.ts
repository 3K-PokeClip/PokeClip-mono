'use client';

import { useEffect, useRef, useState } from 'react';

// 채팅 오버레이 목업 — 실채팅 수집은 감지 파이프라인 연동 티켓에서 이 훅만 교체한다.
// 플레이어 재생 상태와 교체 시점이 달라 usePlayerSimulation과 분리해 둔다.

export const CHAT_INTERVAL_MS = 3500;
const KEEP_LAST = 5;

export interface SimChatMessage {
  id: number;
  name: string;
  text: string;
  /** 닉네임 색 로테이션 인덱스 (GlassPlayer.module.css .chatColor*) */
  colorIndex: number;
}

const INITIAL: SimChatMessage[] = [
  { id: 1, name: 'purin_', text: '이걸 쫓아온다고?', colorIndex: 0 },
  { id: 2, name: '초코송이', text: '잡히면 클립인데 ㅋㅋ', colorIndex: 1 },
  { id: 3, name: '밤도둑', text: '오늘 폼 미쳤네', colorIndex: 2 },
  { id: 4, name: 'jinu99', text: '클립 각이다', colorIndex: 3 },
];

const POOL = [
  { name: '하늘바람', text: '방금 뭐였음??', colorIndex: 0 },
  { name: '클립장인', text: '지금 하이라이트다', colorIndex: 2 },
  { name: 'bbibu', text: 'ㅋㅋㅋㅋㅋㅋ', colorIndex: 1 },
  { name: 'PURIN', text: '2시간 순삭이네', colorIndex: 3 },
  { name: '빙수가게', text: '오프닝이라 짧아', colorIndex: 0 },
] as const;

export function useSimulatedChat(enabled: boolean): SimChatMessage[] {
  const [messages, setMessages] = useState<SimChatMessage[]>(INITIAL);
  const counter = useRef(INITIAL.length);

  useEffect(() => {
    if (!enabled) return;
    const tick = window.setInterval(() => {
      const pick = POOL[Math.floor(Math.random() * POOL.length)] ?? POOL[0];
      counter.current += 1;
      const next: SimChatMessage = { ...pick, id: counter.current };
      setMessages((prev) => [...prev.slice(-(KEEP_LAST - 1)), next]);
    }, CHAT_INTERVAL_MS);
    return () => window.clearInterval(tick);
  }, [enabled]);

  return messages;
}
