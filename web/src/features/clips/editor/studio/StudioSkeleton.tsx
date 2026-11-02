import { Redo2, Undo2 } from 'lucide-react';
import { Button, IconButton, LoadingRegion, Skeleton } from '@/ui';
import { TRACK_COUNT } from '@/api/audioTracks';
import { EditorBackLink } from '../EditorBackLink';
import shared from '../editorShared.module.css';
import sections from '../sections/sections.module.css';
import styles from './StudioScreen.module.css';
import own from './StudioSkeleton.module.css';

/** 도구 레일의 도구 수(레이아웃 · 자막 · 텍스트 · 오디오 · 이미지) */
const TOOLS = 5;
/** 처음 여는 도구(레이아웃) 패널의 줄 수 */
const LAYOUTS = 5;
/** 타임라인 줄: 영상 한 줄 + 방송 소리 트랙(실제 편집기는 늘 트랙 수만큼 선다) */
const LANES = ['video', ...Array.from({ length: TRACK_COUNT }, () => 'audio')] as const;
/** 재생 막대 왼쪽 버튼 크기(5초 · 1초 뒤로, 재생, 1초 · 5초 앞으로) */
const TRANSPORT_BUTTONS = [32, 32, 40, 32, 32] as const;
/** 재생 막대 오른쪽 칩 폭(반복 · 배속 · 썸네일) */
const TRANSPORT_CHIPS = [76, 64, 112] as const;

const u = (n: number) => `calc(${n} * var(--pc-u))`;

/** 도구 버튼 한 칸(46u 폭, 아이콘 + 이름 두 줄 높이) */
function ToolSlot() {
  return <Skeleton width={u(46)} height={u(46)} style={{ borderRadius: u(10) }} />;
}

// 편집기 첫 읽기(POK-444). 카드·방송·녹화를 다 읽기 전의 편집기 모양이다 — 로드 뒤와 같은 클래스(.toolRail · .toolPanel ·
// .previewColumn · .stage · .timeline · .trackRow · .lane)를 그대로 써서, 다 읽어도 칸 배치가 바뀌지 않는다. 처음 여는 도구가
// 레이아웃이라 가운데는 원본(16:9) · 결과(9:16) 두 판이다.
// 헤더는 실물이다: 이전 화면으로는 나갈 수 있고, 고치는 버튼은 꺼져 있다. 방송 · 저장 상태 문구 자리만 막대다.
// 자막 줄은 저장된 자막이 있어야 서므로 미리 그리지 않는다(없는 편집본에서 거짓이 된다).
export function StudioSkeleton() {
  return (
    <div className={styles.screen}>
      <header className={shared.header}>
        <EditorBackLink />
        <div className={shared.headerTitleBlock}>
          <h1 className={shared.headerTitle}>클립 편집</h1>
          <div className={shared.headerMeta}>
            {/* 글줄 안에 세워 줄 높이(11u × 1.3)를 지킨다 — 블록이면 헤더가 문구가 올 때 3u 커진다 */}
            <Skeleton
              width={u(180)}
              height={u(10)}
              style={{ display: 'inline-block', verticalAlign: 'middle' }}
            />
          </div>
        </div>
        <div className={shared.headerHistory}>
          <IconButton variant="ghost" size="sm" aria-label="작업 이전으로" disabled>
            <Undo2 size={15} aria-hidden />
          </IconButton>
          <IconButton variant="ghost" size="sm" aria-label="작업 앞으로" disabled>
            <Redo2 size={15} aria-hidden />
          </IconButton>
        </div>
        <div className={shared.headerActions}>
          <Button variant="soft" size="sm" disabled>
            편집본 저장
          </Button>
          <Button variant="solid" size="sm" disabled>
            영상 만들기
          </Button>
        </div>
      </header>
      <main className={own.fill}>
        <LoadingRegion label="편집기를 불러오는 중" className={own.fill}>
          <div className={styles.body} aria-hidden="true">
            <div className={styles.toolRail}>
              <div className={styles.toolList}>
                {Array.from({ length: TOOLS }, (_, i) => (
                  <ToolSlot key={i} />
                ))}
              </div>
              <div className={styles.railFooter}>
                <span className={styles.railDivider} />
                <ToolSlot />
              </div>
            </div>
            <div className={styles.toolPanel}>
              <div className={sections.section}>
                <Skeleton width={u(64)} height={u(13)} />
                <div className={sections.layoutList}>
                  {Array.from({ length: LAYOUTS }, (_, i) => (
                    <Skeleton key={i} height={u(56)} style={{ borderRadius: u(10) }} />
                  ))}
                </div>
              </div>
            </div>
            <div className={styles.previewColumn}>
              <div className={shared.preview}>
                <div className={shared.stage}>
                  {/* 판 크기는 로드 뒤와 같은 클래스가 칸에 맞춰 정한다(--pc-ar = 가로 / 세로) */}
                  <div className={shared.sourceSide}>
                    <span className={shared.sideLabel}>원본</span>
                    <div
                      className={shared.sourcePanel}
                      style={{ ['--pc-ar' as string]: String(16 / 9) }}
                    >
                      <Skeleton height="100%" />
                    </div>
                  </div>
                  <div className={shared.resultSide}>
                    <span className={shared.sideLabel}>결과</span>
                    <div
                      className={shared.resultFrame}
                      style={{ ['--pc-ar' as string]: String(9 / 16) }}
                    >
                      <Skeleton height="100%" />
                    </div>
                  </div>
                </div>
              </div>
              <div className={shared.transport}>
                {TRANSPORT_BUTTONS.map((size, i) => (
                  <Skeleton key={i} width={u(size)} height={u(size)} radius="md" />
                ))}
                <Skeleton width={u(92)} height={u(12)} style={{ marginLeft: u(6) }} />
                <div className={shared.transportRight}>
                  {TRANSPORT_CHIPS.map((width, i) => (
                    <Skeleton key={i} width={u(width)} height={u(32)} radius="pill" />
                  ))}
                </div>
              </div>
            </div>
          </div>
          <div className={styles.timeline} aria-hidden="true">
            {/* 높이 조절 손잡이 자리 — 손잡이는 위아래 여백에 겹쳐 3u만 차지한다 */}
            <div style={{ height: u(3) }} />
            <div className={styles.timelineHead}>
              <span className={styles.timelineTitle}>타임라인</span>
              <div className={styles.zoomGroup}>
                <Skeleton width={u(100)} height={u(32)} radius="md" />
              </div>
            </div>
            {/* 시각 눈금 줄. 누르는 자리가 아니라 손가락 모양을 지운다 */}
            <div className={styles.ruler} style={{ cursor: 'default' }}>
              <Skeleton height={u(12)} />
            </div>
            <div className={styles.lanes}>
              {LANES.map((kind, i) => (
                <div key={i} className={styles.trackRow}>
                  <span className={styles.trackLabel}>
                    <Skeleton width={u(24)} height={u(24)} style={{ borderRadius: u(6) }} />
                    <Skeleton width={u(72)} height={u(11)} />
                  </span>
                  <div className={styles.lane} data-kind={kind}>
                    <Skeleton height="100%" />
                  </div>
                </div>
              ))}
            </div>
            <div className={styles.legend}>
              <Skeleton width="60%" height={u(11)} />
            </div>
          </div>
        </LoadingRegion>
      </main>
    </div>
  );
}
