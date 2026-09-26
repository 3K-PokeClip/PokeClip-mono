'use client';

import { useMemo } from 'react';
import { useLiveData } from './liveDataStore';

// 방송 정보(카테고리·태그)는 clip의 broadcast-info 창구에서 온다(POK-251).
// 수집기 PR-C가 아직 없어 latest는 대개 null이다 — 그때는 칩을 하나도 안 그리고
// 화면이 「방송 정보 수집 전」이라고 말한다. 카드 표기값(cardVisuals)은 어느 창구도 주지 않으므로
// 빈 맵이다 — highlightCardView의 폴백이 계약 필드만으로 카드를 세운다.

export interface StreamMeta {
  /** 방송 카테고리 — 없으면 빈 문자열 */
  category: string;
  tags: string[];
  /** 카테고리 썸네일 자리 표시 문구 (이미지 소스가 아직 없다) */
  thumbLabel: string;
  /** broadcast-info의 latest가 있었는가 — false면 화면이 「수집 전」을 말한다 */
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
  const { info } = useLiveData();
  const streamMeta = useMemo<StreamMeta>(() => {
    const latest = info?.latest ?? null;
    return {
      category: latest?.category ?? '',
      tags: latest?.tags ?? [],
      thumbLabel: '이미지 없음',
      collected: latest !== null,
    };
  }, [info]);
  return { streamMeta, cardVisuals: EMPTY_VISUALS };
}
