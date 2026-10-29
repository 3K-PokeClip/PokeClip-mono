'use client';

import type { ComponentProps } from 'react';
import { Button, useTheme } from '@/ui';
import { ChatPanel } from '@/features/broadcast/livenow/ChatPanel';
import type { ChatPanelMessage } from '@/features/broadcast/livenow/useChatPanelMockState';

// 실제 ChatPanel 상황별 미리보기(커밋하지 않는다) — 배지 B · 로딩 D · 키워드 설정 제거 반영 확인용

const LINES: ChatPanelMessage[] = [
  {
    id: 1,
    kind: 'chat',
    name: '감자도리',
    text: '와 방금 그거 뭐임 ㅋㅋㅋㅋ',
    colorIndex: 1,
    platform: 'CHZZK',
  },
  { id: 2, kind: 'chat', name: '새벽세시', text: '클립각 클립각', colorIndex: 2, platform: 'SOOP' },
  {
    id: 3,
    kind: 'donation',
    name: '포키팬',
    amountLabel: '5,000원',
    text: '오늘도 방송 감사해요!',
    platform: 'CHZZK',
  },
  { id: 4, kind: 'chat', name: '라면왕', text: 'ㅋㅋㅋㅋㅋㅋㅋ', colorIndex: 3, platform: 'CHZZK' },
  {
    id: 5,
    kind: 'chat',
    name: '구름빵',
    text: '이건 하이라이트감이다',
    colorIndex: 5,
    platform: 'CHZZK',
  },
];
const SURGES = [
  { keyword: 'ㅋㅋㅋㅋ', count: 214 },
  { keyword: '클립각', count: 38 },
];
const BASE = {
  surges: SURGES,
  messages: LINES,
  ratePerMinute: 402,
  collectionWarning: false,
  onCollapse: () => {},
};

const CASES: { label: string; props: Partial<ComponentProps<typeof ChatPanel>> }[] = [
  { label: '라이브 · 정상', props: {} },
  { label: '지난 방송', props: { mode: 'ended', replayClock: '1:12:34', ratePerMinute: 6.2 } },
  { label: '라이브 · 끊김', props: { collectionWarning: true } },
  { label: '방송 대기', props: { mode: 'offline', messages: [], surges: [], ratePerMinute: null } },
  {
    label: '지난 방송 · 불러오는 중',
    props: {
      mode: 'ended',
      messages: [],
      surges: [],
      ratePerMinute: null,
      replayWaiting: true,
      placeholder: 'loading',
    },
  },
  {
    label: '라이브 · 연결하는 중',
    props: { messages: [], surges: [], ratePerMinute: null, placeholder: 'connecting' },
  },
  {
    label: '지난 방송 · 못 불러옴',
    props: {
      mode: 'ended',
      messages: [],
      surges: [],
      ratePerMinute: null,
      replayClock: '1:12:34',
      placeholder: 'failed',
    },
  },
  {
    label: '지난 방송 · 채팅 0개',
    props: {
      mode: 'ended',
      messages: [],
      surges: [],
      ratePerMinute: null,
      replayClock: '1:12:34',
      placeholder: 'empty',
    },
  },
];

export default function ChatPanelPreviewPage() {
  const { resolvedTheme, setTheme } = useTheme();
  return (
    <main
      style={{
        minHeight: '100vh',
        padding: '28px 24px 80px',
        background: 'var(--pc-color-bg-canvas)',
        color: 'var(--pc-color-text-primary)',
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 20 }}>
        <h1 style={{ fontSize: 20, fontWeight: 800, margin: 0 }}>
          채팅 창 · 실제 컴포넌트 미리보기
        </h1>
        <Button
          variant="outline"
          size="sm"
          onClick={() => setTheme(resolvedTheme === 'dark' ? 'light' : 'dark')}
        >
          {resolvedTheme === 'dark' ? '라이트로 보기' : '다크로 보기'}
        </Button>
      </div>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(4, calc(332 * var(--pc-u)))',
          gap: '10px 16px',
        }}
      >
        {CASES.map((c) => (
          <div key={c.label}>
            <p
              style={{
                fontSize: 12,
                fontWeight: 700,
                margin: '0 0 8px',
                color: 'var(--pc-color-text-secondary)',
              }}
            >
              {c.label}
            </p>
            <div style={{ height: 330 }}>
              <ChatPanel {...BASE} {...c.props} />
            </div>
          </div>
        ))}
      </div>
    </main>
  );
}
