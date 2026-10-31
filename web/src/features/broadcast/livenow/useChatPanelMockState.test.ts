import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

// 채팅 훅의 줄 재사용 · 중계 모으기(POK-251 데이터 층 정비). 읽기 요청은 늘 빈 쪽으로 답한다: 줄은 중계(emitRelay)로만 넣는다
const api = vi.hoisted(() => ({
  apiFetch: vi.fn(),
}));
vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  apiFetch: api.apiFetch,
}));

import { emitRelay, publishLiveData, resetLiveData, type RelayEvent } from './liveDataStore';
import { useChatPanelMockState, type ChatPanelMessage } from './useChatPanelMockState';

const emptyPage = () =>
  new Response(JSON.stringify({ items: [], nextCursor: null, appliedOffsetMs: 0 }), {
    headers: { 'Content-Type': 'application/json' },
  });

let seq = 0;
function relay(text: string, over: Partial<RelayEvent> = {}): RelayEvent {
  seq += 1;
  return {
    seq,
    seqEpoch: 1,
    kind: 'chat',
    time: new Date(Date.UTC(2026, 9, 31, 1, 0, seq)).toISOString(),
    timeBasis: 'x',
    nickname: `닉${seq}`,
    senderChannelId: `ch-${seq}`,
    role: null,
    text,
    amount: null,
    donationType: null,
    ...over,
  };
}

/** 중계를 모으는 간격(150ms)이 지나게 두고 그 갱신을 act 안에서 받는다 */
const flushRelay = () =>
  act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 250));
  });

const startLive = (streamId: string) =>
  publishLiveData({ streamId, status: 'live', startedAt: Date.UTC(2026, 9, 31, 0, 0, 0) });

const texts = (messages: ChatPanelMessage[]) =>
  messages.map((m) => (m.kind === 'system' ? m.text : m.text));

describe('useChatPanelMockState: 라이브 중계', () => {
  beforeEach(() => {
    seq = 0;
    resetLiveData();
    api.apiFetch.mockReset();
    api.apiFetch.mockImplementation(() => Promise.resolve(emptyPage()));
  });

  async function mountLive() {
    startLive('s1');
    const rendered = renderHook(() => useChatPanelMockState(true, false));
    // 첫 응답을 받아 「연결하는 중」이 걷히면 중계 구독도 서 있다
    await waitFor(() => expect(rendered.result.current.placeholder).toBe('empty'));
    return rendered;
  }

  it('중계 줄은 모았다가 한 번에 싣는다: 조각마다 목록을 다시 만들지 않는다', async () => {
    const { result } = await mountLive();
    const seen = new Set<ChatPanelMessage[]>();
    const before = result.current.messages;
    act(() => {
      emitRelay(relay('하나'));
      emitRelay(relay('둘'));
      emitRelay(relay('셋'));
    });
    // 바로는 안 실린다
    expect(result.current.messages).toBe(before);
    await flushRelay();
    expect(texts(result.current.messages)).toEqual(['하나', '둘', '셋']);
    seen.add(result.current.messages);
    // 세 줄이 한 번의 갱신으로 들어왔다(그 사이 중간 목록이 없다)
    expect(seen.size).toBe(1);
  });

  it('바뀌지 않은 줄은 같은 객체다: 새 줄이 와도 앞 줄 컴포넌트가 다시 그려지지 않는다', async () => {
    const { result } = await mountLive();
    act(() => emitRelay(relay('앞줄')));
    await flushRelay();
    expect(result.current.messages).toHaveLength(1);
    const first = result.current.messages[0];
    act(() => emitRelay(relay('뒷줄')));
    await flushRelay();
    expect(result.current.messages).toHaveLength(2);
    expect(result.current.messages[0]).toBe(first);
  });

  it('방송이 바뀌면 모아 둔 앞 방송의 줄은 새 방송에 섞이지 않는다', async () => {
    const { result } = await mountLive();
    act(() => {
      emitRelay(relay('앞 방송 줄'));
      // 모은 줄이 실리기 전에 방송이 바뀐다
      startLive('s2');
    });
    await waitFor(() => expect(result.current.placeholder).toBe('empty'));
    // 모으기 간격(150ms)이 지나도 앞 방송의 줄은 오지 않는다
    await flushRelay();
    expect(result.current.messages).toEqual([]);
  });

  it('언마운트하면 모은 줄을 싣지 않고 타이머를 치운다', async () => {
    const { result, unmount } = await mountLive();
    const clear = vi.spyOn(window, 'clearTimeout');
    act(() => emitRelay(relay('떠나는 줄')));
    const calls = clear.mock.calls.length;
    unmount();
    expect(clear.mock.calls.length).toBeGreaterThan(calls);
    clear.mockRestore();
    // 언마운트 뒤에 타이머가 돌아도 상태를 건드리지 않는다(경고 없이 지나간다)
    await new Promise((resolve) => setTimeout(resolve, 300));
    expect(result.current.messages).toEqual([]);
  });
});
