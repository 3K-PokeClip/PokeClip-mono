'use client';

import { CirclePlus, Pencil, Zap } from 'lucide-react';
import { Button, IconButton, Skeleton, Tag } from '@/ui';
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
  infoPending,
  titleNote,
  uptimeLabel,
  uptimeNote,
  metaNote,
  pendingLabel,
  markMode,
  markDisabled,
  onMark,
}: {
  stream: LiveStream;
  meta: StreamMeta;
  /** 시청자 줄: 「1,842명 시청 중」, 끝난 방송은 「끝날 때 N명 시청」, 값이 없으면 그 까닭(확인 중·기록 없음·수집 전) */
  viewersLabel: string;
  /**
   * 방송 정보를 받아 오는 중이다(POK-449). 빈 제목 · 시청자 줄 · 카테고리 줄을 번쩍이는 회색 막대로 둔다: 수집기가 방송을 찾기 전이라
   * 비었을 뿐이다
   */
  infoPending: boolean;
  /** 제목을 모를 때(받아 오는 중이 아닐 때)의 말: 「제목 확인 중」 · 「제목 수집 전」 · 「제목 기록 없음」. 방송 번호를 대신 쓰지 않는다 */
  titleNote: string;
  /** 흐르는 경과 표기: clip의 startedAt에서 센다. 모르면 null이다 */
  uptimeLabel: string | null;
  /**
   * 경과 뒤에 붙는 말. 라이브 「스트리밍 중」, 지난 방송 「방송함」. 경과를 모르면 이 말만 선다: 지난 방송·명부 답 전에
   * 「방송 중이 아니에요」라고 하면 거짓이다(리뷰 4라운드)
   */
  uptimeNote: string;
  /**
   * 방송 정보(카테고리·태그)가 없을 때의 말(missingWord). 받기 전·상태를 모름·명부에 없음 「확인 중」, 받았는데 없으면 라이브
   * 「수집 전」, 지난 방송 「기록 없음」
   */
  metaNote: string;
  /** 만드는 중인 카드의 시각 — 있으면 버튼 아래 피드백이 선다 */
  pendingLabel: string | null;
  /**
   * 라이브는 시안 1b 「수동 마킹 F8」, 지난 방송은 시안 1c 「이 시점 카드 추가」(재생 위치에 카드를 세운다, POK-392). 화면 상태로
   * 그린다: 지난 방송 주소는 명부 답 전에도 지난 방송 단추다(눌러지는지는 markDisabled가 정한다)
   */
  markMode: 'live' | 'past';
  /** 지금은 못 누른다: 방송 상태를 모르거나, 지난 방송인데 재생 위치를 서버 기준점 축으로 못 읽는다(녹화 없음·기준점 없음) */
  markDisabled: boolean;
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
          {stream.title ? (
            <h1 className={styles.infoTitle}>{stream.title}</h1>
          ) : infoPending ? (
            <h1 className={styles.infoTitle} aria-label="방송 정보를 받아 오고 있어요">
              <Skeleton width={260} height={18} data-testid="info-title-pending" />
            </h1>
          ) : (
            <h1 className={styles.infoTitle}>
              <span className={styles.infoNote}>{titleNote}</span>
            </h1>
          )}
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
          ) : infoPending ? (
            <Skeleton width={120} height={12} data-testid="info-meta-pending" />
          ) : (
            // 칩을 지어내지 않고 사실만 적는다
            <span className={styles.infoNote}>{metaNote}</span>
          )}
        </div>
      </div>
      <div className={styles.infoStats}>
        <span className={styles.infoViewers}>
          {infoPending && !viewersLabel ? (
            <Skeleton width={72} height={12} data-testid="info-viewers-pending" />
          ) : (
            viewersLabel
          )}
        </span>
        <span className={styles.infoDivider} aria-hidden />
        <span className={styles.infoUptime}>
          {uptimeLabel !== null ? (
            <span className={styles.infoUptimeValue}>{uptimeLabel}</span>
          ) : null}
          <span>{uptimeNote}</span>
        </span>
      </div>
      <span className={styles.infoRule} aria-hidden />
      <div className={styles.markBlock}>
        {markMode === 'past' ? (
          <>
            <Button
              variant="solid"
              size="sm"
              fullWidth
              disabled={markDisabled}
              onClick={onMark}
              iconStart={<CirclePlus size={13} aria-hidden />}
            >
              이 시점 카드 추가
            </Button>
            <span className={styles.markNote}>재생 지점 앞 40초 · 뒤 20초</span>
          </>
        ) : (
          <Button
            variant="solid"
            size="sm"
            fullWidth
            disabled={markDisabled}
            onClick={onMark}
            iconStart={<Zap size={13} aria-hidden />}
          >
            수동 마킹
            <kbd className={styles.markHotkey}>{MARK_HOTKEY}</kbd>
          </Button>
        )}
        {/* 눌렀다는 사실이 화면에만 남으면 스크린리더 사용자는 마킹됐는지 알 수 없다.
            쉴 때는 비운다 — CSS가 빈 요소를 접어 버튼이 바의 가운데에 그대로 선다. */}
        <span className={styles.markFeedback} role="status">
          {pendingLabel ? `${pendingLabel} 마킹됨 · 카드 생성 중` : null}
        </span>
      </div>
    </section>
  );
}
