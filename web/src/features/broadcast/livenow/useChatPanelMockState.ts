'use client';

import { useEffect, useRef, useState } from 'react';
import { MOCK_CHAT_RATE_PER_MINUTE } from './liveMockValues';

// 실시간 채팅 패널(시안 1b)의 목업.
//
// 플레이어 안 오버레이(useSimulatedChat)와 따로 두는 이유는 담는 것이 다르기 때문이다 —
// 저쪽은 닉네임·본문뿐이고, 이쪽은 후원과 시스템 알림(하이라이트 감지)을 함께 세운다.
//
// 교체 시점도 다르다: 이 패널은 POK-180(카드 SSE)의 범위가 아니다. 채팅 원문을 화면에
// 흘려보내는 창구가 아직 어느 계약에도 없어(status.md 열린 미결) 실연동 티켓이 생길 때까지
// 목업으로 남는다. 수집이 살아 있는지(끊김 배지)는 동결 계약의 chatWarning이 답한다.

export const CHAT_PANEL_INTERVAL_MS = 3500;
// 초기 픽스처(INITIAL)보다 넉넉해야 한다 — 작으면 첫 새 메시지가 오는 순간 옛 줄이 잘려
// 2단 레이아웃에서 목록이 다시 안 넘치고, 스크롤·「지난 메시지 보는 중」을 눈으로 볼 수 없게 된다.
const KEEP_LAST = 40;

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
  /** 하단 상태줄의 「분당 N」 — 통계의 「분당 평균 채팅」과 같은 원천(MOCK_CHAT_RATE_PER_MINUTE) */
  ratePerMinute: number;
}

const MOCK_SURGES: ChatSurge[] = [
  { keyword: 'ㅋㅋㅋㅋ', count: 214 },
  { keyword: '미쳤다', count: 86 },
  { keyword: '클러치', count: 41 },
];

// 뒤쪽 11줄은 시안 1b 채팅 패널에 그려진 줄 그대로다 — 테스트가 이 문구를 단언한다.
// 앞쪽 20줄은 그보다 오래된 채팅이다: 2단(데스크톱) 레이아웃의 화면 높이 패널에서도 목록이 넘쳐야
// 자체 스크롤과 「지난 메시지 보는 중」 분기를 실제 화면에서 볼 수 있다(이 목업은 수집 끊김 상태라
// 새 메시지가 오지 않아, 여기 든 줄이 화면에 서는 전부다).
// id는 순서와 무관한 렌더 키일 뿐이라 시안 줄의 번호를 건드리지 않으려고 12부터 붙였다 —
// 새 메시지는 INITIAL.length(=31) 다음부터 세므로 겹치지 않는다.
const INITIAL: ChatPanelMessage[] = [
  { id: 12, kind: 'chat', name: '밤샘각', text: '오늘도 새벽 랭크인가요', colorIndex: 0 },
  { id: 13, kind: 'chat', name: '라면먹자', text: '어제 그 판 진짜 레전드였음', colorIndex: 3 },
  { id: 14, kind: 'chat', name: '겜돌이', text: 'ㅇㅇ 그거 보고 입덕함', colorIndex: 1 },
  { id: 15, kind: 'chat', name: '포키좋아', text: '지금 몇 승 몇 패예요?', colorIndex: 2 },
  { id: 16, kind: 'chat', name: '별사탕', text: '2연승 중이십니다', colorIndex: 2 },
  { id: 17, kind: 'chat', name: '야옹이22', text: '오늘 안에 승급 가나요', colorIndex: 3 },
  { id: 18, kind: 'chat', name: '초코송이', text: '폼 보면 가능하죠', colorIndex: 4 },
  { id: 19, kind: 'chat', name: '수면부족', text: '저 내일 출근인데 왜 보고 있지', colorIndex: 0 },
  { id: 20, kind: 'chat', name: '다이아가자', text: '같이 망하실래요', colorIndex: 5 },
  { id: 21, kind: 'chat', name: '라면먹자', text: 'ㅋㅋㅋㅋ 인정', colorIndex: 3 },
  { id: 22, kind: 'chat', name: '겜돌이', text: '픽 뭐 하실 거예요', colorIndex: 1 },
  { id: 23, kind: 'chat', name: '별사탕', text: '정글 고정이시죠', colorIndex: 2 },
  { id: 24, kind: 'chat', name: '밤샘각', text: '상대 미드 잘한다던데', colorIndex: 0 },
  { id: 25, kind: 'chat', name: '포키좋아', text: '그래도 이깁니다', colorIndex: 2 },
  { id: 26, kind: 'chat', name: '야옹이22', text: '와 저 궁 타이밍', colorIndex: 3 },
  { id: 27, kind: 'chat', name: '초코송이', text: '와 진짜 미쳤네', colorIndex: 4 },
  { id: 28, kind: 'chat', name: '다이아가자', text: '이거 클립 따야 되는 거 아님?', colorIndex: 5 },
  { id: 29, kind: 'chat', name: '수면부족', text: '그 장면 한 번만 더요', colorIndex: 0 },
  { id: 30, kind: 'chat', name: '겜돌이', text: '한타 열립니다', colorIndex: 1 },
  { id: 31, kind: 'chat', name: '별사탕', text: '집중집중', colorIndex: 2 },
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
      // id를 갱신 함수 밖에서 굳힌다 — 안에서 ref를 읽으면 React가 갱신을 다시 돌릴 때
      // 같은 번호가 두 번 붙어 키가 겹친다(useSimulatedChat과 같은 모양).
      const next: ChatPanelMessage = { ...pick, id: counter.current };
      setMessages((prev) => [...prev.slice(-(KEEP_LAST - 1)), next]);
    }, CHAT_PANEL_INTERVAL_MS);
    return () => window.clearInterval(tick);
  }, [enabled]);

  return { surges: MOCK_SURGES, messages, ratePerMinute: MOCK_CHAT_RATE_PER_MINUTE };
}
