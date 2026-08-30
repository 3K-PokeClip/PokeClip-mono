'use client';

import { useState } from 'react';
import Link from 'next/link';
import clsx from 'clsx';
import { Check, ChevronRight, Download, X } from 'lucide-react';
import {
  Badge,
  Button,
  IconButton,
  Popover,
  Radio,
  RadioGroup,
  Spinner,
  Tag,
  VisuallyHidden,
} from '@/ui';
import type { VodBroadcast, VodDownloadState, VodRowVisual } from './useVodListMockState';
import {
  dateLabel,
  ddayFor,
  durationLabel,
  isOpenable,
  qualityOptionsFor,
  rowViewFor,
} from './vodListView';
import styles from './VodListScreen.module.css';

// 시안 1f의 목록 행 하나. 표시 규칙은 전부 vodListView가 정한다 — 이 파일은 그리기만 한다.

/**
 * 행 전체가 클릭되지만 링크는 제목 하나뿐이다. 행을 통째로 <Link>로 감싸면 안의 버튼이
 * 링크 안의 버튼이 되어(axe nested-interactive) 스크린리더에서 무엇을 누르는지 흐려진다 —
 * 「카드 전체가 아니라 썸네일만 버튼이다」(HighlightCard)와 같은 이유다. 링크의 ::after가
 * 행을 덮고, 오른쪽 조작부가 그 위층에 앉는다.
 */
export function VodRow({
  item,
  visual,
  download,
  now,
  onRequestDownload,
  onCancelDownload,
  onResetDownload,
}: {
  item: VodBroadcast;
  visual: VodRowVisual | undefined;
  download: VodDownloadState;
  now: Date;
  onRequestDownload: (quality: string) => void;
  onCancelDownload: () => void;
  onResetDownload: () => void;
}) {
  const view = rowViewFor(item, download, now);
  const title = visual?.title ?? '제목 없는 방송';
  const duration = durationLabel(visual?.durationSec ?? null);
  const date = dateLabel(item);
  const dday = ddayFor(item.vodExpiresAt, now);
  const preparing = view.kind === 'preparing';
  const openable = isOpenable(view);
  // 시안의 붉은 테두리 행 — 곧 사라질 것을 색과 문장 양쪽으로 말한다
  const urgent = dday.kind === 'active' && dday.urgent;

  const qualities = qualityOptionsFor(visual?.durationSec ?? null);
  const [pickOpen, setPickOpen] = useState(false);
  const [quality, setQuality] = useState(qualities[0]!.id as string);

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

        {/* 받는 중에는 썸네일이 진행률로 덮인다 — 채움이 아래에서 차오른다 (시안 1f ②) */}
        {view.kind === 'downloading' ? (
          <div className={styles.dlOverlay}>
            <div className={styles.dlFill} style={{ height: `${view.progress}%` }} />
            <div className={styles.dlText}>
              <span className={styles.dlPercent}>{view.progress}%</span>
              <span className={styles.dlCaption}>풀 VOD 받는 중</span>
            </div>
          </div>
        ) : null}
      </div>

      <div className={styles.rowText}>
        {/*
          썸네일 위 길이 표시는 aria-hidden이라 듣는 쪽에는 여기서만 남는다. 링크가 있는 행은
          링크 이름에 실어 「제목 · 길이」로 읽히게 하고, 링크가 없는 행(준비 중·만료)도
          길이를 아는 이상 빠뜨리지 않는다.
        */}
        {openable ? (
          <Link href={`/broadcast/vod/${item.streamId}`} className={styles.rowLink}>
            {title}
            {duration ? <VisuallyHidden> · 길이 {duration}</VisuallyHidden> : null}
          </Link>
        ) : (
          // 열 VOD가 없다(준비 중이거나 이미 지워졌다) — aria-disabled 링크로 포커스를
          // 받게 하느니 링크를 안 만든다
          <span className={styles.rowTitle}>
            {title}
            {duration ? <VisuallyHidden> · 길이 {duration}</VisuallyHidden> : null}
          </span>
        )}
        <p className={styles.rowMeta}>{meta}</p>
      </div>

      <div className={styles.rowActions}>
        {visual && !preparing ? (
          <Tag variant="soft" size="sm">
            카드 {visual.cardCount}개
          </Tag>
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

        {/* 받기 전 — 화질을 고르는 팝오버가 이 버튼에 붙는다 */}
        {view.kind === 'ready' ? (
          <Popover open={pickOpen} onOpenChange={setPickOpen} side="bottom" align="end">
            <Popover.Trigger>
              {/*
                이름에 제목을 함께 싣는다. 화면에서는 어느 행의 버튼인지 눈으로 보이지만,
                버튼 목록으로 훑는 사람에게는 같은 이름 아홉 개가 구분이 안 된다.
              */}
              <IconButton variant="ghost" size="sm" aria-label={`풀 버전 다운로드 · ${title}`}>
                <Download size={15} aria-hidden="true" />
              </IconButton>
            </Popover.Trigger>
            <Popover.Content className={styles.pickPanel} aria-label="풀 버전 다운로드">
              <p className={styles.pickTitle}>풀 버전 다운로드</p>
              <p className={styles.pickSub}>
                {title}
                {duration ? ` · ${duration}` : null}
              </p>
              <RadioGroup
                className={styles.pickOptions}
                value={quality}
                onValueChange={setQuality}
                aria-label="화질"
              >
                {qualities.map((option) => (
                  <Radio
                    key={option.id}
                    value={option.id}
                    className={styles.pickOption}
                    label={
                      <>
                        <span className={styles.pickOptionLabel}>{option.label}</span>
                        <span className={styles.pickOptionSize}>{option.size}</span>
                      </>
                    }
                  />
                ))}
              </RadioGroup>
              <p className={styles.pickNote}>
                보관 만료{dday.kind === 'active' ? `(${dday.label})` : ''} 후에는 다운로드할 수
                없어요.
              </p>
              <div className={styles.pickActions}>
                <Button variant="ghost" size="md" onClick={() => setPickOpen(false)}>
                  취소
                </Button>
                <Button
                  variant="solid"
                  size="md"
                  fullWidth
                  onClick={() => {
                    setPickOpen(false);
                    onRequestDownload(quality);
                  }}
                >
                  다운로드 시작
                </Button>
              </div>
            </Popover.Content>
          </Popover>
        ) : null}

        {view.kind === 'downloading' ? (
          <IconButton
            variant="ghost"
            size="sm"
            aria-label={`다운로드 취소 · ${title}`}
            onClick={onCancelDownload}
          >
            <X size={14} aria-hidden="true" />
          </IconButton>
        ) : null}

        {view.kind === 'done' ? (
          <button type="button" className={styles.donePill} onClick={onResetDownload}>
            <Check size={12} aria-hidden="true" />
            받기 완료
            <VisuallyHidden> · {title} · 다시 받기</VisuallyHidden>
          </button>
        ) : null}

        {/* 열 수 있는 행만 「들어간다」고 말한다 */}
        {openable ? (
          <ChevronRight size={16} className={styles.rowChevron} aria-hidden="true" />
        ) : null}
      </div>
    </li>
  );
}
