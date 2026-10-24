'use client';

// 실시간 통계의 값을 clip 창구에서 만든다(POK-251). 그래프는 칸·시청자·카테고리를 그대로 넘기고(그리는 쪽은 LiveStatsPanel·statsChart),
// 위쪽 숫자 줄의 세 칸(분당 평균 채팅·시청자·후원)은 여기서 말까지 정한다. 하이라이트 칸은 카드 목록에서 센다(LiveScreen).
// 후원은 chat-chart 칸에서, 시청자·카테고리는 broadcast-info에서 온다. 시청자 값이 없으면 선을 그리지 않고 칸에 그 까닭을 적는다
// (missingWord: 확인 중·기록 없음·수집 전). 가짜 선은 그리지 않는다.
import { useMemo } from 'react';
import {
  chartSpan,
  formatPerMinute,
  missingWord,
  trustedViewerSeries,
  useLiveData,
  type LiveBroadcastStatus,
  type LiveChartScope,
} from './liveDataStore';
import { fmtHM, type CategoryMark, type StatsChart, type ViewerPoint } from './statsChart';

/** 숫자 줄 한 칸. 값이 없으면 value가 그 까닭(확인 중 등)이고 muted다 */
export interface StatsCell {
  label: string;
  value: string;
  sub: string | null;
  muted?: boolean;
}

export interface LiveStatsMockState {
  /** 그래프의 칸(epoch ms). 차트를 아직 못 받았으면 null — 막대를 지어내지 않는다 */
  chart: StatsChart | null;
  /** 차트가 실제로 읽은 창. 머리줄 설명과 그래프 끝(「지금」)이 이것을 본다 */
  scope: LiveChartScope | null;
  /** 믿는 시청자 기록 중 잰 값만(빈 관측은 뺀다) */
  viewers: ViewerPoint[];
  /** 카테고리가 바뀐 기록. 수집기가 관측마다 카테고리를 싣기 전에는 마지막 카테고리 하나가 그래프 처음부터다 */
  categories: CategoryMark[];
  chat: StatsCell;
  viewerCell: StatsCell;
  donation: StatsCell;
  /** 믿는 기록에서 마지막으로 잰 시청자 수(빈 관측은 건너뛴다). 정보 바·플레이어 필도 이것을 쓴다 */
  lastViewers: number | null;
  /** 시청자 값이 없을 때의 말(missingWord). 통계 칸과 정보 바가 같은 말을 한다 */
  noViewers: string;
  /**
   * 그래프를 아직 받지 못했고 받을 방송이다. 그래프 자리에 같은 높이의 그래프 모양 스켈레톤이 선다(POK-445). 받기에 실패해도
   * 다음 주기에 다시 읽으므로(useLiveMockState) 이 값이 그대로다
   */
  chartLoading: boolean;
  /**
   * 그래프를 받지 않을 때의 말(chartLoading이 아닌데 그래프가 없다). 볼 수 없게 된 방송(명부에 없다 — 위임 회수·탈퇴)은 그래프를
   * 다시 읽지 않으니 불러온다고 하지 않는다(PR #316 리뷰 2라운드)
   */
  noChart: string;
}

/** 채팅 수의 이름은 차트가 실제로 읽은 창대로다(QA 2026-10-11 리뷰): 방송 전체, 끝 12시간(그보다 긴 방송), 지금 기준 최근 창 */
const CHAT_COUNT_LABEL: Record<LiveChartScope, (minutes: number) => string> = {
  broadcast: () => '방송 중 채팅',
  tail: (minutes) => `마지막 ${minutes}분 채팅`,
  recent: (minutes) => `최근 ${minutes}분 채팅`,
};

/** 후원 칸이 센 창 */
const DONATION_SCOPE: Record<LiveChartScope, (minutes: number) => string> = {
  broadcast: () => '방송 전체',
  tail: (minutes) => `마지막 ${Math.round(minutes / 60)}시간`,
  recent: (minutes) => `최근 ${minutes}분`,
};

/**
 * @param view 화면이 이 방송을 무엇으로 보나(LiveDashboard의 viewStatus. 지난 방송 주소는 명부 답 전에도 끝난 방송이다).
 *   시청자 기록은 정보 바와 같은 규칙으로 믿고(trustedViewerSeries), 없을 때의 말도 같다(missingWord): 칸마다 따로 정해서
 *   한 화면에 「확인 중」과 「수집 전」이 섰다(리뷰 4·5라운드)
 */
