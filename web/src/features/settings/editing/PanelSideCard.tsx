'use client';

import { Info } from 'lucide-react';
import { RadioGroup, useToast } from '@/ui';
import {
  DEFAULT_EDITOR_PANEL_SIDE,
  storePanelSide,
  useStoredPanelSide,
  type EditorPanelSide,
} from '@/lib/editorPanelSide';
import styles from './EditingSettingsScreen.module.css';

// 설정 › 편집 환경 「도구 패널 위치」(POK-614, 2026-11-20 사용자 시안). 편집기의 도구 레일과 패널을 둘 쪽을 고른다.
// 고르는 즉시 저장한다(저장 버튼 없음 — 화면 테마와 같다). 편집기 레일 맨 아래 「패널 위치」 버튼과 같은 값이라
// 거기서 옮기면 여기도 그 쪽이 골라져 있다(lib/editorPanelSide). 이 브라우저에만 남는다.

const SIDES: readonly { side: EditorPanelSide; label: string }[] = [
  { side: 'left', label: '왼쪽' },
  { side: 'right', label: '오른쪽' },
];

export function PanelSideCard() {
  const { toast } = useToast();
  const side = useStoredPanelSide();

  return (
    <section className={styles.card} aria-labelledby="panel-side-title">
      <h2 id="panel-side-title" className={styles.cardTitle}>
        도구 패널 위치
      </h2>
      <p id="panel-side-desc" className={styles.cardDesc}>
        레이아웃 · 자막 · 텍스트 · 오디오 · 이미지 패널과 도구 레일을 둘 쪽을 고르세요. 미리보기와
        타임라인 위치는 그대로예요.
      </p>
      <RadioGroup
        className={styles.sideOptions}
        orientation="horizontal"
        value={side}
        onValueChange={(next) => {
          if (next === side) return;
          if (!storePanelSide(next as EditorPanelSide)) {
            toast({ tone: 'error', title: '이 브라우저에 저장하지 못했어요' });
          }
        }}
        aria-labelledby="panel-side-title"
        aria-describedby="panel-side-desc panel-side-note"
      >
        {SIDES.map(({ side: value, label }) => (
          <RadioGroup.Item
            key={value}
            value={value}
            className={styles.sideOption}
            label={
              <>
                <PanelSidePreview side={value} />
                {/* 「기본」 앞 띄어쓰기는 낭독 이름용이다(「왼쪽기본」으로 붙어 읽힌다) — flex라 화면엔 안 그려진다 */}
                <span className={styles.sideOptionText}>
                  {label}
                  {value === DEFAULT_EDITOR_PANEL_SIDE ? (
                    <>
                      {' '}
                      <span className={styles.sideOptionHint}>기본</span>
                    </>
                  ) : null}
                </span>
              </>
            }
          />
        ))}
      </RadioGroup>
      <p id="panel-side-note" className={styles.sideNote}>
        <Info aria-hidden />
        <span>
          편집기 도구 레일 맨 아래 <strong>패널 위치</strong> 버튼으로도 바꿀 수 있어요 · 이
          브라우저에만 저장돼요
        </span>
      </p>
    </section>
  );
}

/** 편집기 축소 그림 — 머리줄 · (레일 · 패널 · 미리보기) · 타임라인. <label> 안이라 전부 span이다 */
function PanelSidePreview({ side }: { side: EditorPanelSide }) {
  return (
    <span className={styles.preview} data-side={side} aria-hidden="true">
      <span className={styles.previewBar} />
      <span className={styles.previewRow}>
        <span className={styles.previewRail} />
        <span className={styles.previewPanel} />
        <span className={styles.previewStage} />
      </span>
      <span className={styles.previewBar} />
    </span>
  );
}
