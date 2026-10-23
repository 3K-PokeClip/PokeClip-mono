import { useEffect, useRef } from 'react';
import { Camera } from 'lucide-react';
import styles from './StudioScreen.module.css';

// 영상 줄 앞 썸네일 칸(POK-389) — 쇼츠 서비스들의 「커버」 칸. 고른 순간의 미리보기 프레임을 작은 그림으로 남긴다.
// 그림은 캔버스에 직접 옮겨 그리고 데이터 주소로 바꾸지 않는다(다른 출처 영상이라도 화면에 그리는 것은 된다).
// 누르면 그 장면으로 간다.

export function ThumbnailTile({
  seconds,
  label,
  video,
  onSeek,
}: {
  /** 고른 장면(소스 축 초). 없으면 칸을 그리지 않는다 */
  seconds: number | null;
  /** 쇼츠 안 시각 표기(0:05) */
  label: string;
  video: HTMLVideoElement | null;
  onSeek: (seconds: number) => void;
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  // 고른 순간 = 재생 위치가 그 장면에 있는 순간이라 지금 프레임을 그대로 옮긴다
  useEffect(() => {
    const canvas = canvasRef.current;
    if (seconds === null || canvas === null || video === null || video.videoWidth === 0) return;
    const context = canvas.getContext('2d');
    context?.drawImage(video, 0, 0, canvas.width, canvas.height);
  }, [seconds, video]);

  if (seconds === null) return null;
  return (
    <button
      type="button"
      className={styles.thumbnailTile}
      aria-label={`썸네일 장면 ${label}`}
      title={`썸네일 장면 ${label}`}
      onClick={() => onSeek(seconds)}
    >
      <canvas
        ref={canvasRef}
        width={48}
        height={27}
        className={styles.thumbnailCanvas}
        aria-hidden
      />
      <Camera size={10} className={styles.thumbnailBadge} aria-hidden />
    </button>
  );
}
