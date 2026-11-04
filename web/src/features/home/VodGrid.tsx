import Link from 'next/link';
import clsx from 'clsx';
import { ChevronRight, Clapperboard, Sparkles, VideoOff } from 'lucide-react';
import { Badge } from '@/ui';
import { TOUR_TARGET } from '@/features/onboarding/tourSteps';
import styles from './HomeScreen.module.css';
import { Thumb } from './Thumb';
import type { HomeVod } from './useHomeMockState';

// 디자인 1a ③ — 지난 방송 격자. clip `state=past` 실값이다.
// 카드 한 장이 그 방송을 말한다: 장면 사진 · 제목 · 시작 시각(같은 제목을 가른다) · 찾은 카드와 만든 클립 수.
// 첫 응답 전엔 홈이 큰 칸 스켈레톤만 그려 이 격자는 늘 읽은 뒤에 선다 — 「불러오는 중」 칸이 없다(POK-378).
export function VodGrid({ vods }: { vods: HomeVod[] }) {
  return (
    <section aria-label="지난 방송" data-tour-id={TOUR_TARGET.vodGrid}>
      <div className={styles.vodHeader}>
        <h2 className={styles.sectionLabel}>지난 방송</h2>
        <span className={styles.vodKeepNote}>60일 보관</span>
        <Link href="/broadcast/vod" className={styles.vodAllLink} aria-label="지난 방송 전체 보기">
          전체 보기
          <ChevronRight size={14} aria-hidden />
        </Link>
      </div>
      {vods.length === 0 ? (
        <p className={styles.emptyState}>방송이 끝나면 다시 볼 수 있는 VOD가 여기 쌓여요.</p>
      ) : (
        <ul className={styles.vodGrid}>
          {vods.map((vod) => (
            <li key={vod.id} className={styles.vodCard}>
              {vod.href ? (
                <Link href={vod.href} className={styles.vodLink}>
                  <VodCardBody vod={vod} />
                </Link>
              ) : (
                // 보관이 끝나 열 것이 없다: 링크를 안 만든다(지난 방송 목록과 같다, POK-254)
                <div className={styles.vodLink}>
                  <VodCardBody vod={vod} />
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function VodCardBody({ vod }: { vod: HomeVod }) {
  return (
    <>
      <Thumb
        // 카드가 하나도 없으면 사진(가장 크게 터진 장면)도 올 수 없다
        label={
          vod.cards === 0 ? (
            <span className={styles.vodNoPhoto}>
              <VideoOff size={18} />
              장면 사진 없음
            </span>
          ) : (
            '썸네일 준비 중'
          )
        }
        src={vod.thumbnailUrl}
      >
        {vod.badge?.kind === 'preparing' ? (
          <span className={styles.overlayPillTopLeft}>준비 중</span>
        ) : null}
        {vod.badge?.kind === 'dday' ? (
          <span className={styles.overlayTopLeft}>
            <Badge tone="danger" variant="solid" size="sm">
              {vod.badge.label}
            </Badge>
          </span>
        ) : null}
        {vod.badge?.kind === 'expired' ? (
          <span className={styles.overlayTopLeft}>
            <Badge tone="danger" variant="soft" size="sm">
              보관 만료
            </Badge>
          </span>
        ) : null}
        {vod.duration ? <span className={styles.durationPill}>{vod.duration}</span> : null}
      </Thumb>
      <div className={styles.vodText}>
        <div className={styles.vodTitle}>{vod.title}</div>
        <div className={styles.vodMeta}>{vod.when}</div>
        <VodStats vod={vod} />
      </div>
    </>
  );
}

/** 찾은 카드 · 만든 클립 — 클립이 0이면 아직 손대지 않은 방송이 눈에 띈다 */
function VodStats({ vod }: { vod: HomeVod }) {
  if (vod.cards === 0)
    return <div className={clsx(styles.vodStats, styles.vodStatsEmpty)}>찾은 장면 없음</div>;
  return (
    <div className={styles.vodStats}>
      <span className={styles.vodStat}>
        <Sparkles size={12} aria-hidden className={styles.vodStatCardIcon} />
        카드 <b>{vod.cards}</b>
      </span>
      <span className={styles.vodStat}>
        <Clapperboard size={12} aria-hidden className={styles.vodStatClipIcon} />
        클립 <b>{vod.clips ?? '–'}</b>
      </span>
    </div>
  );
}
