'use client';

// 실시간 통계(시안 1b)의 값을 clip 창구에서 만든다(POK-251). 채팅량 선과 하이라이트 마커는 여기 없다 —
// 동결 계약(useLiveMockState.chatVolume)에서 statsTimeline이 파생한다.
// 후원은 chat-chart 버킷에서, 시청자·카테고리는 broadcast-info에서 온다. 시청자 값이 없으면
// 선을 그리지 않고 지표에 「수집 전」이라 적는다 — 가짜 선은 그리지 않는다.
import { useMemo } from 'react';
import { TIMELINE_HEIGHT, TIMELINE_WIDTH, type Point } from './statsTimeline';
import { useLiveData } from './liveDataStore';

export interface CategorySegment {
  label: string;
  minutes: number;
  /** 구간 바에서 차지하는 비율 0..100 */
  percent: number;
}

export interface LiveMetric {
  label: string;
  value: string;
}

export interface LiveStatsMockState {
  /** 시청자 추이 — 시안 뷰박스(860 × 150) 좌표. 수집 전이면 빈 배열 */
  viewerLine: readonly Point[];
  /** 후원이 들어온 시점 마커 — 같은 뷰박스 좌표 */
  donations: readonly Point[];
  categorySegments: CategorySegment[];
  metrics: LiveMetric[];
}

/** 눈금이 1, 2 같은 값으로 튀지 않게 5 단위로 올린다 */
function niceMax(v: number): number {
  if (v <= 5) return 5;
  return Math.ceil(v / 5) * 5;
}

export function useLiveStatsMockState(): LiveStatsMockState {
  const data = useLiveData();

  return useMemo(() => {
    const buckets = data.chart?.buckets ?? [];
    const bucketSeconds = data.chart?.bucketSeconds ?? 30;
    const chatMax = niceMax(Math.max(0, ...buckets.map((b) => b.chats)));
    const n = buckets.length;
    const x = (i: number) => (n <= 1 ? TIMELINE_WIDTH : (i / (n - 1)) * TIMELINE_WIDTH);
    const yChat = (v: number) => TIMELINE_HEIGHT - 6 - (v / chatMax) * (TIMELINE_HEIGHT - 18);

    const donations: Point[] = buckets
      .map((b, i) => (b.donations > 0 ? ([x(i), yChat(b.chats)] as const) : null))
      .filter((p): p is readonly [number, number] => p !== null);

    // 시청자: broadcast-info의 series를 차트 시간축(첫 버킷~지금)에 얹는다
    const series = (data.info?.series ?? []).filter((p) => p.viewers !== null) as {
      observedAt: string;
      viewers: number;
    }[];
    let viewerLine: Point[] = [];
    const firstB = buckets[0];
    const lastB = buckets[n - 1];
    if (series.length > 0 && firstB && lastB) {
      const t0 = Date.parse(firstB.start);
      const t1 = Date.parse(lastB.start) + bucketSeconds * 1000;
      const vmax = Math.max(1, ...series.map((p) => p.viewers));
      viewerLine = series
        .map((p) => {
          const t = Date.parse(p.observedAt);
          if (t < t0 || t > t1) return null;
          return [
            ((t - t0) / (t1 - t0)) * TIMELINE_WIDTH,
            TIMELINE_HEIGHT - 6 - (p.viewers / vmax) * (TIMELINE_HEIGHT - 18),
          ] as const;
        })
        .filter((p): p is readonly [number, number] => p !== null);
    }

    const totalChats = buckets.reduce((a, b) => a + b.chats, 0);
    const totalDonations = buckets.reduce((a, b) => a + b.donations, 0);
    const minutes = Math.max(1, (n * bucketSeconds) / 60);
    const peak = series.length ? Math.max(...series.map((p) => p.viewers)) : null;
    const avg = series.length
      ? Math.round(series.reduce((a, p) => a + p.viewers, 0) / series.length)
      : null;

    const metrics: LiveMetric[] = [
      { label: '최고 시청자', value: peak === null ? '수집 전' : peak.toLocaleString('ko-KR') },
      { label: '평균 시청자', value: avg === null ? '수집 전' : avg.toLocaleString('ko-KR') },
      { label: `최근 ${Math.round(minutes)}분 채팅`, value: totalChats.toLocaleString('ko-KR') },
      { label: '분당 평균 채팅', value: (totalChats / minutes).toFixed(1) },
      { label: '후원', value: `${totalDonations}회` },
    ];

    const category = data.info?.latest?.category ?? null;
    const categorySegments: CategorySegment[] = category
      ? [{ label: category, minutes: Math.round(minutes), percent: 100 }]
      : [];

    return { viewerLine, donations, categorySegments, metrics };
  }, [data]);
}
