'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { ClipApiError, postWebMark } from '@/api/clipEditor';
import { useToast } from '@/ui';
import { isMarkHotkey, isTypingTarget } from './markKey';
import type { LiveCardSnapshot } from './useLiveMockState';

// 수동 마킹(시안 1b, POK-392). 라이브는 누른 때·보낸 때를 서버에 보내고 서버가 시각을 정한다: 브라우저 시계가 틀려도
// 그 차(받은 시각 − 보낸 때)가 빠진다. 답이 올 때까지의 자리는 StreamInfoBar·HighlightCardPanel의 pendingLabel이 그리고,
// 답의 카드는 onCard로 목록에 넣는다(통로로 같은 카드가 와도 같은 id라 하나다).
//
// 지난 방송은 아직 준비 중 안내다: 방송 시계는 끝난 방송에서 방송 길이에 멈춰 있어 끝 시각에 찍힌다. 플레이어 재생 위치를
// 보내는 갈래는 따로 붙는다. 라이브 갈래를 지난 방송에 보내면 서버가 400을 준다.
//
// 리스너를 document에 다는 이유: 핫키의 쓸모가 "어디를 보고 있든 눌리는 것"이라
// 화면 컨테이너에 달면 포커스가 밖에 있을 때 죽는다. 대신 가드가 둘 붙는다 —
// 입력 중인 곳(isTypingTarget)과 이미 누군가 처리한 키(defaultPrevented).

export interface ManualMarkingOptions {
  /** 지금 화면의 방송 번호. 비어 있으면(정하기 전) 아무것도 안 보낸다 */
  streamId: string;
  /** 방송 중인가. 아니면 아직 준비 중 안내다 */
  live: boolean;
  /** 누른 순간의 방송 시계 표기(만드는 중 자리에 쓴다). 모르면 null */
  clockLabel: string | null;
  /** 답으로 받은 카드(201 새 카드 · 200 앞 카드) */
  onCard: (card: LiveCardSnapshot) => void;
}

export function useManualMarking({ streamId, live, clockLabel, onCard }: ManualMarkingOptions): {
  mark: () => void;
  /** 만드는 중인 카드의 시각. 보내는 동안만 있다 */
  pendingLabel: string | null;
} {
  const { toast } = useToast();
  const [pendingLabel, setPendingLabel] = useState<string | null>(null);
  // 보내는 중이면 또 누른 것은 버린다(자리는 하나). 상태가 아니라 ref다: 같은 그리기 안에서 두 번 눌러도 막는다
  const sending = useRef(false);

  const mark = useCallback(() => {
    if (!live) {
      toast({
        tone: 'info',
        title: '준비 중인 기능이에요',
        description: '수동 마킹은 아직 준비 중이에요. 준비되면 알려드릴게요.',
      });
      return;
    }
    if (!streamId || sending.current) return;
    sending.current = true;
    const eventId = crypto.randomUUID();
    const pressedAt = Date.now();
    setPendingLabel(clockLabel ?? '지금');
    void (async () => {
      try {
        const { status, card } = await postWebMark<LiveCardSnapshot>(streamId, {
          eventId,
          pressedAt,
          sentAt: Date.now(),
        });
        onCard(card);
        // 200은 같은 eventId이거나 20초 안의 앞 카드다. 라이브는 재생을 되감지 않고 알리기만 한다
        if (status === 200) toast({ tone: 'info', title: '가까운 카드가 이미 있어요' });
      } catch (e) {
        const notReady =
          e instanceof ClipApiError && e.status === 503 && e.code === 'timeline_not_ready';
        toast({
          tone: 'warning',
          title: notReady
            ? '아직 영상 기준을 못 정했어요. 잠시 뒤 다시 눌러 주세요'
            : '카드를 만들지 못했어요',
        });
      } finally {
        sending.current = false;
        setPendingLabel(null);
      }
    })();
  }, [live, streamId, clockLabel, onCard, toast]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      // 플레이어 단축키 등 이미 누군가 처리한 키는 건드리지 않는다
      if (event.defaultPrevented) return;
      if (!isMarkHotkey(event)) return;
      if (isTypingTarget(event.target)) return;
      event.preventDefault();
      mark();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [mark]);

  return { mark, pendingLabel };
}
