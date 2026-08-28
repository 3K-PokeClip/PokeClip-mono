'use client';

import { ChartLine } from 'lucide-react';
import styles from './LiveScreen.module.css';
import {
  TIMELINE_HEIGHT,
  TIMELINE_WIDTH,
  chatVolumeLine,
  highlightMarkers,
  toAreaAttribute,
  toPointsAttribute,
  type Point,
} from './statsTimeline';
import type { CategorySegment, LiveMetric } from './useLiveStatsMockState';
import type { ChatVolumeSeries } from './useLiveMockState';

// 실시간 통계(시안 1b) — 채팅량·시청자·하이라이트·후원을 한 타임라인에 겹쳐 놓는다.
// 겹쳐 두는 이유가 이 패널의 전부다: "채팅이 튄 곳에 카드가 생겼고 후원도 그때 들어왔다"를
// 눈으로 잇게 하는 것. 그래서 채팅량 선과 하이라이트 점은 카드 목록과 같은 원천
// (동결 계약 chatVolume)에서 파생한다 — statsTimeline 참고.

const LEGEND = [
  { label: '채팅량', className: styles.legendChat },
  { label: '시청자', className: styles.legendViewer },
  { label: '하이라이트', className: styles.legendHighlight },
  { label: '후원', className: styles.legendDonation },
];

/** 후원 마커 — 시안의 마름모 */
function donationDiamond([x, y]: Point): string {
  const r = 7;
  return `M${x - r} ${y} L${x} ${y - r} L${x + r} ${y} L${x} ${y + r} Z`;
}

export function LiveStatsPanel({
  chatVolume,
  viewerLine,
  donations,
  categorySegments,
  metrics,
  highlightSummary,
}: {
  chatVolume: ChatVolumeSeries;
  viewerLine: readonly Point[];
  donations: readonly Point[];
  categorySegments: CategorySegment[];
  metrics: LiveMetric[];
  /** 카드 목록에서 센 값 — 목업에 박으면 필터 표기와 어긋난다 */
  highlightSummary: { total: number; auto: number; manual: number };
}) {
  const chatLine = chatVolumeLine(chatVolume);
  const markers = highlightMarkers(chatVolume);

  return (
    <section className={styles.statsPanel} aria-label="실시간 통계">
      <div className={styles.statsHeader}>
        <ChartLine size={15} aria-hidden className={styles.statsHeaderIcon} />
        <h2 className={styles.statsHeading}>실시간 통계</h2>
        <div className={styles.statsLegend}>
          {LEGEND.map(({ label, className }) => (
            <span key={label} className={styles.legendItem}>
              <span className={className} aria-hidden />
              {label}
            </span>
          ))}
        </div>
      </div>
      <div className={styles.statsBody}>
        <div className={styles.statsChartCol}>
          <svg
            viewBox={`0 0 ${TIMELINE_WIDTH} ${TIMELINE_HEIGHT}`}
            preserveAspectRatio="none"
            className={styles.statsSvg}
            role="img"
            aria-label={`방송 타임라인 — 채팅량과 시청자 추이, 하이라이트 ${markers.length}곳, 후원 ${donations.length}회`}
          >
            <polygon points={toAreaAttribute(chatLine)} className={styles.statsChatArea} />
            <polyline
              points={toPointsAttribute(chatLine)}
              className={styles.statsChatLine}
              vectorEffect="non-scaling-stroke"
            />
            <polyline
              points={toPointsAttribute(viewerLine)}
              className={styles.statsViewerLine}
              vectorEffect="non-scaling-stroke"
            />
            {markers.map(([x, y]) => (
              <circle key={`hl-${x}-${y}`} cx={x} cy={y} r={5} className={styles.statsHighlight} />
            ))}
            {donations.map((point) => (
              <path
                key={`donation-${point[0]}-${point[1]}`}
                d={donationDiamond(point)}
                className={styles.statsDonation}
              />
            ))}
            {/* 지금 이 순간 — 오른쪽 끝의 세로선 */}
            <line
              x1={TIMELINE_WIDTH}
              y1={0}
              x2={TIMELINE_WIDTH}
              y2={TIMELINE_HEIGHT}
              className={styles.statsNow}
            />
          </svg>
          <div className={styles.categoryBar}>
            {categorySegments.map((segment) => (
              <span
                key={segment.label}
                className={styles.categorySegment}
                style={{ width: `${segment.percent}%` }}
              >
                {segment.label} {segment.minutes}분
              </span>
            ))}
          </div>
          <div className={styles.statsAxis}>
            {chatVolume.timeLabels.map((label) => (
              <span key={label}>{label}</span>
            ))}
          </div>
        </div>
        <dl className={styles.statsMetrics}>
          {metrics.map((metric) => (
            <div key={metric.label} className={styles.metricRow}>
              <dt className={styles.metricLabel}>{metric.label}</dt>
              <dd className={styles.metricValue}>{metric.value}</dd>
            </div>
          ))}
          <div className={styles.metricRow}>
            <dt className={styles.metricLabel}>하이라이트</dt>
            {/* 시안은 내역을 숫자 앞에 둔다 — 총계가 다른 지표들과 같은 오른쪽 끝에 서야 읽힌다 */}
            <dd className={styles.metricValue}>
              <span className={styles.metricNote}>
                자동 {highlightSummary.auto} · 수동 {highlightSummary.manual}
              </span>
              <span>{highlightSummary.total}</span>
            </dd>
          </div>
        </dl>
      </div>
    </section>
  );
}
