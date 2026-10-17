import type { ReactNode } from 'react';
import clsx from 'clsx';
import { ThumbnailImage } from '@/features/thumbnail/ThumbnailImage';
import styles from './HomeScreen.module.css';

// 16:9 썸네일. 사진(POK-277)이 있으면 칸을 채우고, 없으면 라벨 자리표시를 보인다. 오버레이(children)는 그대로 위에 얹힌다.
export function Thumb({
  label,
  src,
  refreshMs,
  className,
  children,
}: {
  label: string;
  src?: string | null;
  refreshMs?: number;
  className?: string;
  children?: ReactNode;
}) {
  return (
    <div className={clsx(styles.thumb, className)}>
      <ThumbnailImage
        src={src}
        refreshMs={refreshMs}
        fallback={
          <span className={styles.thumbLabel} aria-hidden>
            {label}
          </span>
        }
      />
      {children}
    </div>
  );
}
