import Link from 'next/link';
import { Radio } from 'lucide-react';
import { Skeleton, Tag } from '@/ui';
import { LIVE_REFRESH_MS } from '@/features/thumbnail/thumbnailUrl';
import styles from './HomeScreen.module.css';
import { Thumb } from './Thumb';
import type { LiveNow } from './useHomeMockState';

// 디자인 1a ② — 방송 중일 때만 노출되는 라이브 밴드. 홈 개편(POK-378)부터 큰 칸 아래 본문 맨 위에 선다.
// 두 액션 모두 라이브 대시보드(1b)로 간다 — "카드 검토"는 우측 카드 목록이 목적지다.
// 클립 완료 수는 줄 창구가 없어 「준비 중」으로 적는다.
//
// 방송 정보(플랫폼 · 제목 · 시청자 수)는 수집기가 방송을 찾아야 온다. 방송 시작 알림만 온 직후에는 그 칸들이 비는데, 방송 번호 ·
// 기본 플랫폼 · 자리표시 글자로 채우면 진짜 값처럼 읽힌다(POK-449, 2026-10-24 실방송). 그래서 빈 칸은 번쩍이는 회색 막대로 두고
// 「방송 정보를 받아 오고 있어요」 한 줄을 적는다(시안 (가)). 아무 정보도 없으면 영상 칸 배지도 「연결 중」이다. 방송 시작에서
// 2분(INFO_WAIT_MS)이 지나도 비어 있으면 막대를 걷고 못 받았다고 적는다: 끝없이 번쩍이면 곧 온다고 읽힌다
export function LiveNowBand({ live }: { live: LiveNow }) {
  const dashboardHref = `/broadcast/livenow?stream=${encodeURIComponent(live.streamId)}`;
  const missing = live.title === null || live.platform === null || live.viewers === null;
  const waiting = missing && !live.infoLate;
  const connecting =
    waiting && live.title === null && live.platform === null && live.viewers === null;
  return (
    <section aria-label="라이브" className={styles.liveSection}>
      <h2 className={styles.sectionLabel}>
        <span className={styles.livePulseDot} aria-hidden />
        라이브
      </h2>
      <div className={styles.liveBand}>
        <Thumb
          label={<Radio size={30} />}
          src={live.thumbnailUrl}
          refreshMs={LIVE_REFRESH_MS}
          className={styles.liveThumb}
        >
          {connecting ? (
            <span className={styles.livePill}>
              <span className={styles.connectingDot} aria-hidden />
              연결 중
            </span>
          ) : (
            <span className={styles.livePill}>LIVE {live.uptimeLabel}</span>
          )}
          {live.viewers !== null ? (
            <span className={styles.viewerPill}>시청자 {live.viewers}</span>
          ) : waiting ? (
            <span className={styles.viewerPill} data-testid="live-viewers-pending">
              <Skeleton width={56} height={10} className={styles.pillBar} />
            </span>
          ) : null}
        </Thumb>
        <div className={styles.liveBody}>
          <div className={styles.liveMetaRow}>
            {live.platform ? (
              <Tag variant="soft" size="sm">
                {live.platform}
              </Tag>
            ) : waiting ? (
              <Skeleton width={52} height={20} radius="pill" data-testid="live-platform-pending" />
            ) : null}
            <span className={styles.liveMetaText}>
              {live.editorsOnline
                ? `${live.startedNote} · 편집자 ${live.editorsOnline}명 접속 중`
                : live.startedNote}
            </span>
          </div>
          {live.title !== null ? (
            <div className={styles.liveTitle}>{live.title}</div>
          ) : waiting ? (
            <div className={styles.liveTitleBars} data-testid="live-title-pending">
              <Skeleton width="88%" height={16} />
              <Skeleton width="62%" height={16} />
            </div>
          ) : null}
          {missing ? (
            <p className={styles.liveInfoNote} role="status">
              {waiting ? '방송 정보를 받아 오고 있어요' : lateNote(live)}
            </p>
          ) : null}
          <div className={styles.liveStats}>
            감지된 카드 <b>{live.detectedCards}</b> · 클립 완료{' '}
            {live.completedClips === null ? (
              <span className={styles.emptyInline}>준비 중</span>
            ) : (
              <b>{live.completedClips}</b>
            )}
          </div>
          <div className={styles.liveActions}>
            <Link href={dashboardHref} className={styles.solidLink}>
              대시보드 열기
            </Link>
            <Link href={dashboardHref} className={styles.ghostLink}>
              카드 검토
            </Link>
          </div>
        </div>
      </div>
    </section>
  );
}

/**
 * 시한이 지나도 빈 칸이 있을 때의 말. 다 비었으면 「방송 정보」, 일부만 비었으면 빈 것만 이름 댄다: 제목 · 플랫폼이 보이는데
 * 「방송 정보를 못 받았다」고 하면 화면과 어긋난다(로컬 리뷰 1라운드, 수집기가 시청자 수만 비우는 회차가 있다)
 */
function lateNote(live: LiveNow): string {
  const names = [
    live.platform === null ? '플랫폼' : null,
    live.title === null ? '제목' : null,
    live.viewers === null ? '시청자 수' : null,
  ].filter((n): n is string => n !== null);
  return `${names.length === 3 ? '방송' : names.join(' · ')} 정보를 아직 받지 못했어요`;
}
