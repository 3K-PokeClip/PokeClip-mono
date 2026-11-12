'use client';

import { fetchMeLoose, isBroadcastInChannel, type BroadcastRow } from '@/api/clipEditor';
import { fetchDelegationsAsEditor } from '@/api/editors';
import { createPinnedXResolver } from '@/features/broadcast/livenow/useLiveSession';
import { resolveLiveStreamId } from '@/features/broadcast/streamSelection';
import type { XAnswer } from '@/features/player/rewindSwitch';

// 편집기 R58 의 입력 — 「이 방송의 채널(스트리머) 번호」 와 그 번호로만 묻는 지금 방송 번호(POK-473 계획 prw2 4절 P-9 · A3).
// 편집기는 ChannelScope 밖이라(ChannelScope.tsx 「편집기면 null = 섞어서」) 채널 번호가 없다. 섞어서(null) 물으면 채널 구분
// 없는 live 첫 줄이 와(streamSelection.ts askLiveStreamId) 다른 채널이 방송 중이면 R58 이 「다른 방송」 으로 판정해 방송 중
// 편집이 막힌다. 그래서 번호를 못 정하면 묻지 않고 늘 「모름」 이다 — 모름이면 스트림 경로 답을 버린다(플레이어 R58 규칙).
// 방송 줄에 스트리머 번호가 없어 관계 · 위임으로 고른다(StudioScreen loadTrackLabels 와 같은 판정에 여러 위임 갈래를 더함).

/** 지금 방송 번호를 묻는 한도 — 넘으면 「모름」이다(useLiveSession.ts X_TIMEOUT_MS 와 같은 값 · 그쪽은 내보내지 않는다) */
const X_TIMEOUT_MS = 2_000;

/** 내 번호(Me.id 는 숫자 또는 숫자 글로 온다). 그 밖이면 null */
function idOf(value: number | string): number | null {
  const id = typeof value === 'number' ? value : Number(value);
  return Number.isSafeInteger(id) ? id : null;
}

/** 위임받은 스트리머 가운데 이 방송이 든 채널. 하나면 그것, 여럿이면 채널 목록 대조에서 처음 참인 것 */
async function delegatedStreamerOf(streamId: string): Promise<number | null> {
  const delegations = await fetchDelegationsAsEditor();
  if (delegations.length === 1) return delegations[0]!.streamerId;
  for (const { streamerId } of delegations) {
    if (await isBroadcastInChannel(streamId, streamerId)) return streamerId;
  }
  return null;
}

/**
 * 이 방송의 채널 번호. 내 방송이면 내 번호, 편집자면 위임받은 스트리머 가운데 이 방송이 든 채널이다. 못 정하면(위임 없음 ·
 * 어느 채널에도 없음 · 조회 실패) null
 */
export async function streamerIdOf(
  broadcast: Pick<BroadcastRow, 'streamId' | 'relation'>,
): Promise<number | null> {
  try {
    if (broadcast.relation === 'OWNER') {
      const me = await fetchMeLoose();
      return me === null ? null : idOf(me.id);
    }
    return await delegatedStreamerOf(broadcast.streamId);
  } catch (e) {
    console.warn('[editor] 방송의 채널 번호를 정하지 못했어요:', e);
    return null;
  }
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T | null> {
  return new Promise((resolve) => {
    const timer = window.setTimeout(() => resolve(null), ms);
    void promise.then((value) => {
      window.clearTimeout(timer);
      resolve(value);
    });
  });
}

/**
 * 편집기의 지금 방송 번호 묻기(useEditorSource 의 resolveX). 채널 번호가 정해지면 그 번호로만 묻고(10초 안의 다시 묻기는 앞
 * 답 — createPinnedXResolver), 번호가 null 이면 묻지 않고 늘 모름이다
 * @param streamerId streamerIdOf 의 결과(정해질 때까지 기다린다)
 * @param mediaKey 영상 키 — 앞 답을 다시 쓸 열쇠
 */
export function editorXResolver(
  streamerId: Promise<number | null>,
  mediaKey: string,
): (fresh: boolean) => Promise<XAnswer> {
  const pinned = createPinnedXResolver(async () => {
    const id = await streamerId;
    return id === null ? null : withTimeout(resolveLiveStreamId(id, true), X_TIMEOUT_MS);
  });
  return (fresh) => pinned(fresh, mediaKey);
}
