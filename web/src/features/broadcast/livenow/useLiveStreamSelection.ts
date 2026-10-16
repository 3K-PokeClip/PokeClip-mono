'use client';

import { useEffect, useState } from 'react';
import { usePathname, useSearchParams } from 'next/navigation';
import { resolveLiveStreamId, vodStreamIdFromPathname } from '@/features/broadcast/streamSelection';
import { publishLiveData, useLiveData } from './liveDataStore';

// 라이브 화면이 볼 방송 번호를 정한다(POK-251). 정한 번호는 liveDataStore에 올려 대시보드 훅들이 같이 쓴다.
//
// 주소가 방송을 못 박았으면(지난 방송 상세·?stream=) 그것 하나다. 라이브 대시보드는 「지금 방송 중인 것」을
// 보는 화면이라 10초마다 다시 묻는다: 방송이 끝나면 빈 값이 와서 꺼짐 화면으로 돌아가고, 새 방송이 시작되면
// 새로 고침 없이 그 방송으로 넘어간다. 종료 알림(SSE ended)이 오면 10초를 기다리지 않고 바로 다시 묻는다.

export interface LiveStreamSelection {
  /** 빈 문자열 = 방송 중인 것이 없다 */
  streamId: string;
  /** 한 번이라도 답을 받았는가 — 받기 전에는 꺼짐 화면을 띄우지 않는다(깜빡임) */
  resolved: boolean;
}

export const LIVE_RESELECT_MS = 10_000;

export function useLiveStreamSelection(): LiveStreamSelection {
  const [selection, setSelection] = useState<LiveStreamSelection>({
    streamId: '',
    resolved: false,
  });
  const { status } = useLiveData();
  // 주소가 못 박은 방송. Next 값으로 읽는다: 같은 화면에서 ?stream=만 바뀌면(사이드바 「라이브 대시보드」, 뒤로·앞으로) 화면이
  // 다시 서지 않아, 처음 한 번 주소창에서 읽던 값이 남았다(POK-254)
  const pathname = usePathname();
  const streamParam = useSearchParams().get('stream');
  const pinned = vodStreamIdFromPathname(pathname ?? '') ?? (streamParam || null);

  useEffect(() => {
    if (pinned !== null) {
      setSelection((prev) =>
        prev.resolved && prev.streamId === pinned ? prev : { streamId: pinned, resolved: true },
      );
      return;
    }
    let alive = true;
    const resolve = (fresh: boolean) =>
      void resolveLiveStreamId(fresh, false).then((id) => {
        // 모르면(오류) 지금 보던 것을 그대로 둔다 — 꺼짐으로 바꾸지 않는다
        if (!alive || id === null) return;
        setSelection((prev) =>
          prev.resolved && prev.streamId === id ? prev : { streamId: id, resolved: true },
        );
      });
    resolve(false);
    const t = window.setInterval(() => resolve(true), LIVE_RESELECT_MS);
    return () => {
      alive = false;
      window.clearInterval(t);
    };
  }, [pinned]);

  useEffect(() => {
    if (status !== 'ended' || pinned !== null) return;
    void resolveLiveStreamId(true, false).then((id) => {
      if (id === null) return;
      setSelection((prev) => (prev.streamId === id ? prev : { streamId: id, resolved: true }));
    });
  }, [status, pinned]);

  useEffect(() => {
    publishLiveData({ streamId: selection.streamId });
  }, [selection.streamId]);

  return selection;
}
