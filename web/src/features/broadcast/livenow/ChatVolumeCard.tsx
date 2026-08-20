import { Card } from '@/ui';
import styles from './LiveScreen.module.css';
import type { ChatVolumeSeries } from './useLiveMockState';

// 디자인 1b — 채팅량 카드. 실시간 갱신·마커는 감지 파이프라인 연동에서 데이터만 바뀐다.
export function ChatVolumeCard({ series }: { series: ChatVolumeSeries }) {
  const line = series.points.map(([x, y]) => `${x},${y}`).join(' ');

  return (
    <Card variant="outline" padding={0}>
      <div className={styles.chartHeader}>
        <h2 className={styles.chartTitle}>채팅량</h2>
        <span className={styles.chartNote}>실시간 갱신 · 마커 = 자동 감지 시점</span>
        <span className={styles.chartLegend}>
          <span className={styles.chartLegendSwatch} aria-hidden />
          하이라이트
        </span>
      </div>
      <div className={styles.chartBody}>
        <svg
          viewBox="0 0 800 90"
          preserveAspectRatio="none"
          className={styles.chartSvg}
          role="img"
          aria-label="채팅량 추이 — 하이라이트 감지 시점 4곳"
        >
          <polygon points={`${line} 800,90 0,90`} className={styles.chartArea} />
          <polyline points={line} className={styles.chartLine} vectorEffect="non-scaling-stroke" />
          {series.markers.map(([x, y]) => (
            <circle key={`${x}-${y}`} cx={x} cy={y} r={4.5} className={styles.chartMarker} />
          ))}
          <line x1={800} y1={0} x2={800} y2={90} className={styles.chartEdge} />
        </svg>
        <div className={styles.chartAxis}>
          {series.timeLabels.map((label) => (
            <span key={label}>{label}</span>
          ))}
        </div>
      </div>
    </Card>
  );
}
