'use client';

import { useCallback, useEffect, useRef } from 'react';
import { useToast } from '@/ui';
import { ensureWindowHeads } from '../source/editorWindow';
import type { HeadCache } from '../source/fileHeadProbe';
import { saveAfterReload, saveRefusalMessage, type EndpointRange } from '../source/rangeEndpoints';
import type { EditorSource } from '../source/useEditorSource';

// 편집기 저장 · 영상 만들기 앞의 컷 판정(POK-473 계획 prw2 5절 E4b-3 · 처분 PK1). 누를 때 목록을 한 번 다시 받아 그 목록으로
// savable 을 보고, 서면 그 컷으로 이어 간다. 막히면 까닭을 알린다. 다시 받으면 풀릴 수 있는 까닭(② 머리 · 목록)에는 「다시
// 시도」 단추를 달아 — 머리면 그 끝점 조각의 머리를 다시 읽고 — 그 순간의 동작(again)을 다시 부른다(cc L2). 처음 받은 컷 ·
// 이어 갈 일은 다시 쓰지 않는다 — 그 사이 고친 화면이나 새 저장을 옛 내용으로 덮지 않게(E4b-3 r1 [cc·cx] HIGH). 새 판정이
// 시작되거나 화면을 떠나면 앞의 거절 알림을 닫고, 떠난 뒤에 끝난 판정은 버린다. StudioScreen(800줄 넘음)은 부르기만 한다.

/** 저장할 컷(절대 ms) */
export interface SaveCut {
  inMs: number;
  outMs: number;
}

/**
 * @returns 컷을 판정해 서면 proceed, 막히면 알리고 refused 를 부르는 함수
 */
export function useSaveCut(options: {
  /** 편집기 0초의 절대 ms */
  base: number;
  heads: HeadCache;
  /** clip 방송 번호 — 창 소리 주소 요청(② 머리 다시 읽기) */
  streamId: string;
  /** 영상 키 — ② 머리 캐시 */
  mediaKey: string;
  source: Pick<EditorSource, 'list' | 'reloadList'>;
}): (
  endpoints: EndpointRange | undefined,
  proceed: (cut: SaveCut) => void,
  refused: () => void,
  /** 「다시 시도」 — 부른 쪽의 동작을 그 순간의 레시피로 처음부터 다시 부른다(바쁨 검사 포함) */
  again: () => void,
) => void {
  const { base, heads, streamId, mediaKey, source } = options;
  const { toast, dismiss } = useToast();
  const { list, reloadList } = source;
  /** 떠 있는 거절 알림 */
  const refusal = useRef<string | null>(null);
  /** 판정이 진행 중이다 — 겹친 판정은 막는다(cc L1) */
  const judging = useRef(false);
  /** 화면이 떠 있는 동안의 끊개. 떠나면 끊기고, 그 뒤 끝난 판정 · 다시 읽기는 버린다 */
  const life = useRef<AbortController | null>(null);

  const closeRefusal = useCallback(() => {
    if (refusal.current !== null) dismiss(refusal.current);
    refusal.current = null;
  }, [dismiss]);

  useEffect(() => {
    const controller = new AbortController();
    life.current = controller;
    return () => {
      controller.abort();
      judging.current = false;
      closeRefusal();
    };
  }, [closeRefusal]);

  /** 고름 끝점 조각의 ② 머리를 다시 읽는다(실패 · 미등재였던 것만 다시 묻는다 — fileHeadProbe) */
  const rereadHeads = useCallback(
    async (endpoints: EndpointRange, signal: AbortSignal) => {
      const seqs = new Set(
        [endpoints.start, endpoints.end].flatMap((e) => (e.kind === 'pick' ? [e.pick.seq] : [])),
      );
      const rows = (list?.playlist.rows ?? []).filter((row) => seqs.has(row.seq));
      await ensureWindowHeads(streamId, mediaKey, rows, signal);
    },
    [list, streamId, mediaKey],
  );

  return useCallback(
    (endpoints, proceed, refused, again) => {
      if (endpoints === undefined) throw new Error('실제 편집기에 끝점이 없다');
      const signal = life.current?.signal;
      if (signal === undefined || signal.aborted || judging.current) {
        refused();
        return;
      }
      closeRefusal();
      judging.current = true;
      const settle = () => {
        judging.current = false;
        return !signal.aborted;
      };
      saveAfterReload(endpoints, base, heads, reloadList)
        .then((verdict) => {
          if (!settle()) return;
          if (verdict.ok) {
            proceed({ inMs: verdict.inMs, outMs: verdict.outMs });
            return;
          }
          refused();
          const { title, retry } = saveRefusalMessage(verdict.reason);
          const retryNow = () => {
            refusal.current = null;
            const reread =
              verdict.reason === 'listUnavailable'
                ? Promise.resolve()
                : rereadHeads(endpoints, signal);
            void reread.then(() => {
              if (!signal.aborted) again();
            });
          };
          refusal.current = toast({
            tone: 'error',
            title,
            ...(retry ? { action: { label: '다시 시도', onClick: retryNow } } : {}),
          });
        })
        .catch((e: unknown) => {
          if (!settle()) return;
          refused();
          refusal.current = toast({
            tone: 'error',
            title: '저장 실패',
            description: (e as Error).message,
          });
        });
    },
    [base, heads, reloadList, rereadHeads, toast, closeRefusal],
  );
}
