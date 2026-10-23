'use client';

// 실시간 통계 — 위쪽 숫자 줄 + 한 상자 두 층 그래프(위 시청자 선 · 아래 채팅 막대) + 카테고리 띠 + 시간축.
// 하이라이트는 그래프 위 핀(누르면 그 카드 시점으로 간다)과 막대 색(포인트색)으로, 후원은 숫자 줄과 툴팁에만 둔다(막대 아래 후원
// 줄은 사용자 결정으로 뺐다). 방송 중에도 처음부터 지금까지 그리고, 길면 가로 스크롤이다(statsChart.layoutFor). 스크롤해도
// 오른쪽 눈금과 층 이름은 제자리다. 방송 중이면 처음에 오른쪽 끝(지금)을 보여 주고, 끝에 붙어 있으면 새 칸이 와도 끝을 따라간다.
import { ChevronsLeft, ChevronsRight } from 'lucide-react';
import {
  memo,
  useCallback,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type PointerEvent,
} from 'react';
import styles from './LiveStatsPanel.module.css';
import {
  BAR_MAX,
  BAR_RATIO,
  areaPath,
  barPath,
  bucketEnd,
  bucketIndexAt,
  categorySpans,
  clusterPins,
  fmtDuration,
  fmtHM,
  fmtHMS,
  fmtNum,
  fmtSeconds,
  fmtTick,
  layoutFor,
  rangeScale,
  smoothPath,
  timeTicks,
  viewerWindow,
  viewersAt,
  xOf,
  zeroScale,
  type StatsChart,
} from './statsChart';
import type { LiveStatsMockState, StatsCell } from './useLiveStatsMockState';

/** 그래프에 핀으로 서는 카드 */
export interface TimelineCard {
  id: string;
  /** 카드가 가리키는 절대 시각(epoch ms) */
  atMs: number;
  /** 카드의 방송 시점(h:mm:ss) — 누르면 카드를 누른 것과 같이 그 시점으로 간다 */
  timestamp: string;
  /** 핀 글자 — 자동 카드는 카드 제목의 번호(#12), 수동 카드는 「수동」(제목에 번호가 없다) */
  label: string;
  manual: boolean;
  /** 툴팁 한 줄(채팅 ×3.2 급증 등) */
  reason: string;
}

// 그래프 치수(--pc-u 1배 px). 그릴 때 --pc-u의 실제 px을 곱한다 — 브라우저 글꼴을 키우면 그래프도 같이 커진다
const RIGHT = 52;
const PIN_ROW = 30;
const VIEWER_H = 112;
const GAP = 14;
const CHAT_H = 78;
const CAT_GAP = 8;
const CAT_ROW = 24;
/** 핀 둘이 이보다 가까우면 앞 핀 하나로 묶는다(핀 폭) */
const PIN_GAP = 44;

/** --pc-u 한 칸의 px. 테스트(jsdom)처럼 글꼴 크기를 못 읽으면 1이다 */
function usePcUnit() {
  const [u, setU] = useState(1);
  useLayoutEffect(() => {
    const read = () => {
      const px = parseFloat(getComputedStyle(document.documentElement).fontSize);
      setU(Number.isFinite(px) && px > 0 ? px / 16 : 1);
    };
    read();
    window.addEventListener('resize', read);
    return () => window.removeEventListener('resize', read);
  }, []);
  return u;
}

/** 요소 폭. ResizeObserver가 없는 곳(jsdom)에서는 처음 잰 값만 쓴다 */
function useWidth<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(0);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return undefined;
    setWidth(el.getBoundingClientRect().width);
    if (typeof ResizeObserver === 'undefined') return undefined;
    const ro = new ResizeObserver(([entry]) => {
      if (entry) setWidth(entry.contentRect.width);
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, width] as const;
}

function Cell({ cell, keyClass }: { cell: StatsCell; keyClass: string | undefined }) {
  return (
    <div className={styles.cell}>
      <dt className={styles.cellLabel}>
        <span className={keyClass} aria-hidden />
        {cell.label}
      </dt>
      <dd className={styles.cellValue} data-muted={cell.muted ? '' : undefined}>
        {cell.value}
      </dd>
      {cell.sub ? <dd className={styles.cellSub}>{cell.sub}</dd> : null}
    </div>
  );
}

