import type { ReactNode } from 'react';
import clsx from 'clsx';
import { Skeleton } from '@/ui';
import { ThumbnailImage } from '@/features/thumbnail/ThumbnailImage';
import styles from './HomeScreen.module.css';

// 16:9 썸네일. 사진(POK-277)이 있으면 칸을 채우고, 없으면 라벨 자리표시를 보인다. 오버레이(children)는 그대로 위에 얹힌다.
// shimmer를 켜면 사진을 내려받는 동안 칸 전체가 반짝이는 회색이다(방송 정보 막대와 같은 Skeleton). 빈 칸으로 두면 사진이 없는
// 방송처럼 읽힌다
export function Thumb({
  label,
  src,
  refreshMs,
  shimmer = false,
  photoPending = false,
  className,
  children,
}: {
  /** 사진이 없을 때 자리표시. 글자나 아이콘 */
  label: ReactNode;
  src?: string | null;
  refreshMs?: number;
  /** 사진을 내려받는 동안 반짝이는 회색 칸을 보인다 */
  shimmer?: boolean;
  /** 사진이 아직 없지만 곧 온다(라이브 첫 사진). 자리표시 대신 반짝이는 회색 칸이다 */
  photoPending?: boolean;
  className?: string;
  children?: ReactNode;
}) {
  const loading = (
    <Skeleton
      width="100%"
      height="100%"
      radius="none"
      className={styles.thumbLoading}
      data-testid="thumb-loading"
    />
  );
  return (
    <div className={clsx(styles.thumb, className)}>
      <ThumbnailImage
        src={src}
        refreshMs={refreshMs}
        placeholder={shimmer ? loading : undefined}
        fallback={
          shimmer && photoPending && !src ? (
            loading
          ) : (
            <span className={styles.thumbLabel} aria-hidden>
              {label}
            </span>
          )
        }
      />
      {children}
    </div>
  );
}
