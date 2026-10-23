import { useMemo } from 'react';
import { Camera } from 'lucide-react';
import { useSceneFrames } from '../useSceneFrames';
import type { FilmstripSource } from './VideoFilmstrip';
import styles from './StudioScreen.module.css';

// 영상 줄 앞 썸네일 칸(POK-389): 쇼츠 서비스들의 「커버」 칸. 고른 장면을 작은 그림으로 보인다.
// 그림은 필름띠와 같은 장면 그림 훅으로 고른 시각에서 따로 뽑는다: 지금 영상 화면을 찍으면 칸이 다시 그려질 때
// (구간을 줄였다 늘림 등) 고른 장면이 아닌 엉뚱한 장면이 남는다(PR #341 codex). 누르면 그 장면으로 간다.

/** 뽑는 그림 크기(16:9, 칸의 두 배쯤) */
const TILE_SIZE = { width: 96, height: 54 };

export function ThumbnailTile({
  seconds,
  label,
  source,
  onSeek,
}: {
  /** 고른 장면(소스 축 초). 없으면 칸을 그리지 않는다 */
  seconds: number | null;
  /** 쇼츠 안 시각 표기(0:05) */
  label: string;
  /** 받아 둔 녹화 파일. 없으면(목업·아직 못 받음) 그림 없이 칸만 보인다 */
  source: FilmstripSource | null;
  onSeek: (seconds: number) => void;
}) {
  const ms = seconds === null ? null : Math.round(seconds * 1000);
  const wanted = useMemo(() => (ms === null ? [] : [ms]), [ms]);
  const frameSource = useMemo(
    () => (source === null ? null : { ...source, size: TILE_SIZE }),
    // 재료 함수는 매번 새로 와도 같은 파일이면 같은 그림이다(필름띠와 같은 기준)
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [source?.src, source?.fromSeconds],
  );
  const frames = useSceneFrames(frameSource, wanted, null);

  if (seconds === null || ms === null) return null;
  const url = frames.frameOf(ms);
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
      {url !== null ? <img src={url} alt="" className={styles.thumbnailCanvas} /> : null}
      <Camera size={10} className={styles.thumbnailBadge} aria-hidden />
    </button>
  );
}
