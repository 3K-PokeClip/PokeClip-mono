'use client';

import { useCallback, useEffect } from 'react';
import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { delegationsAsEditorQueryOptions } from '@/api/editors';
import { useMe } from '@/features/auth/useSession';
import {
  isListSeenWhenSelected,
  setAsEditorListReader,
  useChannelStore,
  type ListSnapshot,
} from '@/stores/channel';
import { useToast } from '@/ui';

// 「지금 채널」(POK-390 채널 스위처): 고른 번호(store)를 me·as-editor와 맞대어 푼다.
//
// 🔴 이 훅은 순수 해석만 한다. 고른 채널이 as-editor에서 사라졌으면(위임 끊김) 내 채널로 풀고
// gone=true를 실어 줄 뿐, 저장값 지우기·토스트는 안 한다. 이 훅은 사이드바·헤더·화면 훅 여러
// 자리에서 동시에 쓰여서, 부수효과를 여기 두면 쓰는 자리 수만큼 돈다. 실행은 ChannelScope 한 곳.

export type ChannelRelation = 'OWNER' | 'EDITOR';

export type ActiveChannel =
  | { ready: false; streamerId: null; relation: null; name: null; gone: false }
  | {
      ready: true;
      streamerId: number;
      relation: ChannelRelation;
      name: string;
      /** 고른 채널이 as-editor에 없어 내 채널로 풀었다. 처리는 ChannelScope가 한다. */
      gone: boolean;
    };

const NOT_READY: ActiveChannel = {
  ready: false,
  streamerId: null,
  relation: null,
  name: null,
  gone: false,
};

/** 지금 as-editor 목록(쿼리 객체 + 갱신 횟수). 아직 쿼리가 없으면 null */
export function asEditorList(queryClient: QueryClient): ListSnapshot | null {
  const query = queryClient
    .getQueryCache()
    .find({ queryKey: delegationsAsEditorQueryOptions.queryKey, exact: true });
  return query ? { list: query, count: query.state.dataUpdateCount } : null;
}

export function useActiveChannel(): ActiveChannel {
  const me = useMe().data;
  const asEditor = useQuery({ ...delegationsAsEditorQueryOptions, enabled: me !== undefined });
  const hydrate = useChannelStore((s) => s.hydrate);
  const storeUserId = useChannelStore((s) => s.userId);
  const selected = useChannelStore((s) => s.selected);
  const selectedAgainst = useChannelStore((s) => s.selectedAgainst);
  const queryClient = useQueryClient();

  // 고를 때의 목록을 store가 적을 수 있게 읽는 길을 넣는다. hydrate보다 먼저: 효과는 적힌 순서로 돈다.
  useEffect(() => {
    setAsEditorListReader(() => asEditorList(queryClient));
  }, [queryClient]);

  // 스스로 읽는다: 편집기는 (fullscreen) 그룹이라 ChannelScope 밖에서도 이 훅을 쓴다.
  useEffect(() => {
    if (me) hydrate(me.id);
  }, [me, hydrate]);

  // store가 아직 이 회원으로 안 읽혔으면 selected는 앞 회원의 값이거나 초기값이다: 믿지 않는다.
  if (!me || storeUserId !== me.id) return NOT_READY;

  const mine: ActiveChannel = {
    ready: true,
    streamerId: me.id,
    relation: 'OWNER',
    name: me.name,
    gone: false,
  };
  if (selected === null || selected === me.id) return mine;

  // 실패는 데이터가 없을 때만 본다: react-query는 다시 읽기가 실패해도 남은 데이터를 두고 상태만 error로 바꾼다.
  // 그것을 「모른다」로 읽으면 편집자가 보던 채널이 auth의 잠깐 실패마다 내 채널로 튄다(감사 2회차 지적 2).
  if (asEditor.data === undefined) {
    // 아직 못 받았으면 기다린다: 먼저 「내 채널」로 그리면 새로고침마다 내 목록을 한 번 불렀다가 바꾼다.
    // 처음부터 못 읽었으면 끊겼는지 모른다: 내 채널로 보여 주되 저장값은 남긴다(다음에 읽히면 돌아간다).
    return asEditor.isError ? mine : NOT_READY;
  }

  const delegation = asEditor.data.find((d) => d.streamerId === selected);
  if (!delegation) {
    // 고른 뒤에 읽은 목록이어야 「없다」가 끊김이다. 그 전 목록이면 새로 읽을 때까지 기다린다(새로 읽기는 ChannelScope가
    // 시킨다). 새로 읽기마저 실패했으면 모른다: 내 채널로 보이되 끊김으로 다루지 않는다.
    if (isListSeenWhenSelected(selectedAgainst, asEditorList(queryClient))) {
      return asEditor.isError ? mine : NOT_READY;
    }
    return { ...mine, gone: true };
  }
  return {
    ready: true,
    streamerId: delegation.streamerId,
    relation: 'EDITOR',
    name: delegation.streamerName,
    gone: false,
  };
}

export interface SwitchTarget {
  streamerId: number;
  name: string;
  relation: ChannelRelation;
}

/** 채널을 바꾸고 알린다. 내 채널이면 저장된 줄을 지운다(「내 채널」은 고른 것 없음과 같다). */
export function useSwitchChannel() {
  const me = useMe().data;
  const select = useChannelStore((s) => s.select);
  const { toast } = useToast();

  return useCallback(
    (target: SwitchTarget) => {
      if (!me) return;
      select(me.id, target.streamerId === me.id ? null : target.streamerId);
      toast({
        tone: 'success',
        title: `${target.name} 채널로 전환했어요`,
        description: `${target.relation === 'OWNER' ? '스트리머' : '편집자'} · 메뉴와 권한이 바뀌었어요`,
        // 연달아 바꾸면 쌓지 않고 최신 것으로: 키가 없으면 제목이 달라 쌓인다.
        dedupeKey: 'channel-switch',
      });
    },
    [me, select, toast],
  );
}
