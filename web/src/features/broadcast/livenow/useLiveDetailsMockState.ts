'use client';

import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { isBroadcastInChannel } from '@/api/clipEditor';
import { useMe } from '@/features/auth/useSession';
import { useActiveChannel } from '@/features/channel/useActiveChannel';
import { useLiveData } from './liveDataStore';

// 방송 정보(카테고리·태그)는 clip의 broadcast-info 창구에서 온다(POK-251).
// latest가 없으면 칩을 하나도 안 그리고 화면이 그 까닭을 말한다(대시보드의 missingWord: 확인 중·기록 없음·수집 전).
// 카드 표기값(cardVisuals)은 어느 창구도 주지 않으므로
// 빈 맵이다 — highlightCardView의 폴백이 계약 필드만으로 카드를 세운다.

export interface StreamMeta {
  /** 방송 카테고리 — 없으면 빈 문자열 */
  category: string;
  tags: string[];
  /**
   * 정보 바 왼쪽 동그라미: 그 방송 스트리머의 PokeClip 프로필(이름 · 사진, POK-457). 사진은 직접 올린 것, 없으면 구글 사진이고 편집자 ·
   * 채널 아바타(#372)와 같은 출처다. 사진이 없으면 이름 첫 글자다. 누구 방송인지 아직 모르면 null이다. 옛 고정 문구 「이미지
   * 없음」은 실제 그림과 이어진 적이 없어 걷었다. 플랫폼(치지직 · SOOP) 사진은 쓰지 않는다
   */
  streamer: { name: string; photo: string | null } | null;
  /** broadcast-info의 latest가 있었는가. false면 화면이 그 까닭(확인 중·기록 없음·수집 전)을 말한다 */
  collected: boolean;
}

export interface CardVisual {
  /** 카드 길이 표기 — `0:42` */
  duration: string;
  /** 감지 사유 캡슐 — 채팅 급증·키워드 감지·시청자 급증·수동 마킹 */
  reason: string;
  /** 방송 타임라인에서의 위치 0..100 (썸네일 하단 인디케이터) */
  posPercent: number;
  /** 미니 파형 진폭 0..1 — 뷰박스는 그리는 쪽이 정한다 */
  spark: readonly number[];
  timeAgo: string;
  /** 클립 생성 진행률 0..100 — 만드는 중인 카드에만 */
  progress?: number;
}

export interface LiveDetailsMockState {
  streamMeta: StreamMeta;
  cardVisuals: Record<string, CardVisual>;
}

const EMPTY_VISUALS: Record<string, CardVisual> = {};

export function useLiveDetailsMockState(): LiveDetailsMockState {
  const { info, relation, streamId } = useLiveData();
  const me = useMe().data;
  const channel = useActiveChannel();
  // 방송 줄은 스트리머 번호를 싣지 않는다. 내 방송(OWNER)이면 나, 위임받은 방송(EDITOR)이면 지금 고른 채널의 주인인데, 주소로 연
  // 방송은 고른 채널의 것이 아닐 수 있다(방송 조회는 채널을 가리지 않는다). 그래서 그 채널로 물은 목록에 이 방송이 있을 때만 그
  // 주인으로 본다(로컬 리뷰 1라운드: 두 채널의 편집자가 다른 채널 방송을 열면 고른 채널 주인 사진이 붙었다). 방송의 채널은 바뀌지
  // 않으니 한 번만 묻는다. 모르면 비운다(남의 사진을 붙이지 않게)
  const editorChannel = relation === 'EDITOR' && channel.ready && channel.relation === 'EDITOR';
  const inChannel = useQuery({
    queryKey: ['broadcast-in-channel', streamId, channel.streamerId],
    queryFn: () => isBroadcastInChannel(streamId, channel.streamerId!),
    enabled: editorChannel && streamId !== '',
    staleTime: Infinity,
  });
  const delegated = editorChannel && inChannel.data === true;
  const streamerName = relation === 'OWNER' ? (me?.name ?? null) : delegated ? channel.name : null;
  const streamerPhoto =
    relation === 'OWNER' ? (me?.profileImageUrl ?? null) : delegated ? channel.photo : null;
  const streamMeta = useMemo<StreamMeta>(() => {
    const latest = info?.latest ?? null;
    return {
      category: latest?.category ?? '',
      tags: latest?.tags ?? [],
      streamer: streamerName === null ? null : { name: streamerName, photo: streamerPhoto },
      collected: latest !== null,
    };
  }, [info, streamerName, streamerPhoto]);
  return { streamMeta, cardVisuals: EMPTY_VISUALS };
}