function headMeta(chart: StatsChart | null, scope: LiveStatsMockState['scope'], live: boolean) {
  if (!chart || !scope) return '그래프 확인 중';
  const minutes = (chart.t1 - chart.t0) / 60_000;
  const refresh = live ? ' · 10초마다 갱신' : '';
  if (scope === 'recent') return `최근 ${Math.round(minutes)}분${refresh}`;
  if (scope === 'tail') return `마지막 ${Math.round(minutes / 60)}시간${refresh}`;
  return `방송 전체 · ${fmtDuration(minutes)}${live ? '째' : ''}${refresh}`;
}

interface TipRow {
  /** React key — 카드 줄은 카드 id다(같은 칸의 수동 카드 둘은 글자가 같다) */
  id: string;
  key: 'bar' | 'line' | 'dot' | 'gold';
  value: string;
  label: string;
}

const TIP_KEY: Record<TipRow['key'], string | undefined> = {
  bar: styles.tipKeyBar,
  line: styles.tipKeyLine,
  dot: styles.tipKeyDot,
  gold: styles.tipKeyGold,
};

export const LiveStatsPanel = memo(function LiveStatsPanel({
  stats,
  cards,
  highlightSummary,
  live,
  ended,
  onSeekCard,
}: {
  stats: LiveStatsMockState;
  cards: readonly TimelineCard[];
  /** 카드 목록에서 센 값 — 필터 표기와 같은 원천이다 */
  highlightSummary: { total: number; auto: number; manual: number };
  /** 화면이 이 방송을 방송 중으로 보나 — 오른쪽 끝이 「지금」이고 처음에 끝을 보여 준다 */
  live: boolean;
  /** 끝난 방송: 실시간이 아니라 그 방송의 통계다(QA 2026-10-11) */
  ended: boolean;
  onSeekCard: (timestamp: string) => void;
}) {
  const heading = ended ? '방송 통계' : '실시간 통계';
  const u = usePcUnit();
  const clipId = useId();
  const [wrapRef, width] = useWidth<HTMLDivElement>();
  const right = RIGHT * u;
  const visibleW = Math.max(0, width - right);
  const { chart, scope } = stats;
  const nowEdge = live || scope === 'recent';

  const highlightCell: StatsCell = {
    label: '하이라이트',
    value: fmtNum(highlightSummary.total),
    sub: `자동 ${highlightSummary.auto} · 수동 ${highlightSummary.manual}`,
  };

  return (
    <section className={styles.panel} aria-label={heading}>
      <div className={styles.head}>
        <h2 className={styles.headTitle}>{heading}</h2>
        <span className={styles.headMeta}>
          {live ? <span className={styles.liveDot} aria-hidden /> : null}
          {headMeta(chart, scope, live)}
        </span>
        {cards.length > 0 ? (
          <span className={styles.headHint}>핀을 누르면 그 카드로 가요</span>
        ) : null}
      </div>
      <dl className={styles.strip}>
        <Cell cell={stats.chat} keyClass={styles.keyBar} />
        <Cell cell={stats.viewerCell} keyClass={styles.keyLine} />
        <Cell cell={highlightCell} keyClass={styles.keyDot} />
        <Cell cell={stats.donation} keyClass={styles.keyGold} />
      </dl>
      <div className={styles.body}>
        <div ref={wrapRef} className={styles.wrap}>
          {chart && width > 0 ? (
            <Timeline
              chart={chart}
              stats={stats}
              cards={cards}
              u={u}
              visibleW={visibleW}
              right={right}
              nowEdge={nowEdge}
              live={live}
              clipId={clipId}
              onSeekCard={onSeekCard}
            />
          ) : (
            <div
              className={styles.empty}
              style={{ height: (PIN_ROW + VIEWER_H + GAP + CHAT_H) * u }}
            >
              {chart ? '' : '그래프를 불러오고 있어요'}
            </div>
          )}
        </div>
      </div>
    </section>
  );
});

