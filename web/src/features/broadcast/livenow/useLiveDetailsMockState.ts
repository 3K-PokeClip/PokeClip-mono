'use client';

import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { isBroadcastInChannel } from '@/api/clipEditor';
import { useMe } from '@/features/auth/useSession';
import { useActiveChannel } from '@/features/channel/useActiveChannel';
import { categoryAt, categorySpansOf, focusAtMs } from './categoryThumb';
import { viewedSegment } from './broadcastInfoAt';
import { useLiveData } from './liveDataStore';
import { useBroadcastTimeline } from './useBroadcastTimeline';

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
  /**
   * 정보 바 왼쪽 그림의 첫째: 화면이 보고 있는 시점의 카테고리 사진(POK-467). 라이브 실시간이면 지금 구간, 되감았거나 지난 방송이면
   * 그 자리의 구간이다(categoryThumb). 그 구간에 카테고리나 사진 주소가 없거나 모르는 자리면 null이고 프로필(streamer)이 선다
   */
  categoryThumb: { name: string; url: string } | null;
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
  const liveData = useLiveData();
  const { info, relation, streamId } = liveData;
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
  // 그 시점 카테고리 사진(POK-467)과 보는 시점의 카테고리 · 태그(POK-462)는 시점별 기록 창구에서 온다(useBroadcastTimeline). 못 읽으면
  // 프로필 사진과 최신 관측이다
  const timeline = useBroadcastTimeline(streamId, liveData.status);
  const spans = useMemo(() => categorySpansOf(timeline), [timeline]);
  const focus = focusAtMs(liveData);
  const span = categoryAt(spans, focus, info?.platform ?? null);
  const categoryName = span?.name ?? null;
  const categoryUrl = span?.imageUrl ?? null;
  // 라이브를 되감았거나 지난 방송에서 시점을 옮겼으면 그 시점의 카테고리 · 태그다(POK-462). 실시간이면 null이고 최신 관측을 쓴다.
  // 재생 위치는 매초 바뀌지만 같은 구간이면 같은 줄이라 아래 memo가 다시 안 돈다
  const seen = viewedSegment(timeline, info?.platform ?? null, focus);
  const streamMeta = useMemo<StreamMeta>(() => {
    const latest = info?.latest ?? null;
    const shown = seen ?? latest;
    return {
      category: shown?.category ?? '',
      tags: shown?.tags ?? [],
      streamer: streamerName === null ? null : { name: streamerName, photo: streamerPhoto },
      categoryThumb:
        categoryName !== null && categoryUrl !== null
          ? { name: categoryName, url: categoryUrl }
          : null,
      collected: latest !== null,
    };
  }, [info, seen, streamerName, streamerPhoto, categoryName, categoryUrl]);
  return { streamMeta, cardVisuals: EMPTY_VISUALS };
}
