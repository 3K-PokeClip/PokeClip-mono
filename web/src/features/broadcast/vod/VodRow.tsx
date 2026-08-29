import Link from 'next/link';
import clsx from 'clsx';
import { ChevronRight, Download } from 'lucide-react';
import { Badge, IconButton, Progress, Spinner, Tag, VisuallyHidden } from '@/ui';
import type { VodBroadcast, VodRowVisual } from './useVodListMockState';
import { dateLabel, ddayFor, durationLabel, rowViewFor } from './vodListView';
import styles from './VodListScreen.module.css';

// 시안 1f의 목록 행 하나. 표시 규칙은 전부 vodListView가 정한다 — 이 파일은 그리기만 한다.

/**
 * 행 전체가 클릭되지만 링크는 제목 하나뿐이다. 행을 통째로 <Link>로 감싸면 안의 다운로드
 * 버튼이 링크 안의 버튼이 되어(axe nested-interactive) 스크린리더에서 무엇을 누르는지
 * 흐려진다 — 「카드 전체가 아니라 썸네일만 버튼이다」(HighlightCard)와 같은 이유다.
 * 링크의 ::after가 행을 덮고, 오른쪽 조작부가 그 위층에 앉는다.
 */
export function VodRow({
  item,
  visual,
  now,
}: {
  item: VodBroadcast;
  visual: VodRowVisual | undefined;
  now: Date;
}) {
  const view = rowViewFor(item, visual);
  const title = visual?.title ?? '제목 없는 방송';
  const duration = durationLabel(visual?.durationSec ?? null);
  const date = dateLabel(item);
  const dday = ddayFor(item.vodExpiresAt, now);
  const preparing = view.kind === 'preparing';
  // 시안의 붉은 테두리 행 — 곧 사라질 것을 색과 문장 양쪽으로 말한다
  const urgent = dday.kind === 'active' && dday.urgent;

  const meta = preparing
    ? '방금 종료 · VOD 준비 중 · 준비되면 알려드릴게요'
    : [
        date ?? '방송일 미상',
        // 만료 임박 행만 무엇을 잃는지 적는다 — 평소엔 오른쪽 카드 수 태그로 충분하다
        urgent && visual?.unsavedCardCount
          ? `저장하지 않은 카드 ${visual.unsavedCardCount}개가 함께 삭제됩니다`
          : null,
      ]
        .filter(Boolean)
        .join(' · ');

  return (
    <li
      className={clsx(
        styles.row,
        preparing && styles.rowPreparing,
        urgent && !preparing && styles.rowUrgent,
      )}
    >
      <div className={styles.thumb}>
        {preparing ? (
          <Spinner size="sm" label="VOD 준비 중" />
        ) : (
          <span className={styles.thumbLabel} aria-hidden="true">
            썸네일
          </span>
        )}
        {duration ? (
          <span className={styles.durationPill} aria-hidden="true">
            {duration}
          </span>
        ) : null}
      </div>

      <div className={styles.rowText}>
        {preparing ? (
          // 열 VOD가 아직 없다 — aria-disabled 링크로 포커스를 받게 하느니 링크를 안 만든다
          <span className={styles.rowTitle}>{title}</span>
        ) : (
          <Link href={`/broadcast/vod/${item.streamId}`} className={styles.rowLink}>
            {title}
            {duration ? <VisuallyHidden> · 길이 {duration}</VisuallyHidden> : null}
          </Link>
        )}
        <p className={styles.rowMeta}>{meta}</p>
      </div>

      <div className={styles.rowActions}>
        {visual && !preparing ? (
          <Tag variant="soft" size="sm">
            카드 {visual.cardCount}개
          </Tag>
        ) : null}

        {view.kind === 'downloading' ? (
          <div className={styles.rowProgress}>
            <div className={styles.rowProgressHead}>
              <span>풀 VOD 받는 중</span>
              <span className={styles.rowProgressPercent}>{view.progress}%</span>
            </div>
            <Progress value={view.progress} size="sm" label="풀 VOD 저장 진행률" />
          </div>
        ) : null}

        {preparing ? (
          <Badge tone="neutral" variant="soft" size="sm">
            준비 중
          </Badge>
        ) : null}

        {dday.kind === 'active' ? (
          <Badge
            tone={dday.urgent ? 'danger' : 'neutral'}
            variant={dday.urgent ? 'solid' : 'soft'}
            size="sm"
          >
            {dday.label}
          </Badge>
        ) : null}
        {dday.kind === 'expired' ? (
          <Badge tone="danger" variant="soft" size="sm">
            보관 만료
          </Badge>
        ) : null}

        {/*
          다운로드 플로우(화질 선택 → 진행 → 완료)는 기능명세·계약에 없는 신규 기능이라
          POK-226 범위 밖이다. 자리는 시안대로 두되 누를 수는 없다.

          받을 것이 없는 행에는 아예 안 그린다 — 준비 중은 VOD가 없고, 받는 중은 이미 받고 있다.
        */}
        {view.kind === 'ready' ? (
          <IconButton
            variant="ghost"
            size="sm"
            aria-label="풀 버전 다운로드"
            disabled
            className={styles.rowDownload}
          >
            <Download size={15} aria-hidden="true" />
          </IconButton>
        ) : null}

        {/* 열 수 있는 행만 「들어간다」고 말한다 */}
        {preparing ? null : (
          <ChevronRight size={16} className={styles.rowChevron} aria-hidden="true" />
        )}
      </div>
    </li>
  );
}
