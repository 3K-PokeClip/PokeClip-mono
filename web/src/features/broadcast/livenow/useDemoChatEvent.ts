'use client';

import { useEffect, useRef, useState } from 'react';
import { ClipApiError, clipFetch } from '@/api/clipEditor';
import { useAuthStore } from '@/stores/auth';
import { useToast } from '@/ui';
import { blocksMarkHotkey, type MarkKeyEvent } from './markKey';

// 발표 데모의 「채팅 이벤트」(2026-11-22). 데모 계정이 라이브 방송 화면에서 ⌃⇧C를 누르면 서버가 그 방송에 임시 시청자 채팅을
// 몰아 넣고, 판별기가 그 급증을 잡아 반응 그래프와 점프카드가 선다. 운영 사이트에 들어가므로 서버가 데모 계정 목록으로 막고
// (clip DemoChatEventController), 화면은 서버가 「쓸 수 있다」(204)고 답한 계정에만 키를 붙인다. 보이는 단추는 없다.
//
// 기능 키(F1~F16)가 아니라 보조 키 + 글자다(맥북에서 바로 누른다). 글자(key)가 아니라 키 자리(code)로 본다: 한글 입력 상태면
// key가 「ㅊ」로 온다. 성공은 알리지 않는다: 발표 화면에 「임시 채팅을 보냈어요」가 뜨면 안 된다. 실패만 알려 다시 누르게 한다

/** 쓸 수 있는지 묻다가 일시 실패하면 다시 묻는 간격 */
const RETRY_MS = 10_000;

/** 키 이벤트에서 보는 것. 마킹 키와 같은 꼴에 키 자리(code)가 더해진다 */
export type DemoChatKeyEvent = MarkKeyEvent & { code: string };

export function isDemoChatEventKey(event: DemoChatKeyEvent): boolean {
  if (!event.ctrlKey || !event.shiftKey || event.metaKey || event.altKey) return false;
  // 누르고 있으면 초당 수십 번 온다. 서버도 흐르는 중이면 무시하지만 요청을 쌓지 않는다
  if (event.repeat) return false;
  return event.code === 'KeyC';
}

/**
 * @param live 지금 화면의 방송이 방송 중인가. 끝난 방송에 채팅을 넣으면 판별기가 그 방송을 다시 살아 있다고 본다
 * @returns 이 계정이 쓸 수 있나(서버가 204로 답했나)
 */
export function useDemoChatEvent(streamId: string, live: boolean): boolean {
  const { toast } = useToast();
  const [allowed, setAllowed] = useState(false);
  const latest = useRef({ streamId, live });
  useEffect(() => {
    latest.current = { streamId, live };
  });

  // 화면이 설 때, 그리고 로그인 토큰이 바뀔 때(같은 탭에서 다른 계정으로 들어옴) 다시 묻는다. 토큰 회전(30분)에도 한 번 더 묻는다.
  // 🔴 끄는 것은 서버가 「아니다」(404)라고 답했을 때와 로그아웃(토큰 없음)뿐이다. 일시 실패(503 · 네트워크)에 끄면 발표 중에
  // 키가 아무 알림 없이 죽으므로, 앞 값을 둔 채 잠시 뒤 다시 묻는다. 다른 계정의 판정이 남아도 서버가 다시 막는다(누르면 실패 토스트)
  const session = useAuthStore((s) => s.refreshToken);
  useEffect(() => {
    if (session === null) {
      setAllowed(false);
      return;
    }
    let alive = true;
    let retry: number | undefined;
    const ask = () => {
      clipFetch('/api/clip/demo/chat-event')
        .then((res) => {
          if (alive) setAllowed(res.status === 204);
        })
        .catch((e: unknown) => {
          if (!alive) return;
          // 4xx(404 · 세션 만료 401 등)는 다시 물어도 같은 답이라 끈다. 서버 쪽 실패(5xx)와 네트워크 실패만 다시 묻는다
          if (e instanceof ClipApiError && e.status < 500) setAllowed(false);
          else retry = window.setTimeout(ask, RETRY_MS);
        });
    };
    ask();
    return () => {
      alive = false;
      window.clearTimeout(retry);
    };
  }, [session]);

  useEffect(() => {
    if (!allowed) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || !isDemoChatEventKey(event)) return;
      if (blocksMarkHotkey(event.target)) return;
      const { streamId: id, live: isLive } = latest.current;
      if (!id || !isLive) return;
      event.preventDefault();
      clipFetch(`/api/clip/broadcasts/${encodeURIComponent(id)}/demo/chat-event`, {
        method: 'POST',
      }).catch(() => toast({ tone: 'warning', title: '채팅 이벤트를 보내지 못했어요' }));
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [allowed, toast]);

  return allowed;
}