function Timeline({
  chart,
  stats,
  cards,
  u,
  visibleW,
  right,
  nowEdge,
  live,
  clipId,
  onSeekCard,
}: {
  chart: StatsChart;
  stats: LiveStatsMockState;
  cards: readonly TimelineCard[];
  u: number;
  visibleW: number;
  right: number;
  nowEdge: boolean;
  live: boolean;
  clipId: string;
  onSeekCard: (timestamp: string) => void;
}) {
  const { view, scroll, contentW } = layoutFor(chart, visibleW, u);
  const n = view.buckets.length;
  const span = view.t1 - view.t0;
  // 막대·핀·눈금·호버가 모두 같은 시각 자(xt)를 쓴다. 막대만 같은 폭 칸으로 나누면 덜 찬 마지막 칸 때문에 핀이 이웃 막대 위에
  // 서고, 그 핀에 호버하면 카드가 툴팁에 없었다(PR #316 리뷰). slot은 꽉 찬 칸 하나의 폭이다
  const xt = (t: number) => xOf(view, t, contentW);
  const xb = (i: number) => xt(view.buckets[i]?.start ?? view.t1);
  const wb = (i: number) => xt(bucketEnd(view, i)) - xb(i);
  const slot = ((view.bucketSeconds * 1000) / Math.max(1, span)) * contentW;
  const pinRow = PIN_ROW * u;
  const viewerH = VIEWER_H * u;
  const gap = GAP * u;
  const chatH = CHAT_H * u;
  const svgH = viewerH + gap + chatH;

  const scrollRef = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState({ left: 0, max: 0 });
  const [index, setIndex] = useState<number | null>(null);
  const drag = useRef<{ x: number; left: number; moved: boolean } | null>(null);
  const [dragging, setDragging] = useState(false);
  const followEnd = useRef(true);
  const shown = index !== null && index < n ? index : null;

  const inView = cards.filter((c) => c.atMs >= view.t0 && c.atMs <= view.t1);
  const hot = new Set(inView.map((c) => bucketIndexAt(view, c.atMs)));
  const pins = clusterPins(
    inView.map((c) => ({ card: c, x: xt(c.atMs) })),
    PIN_GAP * u,
  );

  // 위층 — 시청자. 눈금은 방송 전체 기록으로 잡는다(넘길 때마다 눈금이 바뀌면 선이 출렁인다)
  const vw = viewerWindow(stats.viewers, view.t0, view.t1);
  const vScale = vw.length
    ? rangeScale(Math.min(...vw.map((p) => p.v)), Math.max(...vw.map((p) => p.v)), 3)
    : null;
  const vTop = 10 * u;
  const vBase = viewerH - 2 * u;
  const yV = (v: number) =>
    vScale ? vBase - ((v - vScale.min) / (vScale.max - vScale.min)) * (vBase - vTop) : vBase;
  const vPts = vw.map((p) => [xt(p.t), yV(p.v)] as const);
  const vLine = smoothPath(vPts);
  const vLast = vPts.at(-1);

  // 아래층 — 채팅(막대는 0에서)
  const cTop = viewerH + gap + 8 * u;
  const cBase = viewerH + gap + chatH;
  const cScale = zeroScale(Math.max(1, ...view.buckets.map((b) => b.chats)), 3);
  const yC = (v: number) => cBase - (v / cScale.max) * (cBase - cTop);
  const barW = Math.min(BAR_MAX * u, Math.max(2, slot * BAR_RATIO));

  const cats = categorySpans(stats.categories, view.t0, view.t1);
  const crossX = shown !== null ? xb(shown) + wb(shown) / 2 : null;
  const crossV = shown !== null ? viewersAt(stats.viewers, bucketEnd(view, shown)) : null;

  const syncPos = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const max = el.scrollWidth - el.clientWidth;
    followEnd.current = el.scrollLeft >= max - 2;
    setPos({ left: el.scrollLeft, max });
  }, []);

  // 방송 중이면 오른쪽 끝(지금)에서 시작하고, 끝에 붙어 있는 동안 새 칸이 와도 끝을 따라간다. 지난 구간을 보고 있으면 붙들지
  // 않는다. 끝난 방송은 처음부터다. 보이는 폭이 바뀔 때도 다시 잰다: 스크롤할 때 contentW는 폭과 무관해, 창·사이드바 폭만
  // 바뀌면 끝 범위와 「지금」 단추가 옛 값으로 남았다(PR #316 리뷰)
  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    if (nowEdge && followEnd.current) el.scrollLeft = el.scrollWidth;
    setPos({ left: el.scrollLeft, max: el.scrollWidth - el.clientWidth });
  }, [contentW, nowEdge, visibleW]);

  const scrollTo = (left: number) => scrollRef.current?.scrollTo({ left, behavior: 'smooth' });

  // 누르지 않고 움직이면 십자선, 누른 채 움직이면 넘긴다(스크롤할 때만)
  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    if (!scroll || e.button !== 0) return;
    drag.current = { x: e.clientX, left: scrollRef.current?.scrollLeft ?? 0, moved: false };
  };
  const onPointerMove = (e: PointerEvent<HTMLDivElement>) => {
    const d = drag.current;
    if (d && e.buttons === 1) {
      const dx = e.clientX - d.x;
      if (!d.moved && Math.abs(dx) > 4) {
        d.moved = true;
        setDragging(true);
        e.currentTarget.setPointerCapture(e.pointerId);
      }
      if (d.moved && scrollRef.current) scrollRef.current.scrollLeft = d.left - dx;
      return;
    }
    const rect = e.currentTarget.getBoundingClientRect();
    const ratio = (e.clientX - rect.left) / Math.max(1, rect.width);
    setIndex(bucketIndexAt(view, view.t0 + ratio * span));
  };
  const endDrag = () => {
    setDragging(false);
    // 끌기가 끝난 직후의 클릭(핀)은 무시한다 — 다음 틱에 비운다
    window.setTimeout(() => {
      drag.current = null;
    }, 0);
  };
  // 키로 고른 칸이 화면 밖이면 그 칸까지 넘긴다(앞뒤 두 칸 여유). 키의 기본 스크롤은 막았으니 안 넘기면 십자선·툴팁이 화면
  // 밖에 섰다(PR #316 리뷰)
  const reveal = (i: number) => {
    const el = scrollRef.current;
    if (!el || !scroll) return;
    const from = xb(i) - 2 * slot;
    const to = xb(i) + wb(i) + 2 * slot;
    if (from < el.scrollLeft) el.scrollLeft = Math.max(0, from);
    else if (to > el.scrollLeft + el.clientWidth) el.scrollLeft = to - el.clientWidth;
  };
  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const next =
      e.key === 'ArrowLeft'
        ? Math.max(0, (shown ?? n) - 1)
        : e.key === 'ArrowRight'
          ? Math.min(n - 1, (shown ?? -1) + 1)
          : e.key === 'Home'
            ? 0
            : e.key === 'End'
              ? n - 1
              : null;
    if (next === null) return;
    e.preventDefault();
    setIndex(next);
    reveal(next);
  };

  const tipRows = (i: number): TipRow[] => {
    const b = view.buckets[i];
    if (!b) return [];
    const end = bucketEnd(view, i);
    const rows: TipRow[] = [
      {
        id: 'bar',
        key: 'bar',
        value: fmtNum(b.chats),
        label: `채팅 (${fmtSeconds((end - b.start) / 1000)})`,
      },
    ];
    const v = viewersAt(stats.viewers, end);
    if (v !== null) rows.push({ id: 'line', key: 'line', value: fmtNum(v), label: '시청자' });
    for (const c of inView.filter((c) => bucketIndexAt(view, c.atMs) === i))
      rows.push({
        id: c.id,
        key: 'dot',
        value: c.label,
        label: `${c.manual ? '수동' : '자동'} · ${c.reason}`,
      });
    if (b.donations > 0)
      rows.push({ id: 'gold', key: 'gold', value: `${fmtNum(b.donations)}회`, label: '후원' });
    return rows;
  };

  const atStart = pos.left <= 2;
  const atEnd = pos.left >= pos.max - 2;
  const viewFrom = view.t0 + (pos.left / Math.max(1, contentW)) * span;
  const viewTo = view.t0 + ((pos.left + visibleW) / Math.max(1, contentW)) * span;
  const tipFlip = crossX !== null && crossX > pos.left + visibleW - 200 * u;
  const shownBucket = shown !== null ? view.buckets[shown] : undefined;

  return (
    <>
      <div
        ref={scrollRef}
        className={styles.scroll}
        data-scroll={scroll ? '' : undefined}
        style={{ marginRight: right }}
        onScroll={syncPos}
      >
        <div
          className={styles.content}
          data-pannable={scroll ? '' : undefined}
          data-dragging={dragging ? '' : undefined}
          style={{ width: contentW }}
          role="group"
          aria-label={`방송 타임라인 — 채팅·시청자, 하이라이트 ${inView.length}곳, 후원 ${fmtNum(
            view.buckets.reduce((a, b) => a + b.donations, 0),
          )}회 · ←/→로 칸을 옮겨 값을 읽어요`}
          tabIndex={0}
          onKeyDown={onKeyDown}
          onBlur={() => setIndex(null)}
          onPointerLeave={() => setIndex(null)}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={endDrag}
          onPointerCancel={endDrag}
        >
          <div className={styles.pinRow} style={{ height: pinRow }}>
            {pins.map(({ first, x, more }) => (
              <button
                key={first.card.id}
                type="button"
                className={first.card.manual ? styles.pinManual : styles.pin}
                style={{ left: x }}
                onClick={() => {
                  if (drag.current?.moved) return;
                  onSeekCard(first.card.timestamp);
                }}
                aria-label={`하이라이트 ${first.card.label} — ${first.card.reason}${
                  more ? ` 외 ${more}개` : ''
                } · ${first.card.timestamp}로 이동`}
              >
                {first.card.label}
                {more ? <span className={styles.pinMore}>+{more}</span> : null}
              </button>
            ))}
          </div>
          <svg
            className={styles.svg}
            width={contentW}
            height={svgH}
            style={{ width: contentW, height: svgH }}
            aria-hidden
          >
            <defs>
              <clipPath id={clipId}>
                <rect x={0} y={0} width={contentW} height={viewerH} />
              </clipPath>
            </defs>
            <rect x={0} y={0} width={contentW} height={viewerH} className={styles.paneBg} />
            <rect
              x={0}
              y={viewerH + gap}
              width={contentW}
              height={chatH}
              className={styles.paneBg}
            />
            {cats.slice(1).map((c) => (
              <line
                key={`cat-${c.from}`}
                x1={xt(c.from)}
                x2={xt(c.from)}
                y1={0}
                y2={cBase}
                className={styles.catChange}
              />
            ))}
            {inView.map((c) => (
              <line
                key={c.id}
                x1={xt(c.atMs)}
                x2={xt(c.atMs)}
                y1={0}
                y2={cBase}
                className={c.manual ? styles.stemManual : styles.stem}
              />
            ))}
            {vScale
              ? vScale.ticks.map((t) => (
                  <line
                    key={`v${t}`}
                    x1={0}
                    x2={contentW}
                    y1={yV(t)}
                    y2={yV(t)}
                    className={styles.grid}
                  />
                ))
              : null}
            {cScale.ticks.map((t) => (
              <line
                key={`c${t}`}
                x1={0}
                x2={contentW}
                y1={yC(t)}
                y2={yC(t)}
                className={styles.grid}
              />
            ))}
            {/* 관측이 하나뿐이면 선 대신 점이다 — 「수집 전」이라 하면 숫자 줄의 시청자 수와 어긋난다(PR #316 리뷰). 방송
                중이면 마지막 점(맥박)이 그 점이다 */}
            {vScale && vPts.length > 1 ? (
              <g clipPath={`url(#${clipId})`}>
                <path d={areaPath(vPts, vBase, vLine)} className={styles.viewerArea} />
                <path d={vLine} className={styles.viewerLine} />
              </g>
            ) : vScale && vLast ? (
              live ? null : (
                <circle
                  cx={vLast[0]}
                  cy={vLast[1]}
                  r={4 * u}
                  className={styles.viewerDot}
                  data-single=""
                />
              )
            ) : (
              <text
                x={pos.left + Math.min(contentW, visibleW) / 2}
                y={viewerH / 2}
                className={styles.paneEmpty}
                textAnchor="middle"
                dominantBaseline="middle"
              >
                {`시청자 수 ${stats.noViewers}`}
              </text>
            )}
            {view.buckets.map((b, i) => {
              // 덜 찬 마지막 칸은 그 폭만큼 좁게 선다
              const w = Math.min(barW, Math.max(2, wb(i) * BAR_RATIO));
              return (
                <path
                  key={b.start}
                  d={barPath(xb(i) + (wb(i) - w) / 2, w, yC(b.chats), cBase, 3 * u)}
                  className={hot.has(i) ? styles.barHot : styles.bar}
                  data-dim={shown !== null && shown !== i ? '' : undefined}
                />
              );
            })}
            {crossX !== null ? (
              crossV !== null && vScale ? (
                <circle cx={crossX} cy={yV(crossV)} r={4 * u} className={styles.viewerDot} />
              ) : null
            ) : live && vLast && vScale ? (
              <>
                <circle cx={vLast[0]} cy={vLast[1]} r={7 * u} className={styles.livePulse} />
                <circle cx={vLast[0]} cy={vLast[1]} r={4 * u} className={styles.viewerDot} />
              </>
            ) : null}
          </svg>
          <div className={styles.catRow} style={{ height: CAT_ROW * u, marginTop: CAT_GAP * u }}>
            {cats.map((c, k) => {
              const x0 = xt(c.from);
              const w = xt(c.to) - x0;
              const now = live && k === cats.length - 1;
              const dur = fmtDuration((c.to - c.from) / 60_000);
              return (
                <span
                  key={c.from}
                  className={styles.catSeg}
                  data-alt={k % 2 === 1 ? '' : undefined}
                  data-now={now ? '' : undefined}
                  style={{ left: x0 + 1, width: Math.max(0, w - 2) }}
                  title={`${c.name} · ${dur}`}
                >
                  {/* 이름은 구간 왼쪽 끝에 붙어 따라온다(sticky) — 긴 구간을 넘겨도 이름이 화면에 남는다 */}
                  {w >= 56 * u ? (
                    <span className={styles.catLabel}>
                      {c.name}
                      {w >= 170 * u ? (
                        <span className={styles.catDur}>
                          {dur}
                          {now ? '째' : ''}
                        </span>
                      ) : null}
                    </span>
                  ) : null}
                </span>
              );
            })}
            {cats.length === 0 ? <span className={styles.rowEmpty}>카테고리 확인 중</span> : null}
          </div>
          <div className={styles.timeAxis} aria-hidden>
            {timeTicks(view, contentW, nowEdge).map((tick) => (
              <span
                key={tick.t}
                className={styles.timeTick}
                data-edge={tick.edge ? 'end' : undefined}
                style={{ left: tick.x }}
              >
                {tick.label}
              </span>
            ))}
          </div>
          {crossX !== null ? (
            <span
              className={styles.cross}
              style={{ left: crossX, height: pinRow + svgH }}
              aria-hidden
            />
          ) : null}
          {shown !== null && shownBucket && crossX !== null && !dragging ? (
            <div
              className={styles.tip}
              style={
                tipFlip
                  ? { left: crossX - 12, transform: 'translateX(-100%)' }
                  : { left: crossX + 12 }
              }
              role="status"
              aria-live="polite"
            >
              <div className={styles.tipTitle}>
                {fmtHMS(shownBucket.start)} – {fmtHMS(bucketEnd(view, shown))}
              </div>
              {tipRows(shown).map((row) => (
                <div key={row.id} className={styles.tipRow}>
                  <span className={TIP_KEY[row.key]} aria-hidden />
                  <b className={styles.tipValue}>{row.value}</b>
                  <span className={styles.tipLabel}>{row.label}</span>
                </div>
              ))}
            </div>
          ) : null}
        </div>
      </div>

      {/* 제자리 눈금 — 스크롤해도 오른쪽에 남는다 */}
      <svg
        className={styles.axis}
        style={{ top: pinRow, width: right, height: svgH }}
        width={right}
        height={svgH}
        aria-hidden
      >
        {vScale
          ? vScale.ticks.map((t) => (
              <text
                key={`v${t}`}
                x={8 * u}
                y={yV(t)}
                className={styles.yLabel}
                dominantBaseline="middle"
              >
                {fmtTick(t)}
              </text>
            ))
          : null}
        {cScale.ticks.map((t) => (
          <text
            key={`c${t}`}
            x={8 * u}
            y={yC(t)}
            className={styles.yLabel}
            dominantBaseline="middle"
          >
            {fmtTick(t)}
          </text>
        ))}
      </svg>
      <span className={styles.caption} style={{ top: pinRow + 6 * u }}>
        시청자
      </span>
      <span className={styles.caption} style={{ top: pinRow + viewerH + gap + 6 * u }}>
        채팅 · {fmtSeconds(view.bucketSeconds)}마다
      </span>
      <span
        className={styles.rowLabel}
        style={{ top: pinRow + svgH + CAT_GAP * u, height: CAT_ROW * u, width: right }}
      >
        카테고리
      </span>
      {scroll && !atStart ? <span className={styles.fadeLeft} aria-hidden /> : null}
      {scroll && !atEnd ? (
        <span className={styles.fadeRight} style={{ right }} aria-hidden />
      ) : null}

      {scroll ? (
        <div className={styles.footer} style={{ marginRight: right }}>
          <button
            type="button"
            className={styles.jump}
            disabled={atStart}
            onClick={() => scrollTo(0)}
          >
            <ChevronsLeft size={14} aria-hidden />
            처음
          </button>
          <span className={styles.range}>
            {fmtHM(viewFrom)} – {fmtHM(viewTo)} 보는 중 · 끌거나 Shift+휠로 넘겨요
          </span>
          <button
            type="button"
            className={styles.jump}
            disabled={atEnd}
            onClick={() => scrollTo(pos.max)}
          >
            {nowEdge ? '지금' : '끝'}
            <ChevronsRight size={14} aria-hidden />
          </button>
        </div>
      ) : null}
    </>
  );
}
