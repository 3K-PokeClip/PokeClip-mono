'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { ClipApiError, postWebMark, type WebMarkBody } from '@/api/clipEditor';
import { track } from '@/lib/analytics';
import { useToast } from '@/ui';
import { blocksMarkHotkey, isMarkHotkey } from './markKey';
import { msToClock, type LiveCardSnapshot } from './useLiveMockState';

/** 마킹 결과 한 줄을 두는 시간 */
const NOTICE_MS = 3000;

// 수동 마킹(시안 1b·1c, POK-392). 갈래가 둘이다.
// - 라이브: 누른 때·보낸 때를 보내고 서버가 시각을 정한다. 브라우저 시계가 틀려도 그 차(받은 시각 − 보낸 때)가 빠진다.
// - 지난 방송: 녹화 재생기의 정확한 자리(서버 기준점 축 ms)를 보낸다. 방송 시계는 끝난 방송에서 방송 길이에 멈춰 있어 쓰지 않는다.
//   재생기가 그 축의 자리를 못 주면(녹화 없음 · 기준점을 몰라 녹화 시작 축) 보내지 않는다: 카드가 두 시작의 차만큼 어긋난다.
// 답이 올 때까지의 자리는 StreamInfoBar·HighlightCardPanel의 pendingLabel이 그리고, 답의 카드는 onCard로 목록에 넣는다(라이브는
// 통로로 같은 카드가 와도 같은 id라 하나다. 지난 방송은 통로가 없어 답이 유일한 길이다).
//
// 리스너를 document에 다는 이유: 핫키의 쓸모가 "어디를 보고 있든 눌리는 것"이라
// 화면 컨테이너에 달면 포커스가 밖에 있을 때 죽는다. 대신 가드가 둘 붙는다 —
// 입력 중인 곳(blocksMarkHotkey)과 이미 누군가 처리한 키(defaultPrevented).

/** live·past는 명부가 답한 방송 상태다. 모르면 unknown이고 어느 갈래도 보내지 않는다 */
export type MarkMode = 'live' | 'past' | 'unknown';

export interface ManualMarkingOptions {
  /** 지금 화면의 방송 번호. 비어 있으면(정하기 전) 아무것도 안 보낸다 */
  streamId: string;
  mode: MarkMode;
  /** 라이브: 누른 순간의 방송 시계 표기(만드는 중 자리에 쓴다). 모르면 null */
  clockLabel: string | null;
  /** 지난 방송: 지금 재생 위치(서버 기준점 축 ms). 그 축의 자리가 없으면 null */
  positionMs: () => number | null;
  /** 답으로 받은 카드(201 새 카드 · 200 앞 카드) */
  onCard: (card: LiveCardSnapshot) => void;
  /** 200(같은 eventId이거나 20초 안 앞 카드)에서 지난 방송이면 그 카드로 옮긴다. 라이브는 되감지 않는다 */
  onExistingCard: (card: LiveCardSnapshot) => void;
}

