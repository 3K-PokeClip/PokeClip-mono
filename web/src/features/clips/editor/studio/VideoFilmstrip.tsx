import { useMemo } from 'react';
import { useSceneFrames, type SceneFrameSource } from '../useSceneFrames';
import styles from './StudioScreen.module.css';

// 타임라인 영상 줄의 필름띠(POK-389, 캡컷처럼 구간을 따라 장면 그림을 이어 붙인다). 그림은 업로드 창의 장면 그림 훅(POK-381)을
// 그대로 쓴다: 편집기가 받아 둔 녹화 파일로 영상 노드를 하나 더 만들어 칸마다 옮겨 가며 작게 뽑는다.

/** 칸 수. 줄 폭(약 1,200px)에서 칸 하나가 16:9 그림 크기쯤이다 */
export const FILMSTRIP_TILES = 20;
/** 칸 시각 눈금. 확대·이동으로 창이 조금 움직여도 같은 시각 그림을 다시 쓴다 */
const SNAP_MS = 250;
/** 뽑는 그림 크기(16:9, 줄 높이의 두 배쯤이라 고밀도 화면에서도 덜 흐리다) */
const TILE_SIZE = { width: 96, height: 54 };

export interface FilmstripSource extends Omit<SceneFrameSource, 'size'> {
  /** 받아 둔 녹화 파일이 덮는 구간(편집기 초). 밖의 칸은 그림이 없다 */
  fromSeconds: number;
  toSeconds: number;
}

/** 칸마다 가운데 시각(편집기 ms). 받아 둔 영상 밖이면 null */
export function filmstripTimesMs(
  view: { startSeconds: number; endSeconds: number },
  source: FilmstripSource,
  tiles = FILMSTRIP_TILES,
): (number | null)[] {
  const span = view.endSeconds - view.startSeconds;
  return Array.from({ length: tiles }, (_, i) => {
    const seconds = view.startSeconds + ((i + 0.5) * span) / tiles;
    const ms = Math.round((seconds * 1000) / SNAP_MS) * SNAP_MS;
    return ms >= source.fromSeconds * 1000 && ms < source.toSeconds * 1000 ? ms : null;
  });
}

export function VideoFilmstrip({
  source,
  view,
}: {
  source: FilmstripSource;
  view: { startSeconds: number; endSeconds: number };
}) {
  const times = useMemo(
    () => filmstripTimesMs(view, source),
    // 창이 바뀔 때만 다시 나눈다(재료 함수는 매번 새로 와도 덮는 구간이 같으면 칸은 같다)
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [view.startSeconds, view.endSeconds, source.fromSeconds, source.toSeconds],
  );
  const wanted = useMemo(() => times.filter((t): t is number => t !== null), [times]);
  const frameSource = useMemo(
    () => ({ ...source, size: TILE_SIZE }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [source.src, source.fromSeconds],
  );
  const frames = useSceneFrames(frameSource, wanted, null);

  return (
    <div className={styles.filmstrip} aria-hidden>
      {times.map((ms, i) => {
        const url = ms === null ? null : frames.frameOf(ms);
        return (
          <span key={i} className={styles.filmstripTile} data-filmstrip-tile>
            {/* 뽑은 장면(data:)이라 next/image가 다룰 대상이 아니다 */}
            {/* eslint-disable-next-line @next/next/no-img-element */}
            {url !== null ? <img src={url} alt="" /> : null}
          </span>
        );
      })}
    </div>
  );
}
