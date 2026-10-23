import {
  Camera,
  ChevronDown,
  ChevronUp,
  Pause,
  Play,
  SkipBack,
  ZoomIn,
  ZoomOut,
} from 'lucide-react';
import { Button, IconButton, Slider } from '@/ui';
import styles from './StudioScreen.module.css';
import type { ClipEditorMockState } from '../useClipEditorMockState';

// 타임라인 위 막대: 쇼츠 서비스 셋(오퍼스클립·알파컷·피카클립)이 모두 재생 조작을 여기 가운데에 둔다(POK-389).
// 왼쪽: 쇼츠 구간 자르기(I·O 키와 같은 일) · 가운데: 시작으로·재생·쇼츠 안 시각 · 오른쪽: 확대(캡컷처럼 − 막대 +)·접기.
// 쇼츠 길이는 가운데 시각 칸이 보이므로 따로 두지 않는다(3분 상한 안내는 타임라인 아래 줄에 있다).
// 1초·5초 이동은 ←→(Shift) 키가 맡고, 구간 반복은 늘 켠 채 둔다.

/** 쇼츠 안 시각: 반올림하면 끝에서 「0:13 / 0:12」처럼 넘쳐 보이니 내림한다 */
export function shortClock(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

export function TimelineToolbar({ state }: { state: ClipEditorMockState }) {
  const { range } = state;
  const position = Math.min(
    Math.max(0, state.playheadSeconds - range.startSeconds),
    state.rangeLengthSeconds,
  );
  const zoomIndex = Math.max(0, state.zoomLevels.indexOf(state.zoom));

  return (
    <div className={styles.timelineToolbar} role="toolbar" aria-label="재생과 구간">
      <div className={styles.toolbarGroup}>
        {/* 좁은 폭에서는 글자를 숨기고 기호·아이콘만 남긴다(겹치지 않게). 이름은 aria-label·title이 늘 말한다 */}
        <Button
          variant="outline"
          size="sm"
          aria-label="여기부터"
          title="여기부터 (I)"
          onClick={state.markIn}
        >
          <span aria-hidden>[</span>
          <span className={styles.toolbarText}> 여기부터</span>
        </Button>
        <Button
          variant="outline"
          size="sm"
          aria-label="여기까지"
          title="여기까지 (O)"
          onClick={state.markOut}
        >
          <span className={styles.toolbarText}>여기까지 </span>
          <span aria-hidden>]</span>
        </Button>
        {/* 고른 장면은 영상 만들기 창의 「장면 고르기」로 채워진다. 쇼츠 구간 밖에서는 못 고른다 */}
        <Button
          variant="outline"
          size="sm"
          aria-label="이 장면을 썸네일로"
          title="이 장면을 썸네일로"
          disabled={!state.canPickThumbnail}
          onClick={state.pickThumbnail}
        >
          <Camera size={13} aria-hidden />
          <span className={styles.toolbarText}> 썸네일</span>
        </Button>
      </div>

      <div className={styles.toolbarCenter}>
        <Button
          variant="outline"
          size="sm"
          aria-label="시작으로"
          title="시작으로"
          onClick={() => state.seekTo(range.startSeconds)}
        >
          <SkipBack size={13} aria-hidden />
          <span className={styles.toolbarText}> 시작으로</span>
        </Button>
        <IconButton
          variant="solid"
          size="md"
          aria-label={state.playing ? '일시정지' : '재생'}
          onClick={state.togglePlay}
        >
          {state.playing ? <Pause size={18} aria-hidden /> : <Play size={18} aria-hidden />}
        </IconButton>
        <output className={styles.toolbarClock} aria-label="재생 위치">
          {shortClock(position)} / {shortClock(state.rangeLengthSeconds)}
        </output>
      </div>

      <div className={styles.toolbarGroupEnd}>
        <IconButton
          variant="ghost"
          size="sm"
          aria-label="타임라인 축소"
          title="타임라인 축소"
          // 범위보다 넓힐 수 없으면(카드 창 등) 꺼 둔다: 눌러도 화면이 그대로다
          disabled={!state.canZoomOut}
          onClick={() => state.setZoom(state.zoomLevels[zoomIndex - 1] ?? state.zoom)}
        >
          <ZoomOut size={14} aria-hidden />
        </IconButton>
        <Slider
          className={styles.zoomSlider}
          label="타임라인 확대"
          min={Math.max(0, state.zoomLevels.indexOf(state.minZoom))}
          max={state.zoomLevels.length - 1}
          step={1}
          value={zoomIndex}
          onValueChange={(index) => state.setZoom(state.zoomLevels[index] ?? state.zoom)}
        />
        <IconButton
          variant="ghost"
          size="sm"
          aria-label="타임라인 한 단계 확대"
          title="타임라인 확대"
          disabled={zoomIndex >= state.zoomLevels.length - 1}
          onClick={() => state.setZoom(state.zoomLevels[zoomIndex + 1] ?? state.zoom)}
        >
          <ZoomIn size={14} aria-hidden />
        </IconButton>
        <span className={styles.zoomValue}>{state.zoomLabel}</span>
        <IconButton
          variant="ghost"
          size="sm"
          aria-label={state.timelineCollapsed ? '타임라인 열기' : '타임라인 접기'}
          onClick={state.toggleTimeline}
        >
          {state.timelineCollapsed ? (
            <ChevronUp size={14} aria-hidden />
          ) : (
            <ChevronDown size={14} aria-hidden />
          )}
        </IconButton>
      </div>
    </div>
  );
}
