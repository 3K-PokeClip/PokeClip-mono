'use client';

import { useEffect, useRef, useState } from 'react';
import { usePathname, useSearchParams } from 'next/navigation';
import { resolveLiveStreamId, vodStreamIdFromPathname } from '@/features/broadcast/streamSelection';
import { publishLiveData, useLiveData } from './liveDataStore';
import { useChannelStreamerId } from '@/features/channel/ChannelScope';

// 라이브 화면이 볼 방송 번호를 정한다(POK-251). 정한 번호는 liveDataStore에 올려 대시보드 훅들이 같이 쓴다.
//
// 주소가 방송을 못 박았으면(지난 방송 상세·?stream=) 그것 하나다. 라이브 대시보드는 「지금 방송 중인 것」을
// 보는 화면이라 10초마다 다시 묻는다: 방송이 끝나면 빈 값이 와서 꺼짐 화면으로 돌아가고, 새 방송이 시작되면
// 새로 고침 없이 그 방송으로 넘어간다. 종료 알림(SSE ended)이 오면 10초를 기다리지 않고 바로 다시 묻는다.
//
// 🔴 재생 보유(POK-200 M5 · 설계 5.5절): 재생 세션이 보던 방송을 잡고 있으면(liveDataStore 의 playbackHold = 지금 선택) 다시 고른
// 답을 선택에 넣지 않고 deferred 로 낸다 — 끝난 방송의 끝남 대기 · 녹화가 꺼짐 화면이나 새 방송으로 내려가지 않게. 화면은
// deferred 가 보던 방송과 다르면 「새 방송이 시작됐어요」 띠를 둔다. 보유가 풀리면 곧바로 새로 묻고 답을 넣는다.

export interface LiveStreamSelection {
  /** 빈 문자열 = 방송 중인 것이 없다 */
  streamId: string;
  /** 한 번이라도 답을 받았는가 — 받기 전에는 꺼짐 화면을 띄우지 않는다(깜빡임) */
  resolved: boolean;
  /** 보유 중에 다시 고른 답(빈 문자열 = 방송 중인 것이 없다). 보유가 없거나 답이 보던 방송과 같으면 null */
  deferred: string | null;
  /**
   * 마지막 다시 고르기의 답 — 지금 방송 번호 X 의 재료(재생 세션). 그 물음이 실패했으면 null(모름)이다: 실패를 「보던 방송 그대로」로
   * 읽으면 같은 키의 새 방송이 보던 방송 세션에 섞인다(cx H4)
   */
  latest: string | null;
}

/** 재생 보유 중의 다시 고르기 결과(POK-200 M5) — 「새 방송」 띠 · 지금 방송 번호 X 의 재료(useLiveSession) */
export type Reselect = Pick<LiveStreamSelection, 'deferred' | 'latest'>;

export const LIVE_RESELECT_MS = 10_000;

export function useLiveStreamSelection(): LiveStreamSelection {
  const { status, playbackHold } = useLiveData();
  // 주소가 못 박은 방송. Next 값으로 읽는다: 같은 화면에서 ?stream=만 바뀌면(사이드바 「라이브 대시보드」, 뒤로·앞으로) 화면이
  // 다시 서지 않아, 처음 한 번 주소창에서 읽던 값이 남았다(POK-254)
  const pathname = usePathname();
  const streamParam = useSearchParams().get('stream');
  const pinned = vodStreamIdFromPathname(pathname ?? '') ?? (streamParam || null);
  // 주소가 못 박았으면 처음부터 그 방송이다: 효과를 한 번 기다리면 그동안 화면이 스켈레톤을 한 번 더 그린다(지난 방송 상세, POK-445)
  const [selection, setSelection] = useState<Omit<LiveStreamSelection, 'deferred' | 'latest'>>(
    () =>
      pinned !== null ? { streamId: pinned, resolved: true } : { streamId: '', resolved: false },
  );
  const [deferred, setDeferred] = useState<string | null>(null);
  const [latest, setLatest] = useState<string | null>(null);
  // 답이 올 때의 보유를 읽는다(효과는 보유에 걸지 않는다 — 보유가 바뀔 때마다 10초 주기를 다시 세우지 않게)
  const holdRef = useRef(playbackHold);
  holdRef.current = playbackHold;
  const selectionRef = useRef(selection);
  selectionRef.current = selection;
  /** 다시 고른 답을 넣는다. 보유 중이고 보유가 지금 선택이면 선택에 넣지 않고 deferred 로 낸다 */
  const apply = (id: string | null) => {
    setLatest(id);
    if (id === null) return;
    const prev = selectionRef.current;
    const hold = holdRef.current;
    if (hold !== null && prev.resolved && hold === prev.streamId) {
      setDeferred(id === prev.streamId ? null : id);
      return;
    }
    setDeferred(null);
    setSelection((p) => (p.resolved && p.streamId === id ? p : { streamId: id, resolved: true }));
  };
  const applyRef = useRef(apply);
  applyRef.current = apply;
  // 고른 채널(POK-390): 그 채널에서 방송 중인 것을 묻는다
  const streamerId = useChannelStreamerId();

  useEffect(() => {
    if (pinned !== null) {
      setSelection((prev) =>
        prev.resolved && prev.streamId === pinned ? prev : { streamId: pinned, resolved: true },
      );
      return;
    }
    let alive = true;
    const resolve = (fresh: boolean) =>
      void resolveLiveStreamId(streamerId, fresh).then((id) => {
        // 모르면(오류) 지금 보던 것을 그대로 둔다 — 꺼짐으로 바꾸지 않는다. 마지막 답은 「모름」이 된다
        if (!alive) return;
        applyRef.current(id);
      });
    resolve(false);
    const t = window.setInterval(() => resolve(true), LIVE_RESELECT_MS);
    return () => {
      alive = false;
      window.clearInterval(t);
    };
  }, [pinned, streamerId]);

  useEffect(() => {
    if (status !== 'ended' || pinned !== null) return;
    void resolveLiveStreamId(streamerId, true).then((id) => {
      applyRef.current(id);
    });
  }, [status, pinned, streamerId]);

  // 보유가 풀리면(라이브 보기 · 띠 누름 · 세션을 다른 방송으로 닫음) 곧바로 새로 묻고 답을 넣는다
  const held = playbackHold !== null;
  const wasHeld = useRef(held);
  useEffect(() => {
    const released = wasHeld.current && !held;
    wasHeld.current = held;
    if (!released || pinned !== null) return;
    let alive = true;
    void resolveLiveStreamId(streamerId, true).then((id) => {
      if (alive) applyRef.current(id);
    });
    return () => {
      alive = false;
    };
  }, [held, pinned, streamerId]);

  useEffect(() => {
    publishLiveData({ streamId: selection.streamId });
  }, [selection.streamId]);

  return {
    ...selection,
    deferred: selection.streamId === playbackHold ? deferred : null,
    latest: pinned === null ? latest : null,
  };
}
