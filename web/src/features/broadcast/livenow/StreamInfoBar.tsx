'use client';

import { Pencil, Zap } from 'lucide-react';
import { Button, IconButton, Tag } from '@/ui';
import styles from './LiveScreen.module.css';
import { MARK_HOTKEY } from './markKey';
import type { StreamMeta } from './useLiveDetailsMockState';
import type { LiveStream } from './useLiveMockState';

// 방송 정보 바 — 시안 1b는 이 줄을 영상 "아래"에 둔다. 화면 위쪽은 영상이 차지하고,
// 제목·카테고리·시청자·수동 마킹이 한 줄에 모인다(옛 전폭 헤더가 하던 일을 여기가 받는다).

export function StreamInfoBar({
  stream,
  meta,
  uptimeLabel,
  pendingLabel,
  onMark,
}: {
  stream: LiveStream;
  meta: StreamMeta;
  /** 흐르는 경과 표기 — 시계의 주인은 플레이어라 화면이 받아 내려준다 */
  uptimeLabel: string;
  /** 만드는 중인 카드의 시각 — 있으면 버튼 아래 피드백이 선다 */
  pendingLabel: string | null;
  onMark: () => void;
}) {
  return (
    <section className={styles.infoBar} aria-label="방송 정보">
      {/* 카테고리 이미지 창구가 아직 없다 — 자리와 라벨만 지킨다 */}
      <div className={styles.infoThumb} aria-hidden>
        {meta.thumbLabel}
      </div>
      <div className={styles.infoBody}>
        <div className={styles.infoTitleRow}>
          <h1 className={styles.infoTitle}>{stream.title}</h1>
          {/* 제목 수정은 방송 정보 쓰기 창구(미발부)까지 자리만 */}
          <IconButton variant="ghost" size="sm" aria-label="제목 수정" disabled>
            <Pencil size={13} aria-hidden />
          </IconButton>
        </div>
        <div className={styles.infoTagRow}>
          <span className={styles.infoCategory}>{meta.category}</span>
          {meta.tags.map((tag) => (
            <Tag key={tag} variant="soft" size="sm">
              {tag}
            </Tag>
          ))}
        </div>
      </div>
      <div className={styles.infoStats}>
        <span className={styles.infoViewers}>
          <b>{stream.viewers}명</b> 시청 중
        </span>
        <span className={styles.infoDivider} aria-hidden />
        <span className={styles.infoUptime}>
          <span className={styles.infoUptimeValue}>{uptimeLabel}</span>
          <span>스트리밍 중</span>
        </span>
      </div>
      <span className={styles.infoRule} aria-hidden />
      <div className={styles.markBlock}>
        <Button
          variant="solid"
          size="sm"
          fullWidth
          onClick={onMark}
          iconStart={<Zap size={13} aria-hidden />}
        >
          수동 마킹
          <kbd className={styles.markHotkey}>{MARK_HOTKEY}</kbd>
        </Button>
        {/* 눌렀다는 사실이 화면에만 남으면 스크린리더 사용자는 마킹됐는지 알 수 없다.
            쉴 때는 비운다 — CSS가 빈 요소를 접어 버튼이 바의 가운데에 그대로 선다. */}
        <span className={styles.markFeedback} role="status">
          {pendingLabel ? `${pendingLabel} 마킹됨 · 카드 생성 중` : null}
        </span>
      </div>
    </section>
  );
}
