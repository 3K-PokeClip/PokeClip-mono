import type { ReactNode } from 'react';
import clsx from 'clsx';
import styles from './HomeScreen.module.css';

// 16:9 썸네일 플레이스홀더 — 썸네일 파이프라인이 아직 없어 라벨만 보여준다.
// 실이미지가 생기면 내부만 next/image로 교체하고 오버레이(children)는 그대로 둔다.
export function Thumb({
  label,
  className,
  children,
}: {
  label: string;
  className?: string;
  children?: ReactNode;
}) {
  return (
    <div className={clsx(styles.thumb, className)}>
      <span className={styles.thumbLabel} aria-hidden>
        {label}
      </span>
      {children}
    </div>
  );
}
