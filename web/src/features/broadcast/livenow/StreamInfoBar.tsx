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
  viewersLabel,
  uptimeLabel,
  uptimeNote,
  pendingLabel,
  onMark,
}: {
  stream: LiveStream;
  meta: StreamMeta;
  /** 시청자 줄 — 「1,842명 시청 중」 또는 수집 전 안내 */
  viewersLabel: string;
  /** 흐르는 경과 표기 — clip의 startedAt에서 센다. null이면 방송 중이 아니다 */
  uptimeLabel: string | null;
  /** 경과 뒤에 붙는 말. 라이브 「스트리밍 중」, 지난 방송 「방송함」 */
  uptimeNote?: string;
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
          {meta.collected ? (
            <>
              {meta.category ? <span className={styles.infoCategory}>{meta.category}</span> : null}
              {meta.tags.map((tag) => (
                <Tag key={tag} variant="soft" size="sm">
                  {tag}
                </Tag>
              ))}
            </>
          ) : (
            // 수집기 PR-C가 아직 없다 — 칩을 지어내지 않고 사실만 적는다
            <span className={styles.infoNote}>방송 정보 수집 전</span>
          )}
        </div>
      </div>
      <div className={styles.infoStats}>
        <span className={styles.infoViewers}>{viewersLabel}</span>
        <span className={styles.infoDivider} aria-hidden />
        <span className={styles.infoUptime}>
          {uptimeLabel !== null ? (
            <>
              <span className={styles.infoUptimeValue}>{uptimeLabel}</span>
              <span>{uptimeNote ?? '스트리밍 중'}</span>
            </>
          ) : (
            <span>방송 중이 아니에요</span>
          )}
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
