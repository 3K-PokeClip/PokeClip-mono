import { Camera } from 'lucide-react';
import styles from './StudioScreen.module.css';

// 영상 줄 앞 썸네일 칸(POK-389): 쇼츠 서비스들의 「커버」 칸. 고른 장면을 작은 그림으로 보인다.
// 그림은 필름띠와 같은 장면 그림 훅(useFilmstripFrames)이 고른 시각에서 따로 뽑아 넘긴다: 지금 영상 화면을 찍으면 칸이 다시
// 그려질 때(구간을 줄였다 늘림 등) 고른 장면이 아닌 엉뚱한 장면이 남는다(PR #341 codex). 누르면 그 장면으로 간다.

export function ThumbnailTile({
  seconds,
  label,
  frameUrl,
  onSeek,
}: {
  /** 고른 장면(소스 축 초). 없으면 칸을 그리지 않는다 */
  seconds: number | null;
  /** 쇼츠 안 시각 표기(0:05) */
  label: string;
  /** 고른 장면의 그림. 아직이거나 못 뽑았으면(목업·녹화를 아직 못 받음) null이고 그림 없이 칸만 보인다 */
  frameUrl: string | null;
  onSeek: (seconds: number) => void;
}) {
  if (seconds === null) return null;
  return (
    <button
      type="button"
      className={styles.thumbnailTile}
      aria-label={`썸네일 장면 ${label}`}
      title={`썸네일 장면 ${label}`}
      onClick={() => onSeek(seconds)}
    >
      {/* 뽑은 장면(data:)이라 next/image가 다룰 대상이 아니다 */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      {frameUrl !== null ? <img src={frameUrl} alt="" className={styles.thumbnailCanvas} /> : null}
      <Camera size={10} className={styles.thumbnailBadge} aria-hidden />
    </button>
  );
}