export function useManualMarking(options: ManualMarkingOptions): {
  mark: () => void;
  /** 만드는 중인 카드의 시각. 보내는 동안만 있다 */
  pendingLabel: string | null;
  /** 마킹 결과 한 줄. 토스트와 영상 아래 줄이 안 보이는 전체 화면에서 플레이어가 띄운다. 잠깐 있다 사라진다 */
  notice: string | null;
} {
  const { toast } = useToast();
  const [pendingLabel, setPendingLabel] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const noticeTimer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(noticeTimer.current), []);
  const showNotice = useCallback((text: string) => {
    setNotice(text);
    window.clearTimeout(noticeTimer.current);
    noticeTimer.current = window.setTimeout(() => setNotice(null), NOTICE_MS);
  }, []);
  const say = useCallback(
    (tone: 'info' | 'warning', title: string) => {
      toast({ tone, title });
      showNotice(title);
    },
    [toast, showNotice],
  );
  // 보내는 중이면 또 누른 것은 버린다(자리는 하나). 상태가 아니라 ref다: 같은 그리기 안에서 두 번 눌러도 막는다
  const sending = useRef(false);
  // 그리기마다 바뀌는 값(방송 시계·콜백)을 ref로 읽는다: mark가 매초 새로 만들어지면 document 리스너도 매초 다시 붙는다
  const latest = useRef(options);
  useEffect(() => {
    latest.current = options;
  });
  // 답이 오기 전에 화면이 내려갔나(방송을 옮기면 화면이 통째로 바뀔 수 있다)
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const mark = useCallback(() => {
    const { streamId, mode, clockLabel, positionMs, onCard, onExistingCard } = latest.current;
    if (!streamId || sending.current) return;
    if (mode === 'unknown') {
      say('info', '방송 정보를 확인하고 있어요. 잠시 뒤 다시 눌러 주세요');
      return;
    }
    let body: WebMarkBody;
    let label: string;
    const eventId = crypto.randomUUID();
    if (mode === 'past') {
      const position = positionMs();
      if (position === null) {
        say('info', '재생 위치를 알 수 없어 카드를 만들 수 없어요');
        return;
      }
      body = { eventId, positionMs: position };
      // 카드 목록과 같은 표기(카드가 서면 그 카드의 시각과 같은 글자다)
      label = msToClock(Math.max(0, position));
    } else {
      const pressedAt = Date.now();
      body = { eventId, pressedAt, sentAt: pressedAt };
      label = clockLabel ?? '지금';
    }
    sending.current = true;
    setPendingLabel(label);
    // 답이 왔을 때 아직 그 방송을 보고 있나. 기다리는 사이 다른 방송으로 옮겼으면 앞 방송의 답으로 새 방송을 옮기거나 알리지 않는다
    // (PR #350 codex P2: 200이 새 방송의 영상·채팅을 앞 방송 카드 시각으로 옮겼다). 카드 넣기는 mergeCard가 방송 번호를 따로 본다
    const stillHere = () => mounted.current && latest.current.streamId === streamId;
    void (async () => {
      try {
        // 보낸 때는 보내기 직전에 다시 읽는다(누른 때와 사이가 생기면 그만큼 서버가 뺀다)
        const sent = 'sentAt' in body ? { ...body, sentAt: Date.now() } : body;
        const { status, card } = await postWebMark<LiveCardSnapshot>(streamId, sent);
        onCard(card);
        // 새로 만든 카드만 센다(200은 「가까운 카드가 이미 있어요」). 화면을 떠났어도 서버에는 생겼다 (POK-357)
        if (status !== 200) track('highlight_marked', { mode });
        if (!stillHere()) return;
        if (status === 200) {
          // 앞 카드가 가린 카드면(POK-463) 보이는 칸에는 없다: 「이미 있다」만 말하면 마킹이 실패한 것처럼 읽힌다
          say('info', card.hidden ? '가까운 카드가 숨김 칸에 있어요' : '가까운 카드가 이미 있어요');
          if (mode === 'past') onExistingCard(card);
        } else {
          // 성공은 토스트를 안 띄운다: 평소엔 카드가 목록에 서는 것이 답이고, 전체 화면엔 그 목록이 없다
          showNotice(`${label} 카드를 만들었어요`);
        }
      } catch (e) {
        if (!stillHere()) return;
        const notReady =
          e instanceof ClipApiError && e.status === 503 && e.code === 'timeline_not_ready';
        say(
          'warning',
          notReady
            ? '아직 영상 기준을 못 정했어요. 잠시 뒤 다시 눌러 주세요'
            : '카드를 만들지 못했어요',
        );
      } finally {
        sending.current = false;
        setPendingLabel(null);
      }
    })();
  }, [say, showNotice]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      // 플레이어 단축키 등 이미 누군가 처리한 키는 건드리지 않는다
      if (event.defaultPrevented) return;
      if (!isMarkHotkey(event)) return;
      if (blocksMarkHotkey(event.target)) return;
      event.preventDefault();
      mark();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [mark]);

  return { mark, pendingLabel, notice };
}
