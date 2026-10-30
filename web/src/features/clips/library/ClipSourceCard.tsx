'use client';

import { useId, useState } from 'react';
import Link from 'next/link';
import { ArrowUpRight, Tv } from 'lucide-react';
import { Skeleton } from '@/ui';
import { broadcastLengthLabel, instantText, sceneLabel, sourceLinkOf } from './libraryView';
import type { LibraryClip } from './useLibraryMockState';
import { useClipSource } from './useClipSource';
import styles from './LibraryScreen.module.css';

// 상세 패널 원본 방송 카드(POK-511 시안 B, 2026-10-30 사용자 선택). 예전에는 「원본 방송 10월 17일 방송」 한 줄뿐이라 어느 방송의
// 어느 장면인지 몰랐다. 카테고리 포스터 + 방송 제목 + 카테고리 · 플랫폼 + 방송일 · 길이를 한 장으로 모으고, 아래에 장면 위치와 원본
// 방송으로 가는 길을 둔다. 제목 · 카테고리는 그 장면 때의 값이다(libraryView.clipSourceOf).

export function ClipSourceCard({ clip, now }: { clip: LibraryClip; now: Date }) {
  const entry = clip.entry;
  const { source, pending } = useClipSource(entry);
  const headId = useId();
  // 못 읽은 포스터 주소 — 그 주소인 동안은 자리표시 그림이다(정보 바 카테고리 사진과 같은 처리)
  const [failedImage, setFailedImage] = useState<string | null>(null);
  const image =
    source?.categoryImageUrl && source.categoryImageUrl !== failedImage
      ? source.categoryImageUrl
      : null;
  const when = entry
    ? [instantText(entry.broadcast.startedAt, now, false), broadcastLengthLabel(entry.broadcast)]
        .filter(Boolean)
        .join(' · ')
    : '';
  const kind = [source?.category, source?.platform].filter(Boolean).join(' · ');
  const scene = entry?.cut ? sceneLabel(entry.cut) : null;
  const link = entry ? sourceLinkOf(entry, now) : null;

  return (
    <section className={styles.sourceCard} aria-labelledby={headId}>
      <div className={styles.sourceTop}>
        <span className={styles.sourcePoster} aria-hidden="true">
          {image ? (
            // 플랫폼이 준 사진 주소를 그대로 그린다: Next 이미지 최적화를 거치면 주소마다 허용 목록이 필요하다(StreamInfoBar 선례)
            // eslint-disable-next-line @next/next/no-img-element
            <img src={image} alt="" onError={() => setFailedImage(image)} />
          ) : (
            <Tv size={16} />
          )}
        </span>
        <div className={styles.sourceBody}>
          <span id={headId} className={styles.sourceKicker}>
            원본 방송
          </span>
          {pending ? (
            <Skeleton height={14} width="80%" />
          ) : (
            <p className={styles.sourceTitle}>{source?.title ?? clip.sourceLabel}</p>
          )}
          {kind ? <p className={styles.sourceLine}>{kind}</p> : null}
          {when ? <p className={styles.sourceLine}>{when}</p> : null}
        </div>
      </div>
      {scene || link ? (
        <div className={styles.sourceFoot}>
          {scene ? (
            <span>
              장면 <span className={styles.sourceScene}>{scene}</span>
            </span>
          ) : null}
          {link ? (
            <Link href={link.href} className={styles.sourceLink}>
              {link.label}
              <ArrowUpRight size={12} aria-hidden />
            </Link>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}