export function useLiveStatsMockState(view: LiveBroadcastStatus): LiveStatsMockState {
  // 저장소 전체가 아니라 쓰는 칸에만 묶는다: 지난 방송을 트는 동안 재생 위치(playheadMs)가 매초 바뀌어, 통째로 묶으면 그래프(memo)가
  // 매초 다시 그려졌다(PR #316 리뷰 2라운드)
  const { chart: raw, info, status } = useLiveData();

  return useMemo(() => {
    const first = raw?.buckets[0];
    const last = raw?.buckets.at(-1);
    const chart: StatsChart | null =
      raw && first && last
        ? {
            t0: Date.parse(first.start),
            t1: Date.parse(last.start) + raw.bucketSeconds * 1000,
            bucketSeconds: raw.bucketSeconds,
            buckets: raw.buckets.map((b) => ({
              start: Date.parse(b.start),
              chats: b.chats,
              donations: b.donations,
            })),
          }
        : null;

    const trusted = trustedViewerSeries(view, info);
    const viewers: ViewerPoint[] = (trusted ?? [])
      .filter((p): p is typeof p & { viewers: number } => p.viewers !== null)
      .map((p) => ({ t: Date.parse(p.observedAt), v: p.viewers }));
    const peak = viewers.length ? Math.max(...viewers.map((p) => p.v)) : null;
    const avg = viewers.length
      ? Math.round(viewers.reduce((a, p) => a + p.v, 0) / viewers.length)
      : null;
    const lastViewers = viewers.at(-1)?.v ?? null;
    const noViewers = missingWord(view, trusted !== null);

    // 카테고리 기록: 관측마다 실린 카테고리가 바뀐 자리. 없으면 마지막 카테고리(창과 무관한 방송 정보)를 그래프 처음부터 둔다
    const categories: CategoryMark[] = [];
    for (const p of trusted ?? []) {
      if (!p.category || categories.at(-1)?.name === p.category) continue;
      categories.push({ t: Date.parse(p.observedAt), name: p.category });
    }
    const latestCategory = info?.latest?.category ?? null;
    if (categories.length === 0 && latestCategory && chart)
      categories.push({ t: chart.t0, name: latestCategory });

    const span = raw && chart ? chartSpan(raw) : null;
    const minutes = span ? Math.round(span.minutes) : 0;
    const totalDonations = chart ? chart.buckets.reduce((a, b) => a + b.donations, 0) : 0;
    const lastDonation = chart ? [...chart.buckets].reverse().find((b) => b.donations > 0) : null;

    // 차트가 아직 없으면 수를 지어내지 않는다: 「최근 1분 채팅 0」은 지어낸 1분과 0이다(리뷰 3라운드)
    const chat: StatsCell =
      raw && span
        ? {
            label: '분당 평균 채팅',
            value: formatPerMinute(span.chats / span.minutes),
            sub: `${CHAT_COUNT_LABEL[raw.scope](minutes)} ${span.chats.toLocaleString('ko-KR')}개`,
          }
        : { label: '분당 평균 채팅', value: '확인 중', sub: null, muted: true };

    // 라이브는 지금 값(정보 바 「N명 시청 중」과 같은 수), 끝난 방송은 평균이다: 끝날 때 값은 정보 바가 말한다
    const ended = view === 'ended';
    const headline = ended ? avg : lastViewers;
    const viewerCell: StatsCell = {
      label: ended ? '평균 시청자' : '시청자',
      value: headline === null ? noViewers : headline.toLocaleString('ko-KR'),
      sub:
        peak === null || avg === null
          ? null
          : ended
            ? `최고 ${peak.toLocaleString('ko-KR')}`
            : `최고 ${peak.toLocaleString('ko-KR')} · 평균 ${avg.toLocaleString('ko-KR')}`,
      muted: headline === null,
    };

    const donation: StatsCell =
      raw && chart
        ? {
            label: '후원',
            value: `${totalDonations.toLocaleString('ko-KR')}회`,
            sub: `${DONATION_SCOPE[raw.scope](minutes)}${
              lastDonation ? ` · 마지막 ${fmtHM(lastDonation.start)}` : ''
            }`,
          }
        : { label: '후원', value: '확인 중', sub: null, muted: true };

    return {
      chart,
      scope: raw?.scope ?? null,
      viewers,
      categories,
      chat,
      viewerCell,
      donation,
      lastViewers,
      noViewers,
      chartLoading: chart === null && status !== 'offline',
      noChart: '볼 수 없는 방송이에요',
    };
  }, [raw, info, status, view]);
}
