// 실시간 통계 그래프의 계산 — 칸 묶기·가로 스크롤 판단·눈금·선 모양·카테고리 구간.
//
// 그래프는 한 상자 두 층이다: 위층 = 시청자 선, 아래층 = 채팅 막대(하이라이트 칸은 포인트색). 층마다 눈금이 따로라 두 값을 한
// 눈금에 겹치지 않는다(옛 패널은 둘을 각자 최대값에 맞춰 한 칸에 겹쳐 두 선의 높낮이가 아무 뜻이 없었다).
// 방송 중에도 처음부터 지금까지 그리고, 길면 가로 스크롤이다 — 언제 스크롤하는지는 layoutFor.

/** 차트 한 칸(epoch ms). 서버 칸(chat-chart)을 그대로 옮기거나 묶은 것이다 */
export interface StatsBucket {
  start: number;
  chats: number;
  donations: number;
}

/** 그래프가 그리는 창 [t0, t1)과 칸. t1은 마지막 칸의 끝이다(라이브면 「지금」) */
export interface StatsChart {
  t0: number;
  t1: number;
  bucketSeconds: number;
  buckets: StatsBucket[];
}

export interface ViewerPoint {
  t: number;
  v: number;
}

/** 카테고리가 그 시각부터 이 이름이다(시각 순) */
export interface CategoryMark {
  t: number;
  name: string;
}

/** 폭에 맞출 때 고르는 칸 길이(초). 막대가 MIN_SLOT 이상 되는 가장 잘게 */
const FIT_SECONDS = [30, 60, 120];
/**
 * 칸 하나의 최소 폭(px, --pc-u 1배 기준). 폭에 맞출 때도 스크롤할 때보다 가늘어지지 않게 둘을 같게 둔다. 막대는 칸의 72%라
 * 막대 10px·틈 4px — 시안에서 칸 10px·막대 62%(6px)는 「막대가 너무 가늘다」는 지적을 받았다
 */
export const MIN_SLOT = 14;
/** 스크롤할 때의 칸 길이(초)와 폭 — 어느 방송이든 1시간 = 420px로 같은 밀도로 읽힌다 */
export const SCROLL_SECONDS = 120;
export const SCROLL_SLOT = 14;
/** 막대가 칸에서 차지하는 비율과 최대 폭 */
export const BAR_RATIO = 0.72;
export const BAR_MAX = 22;

/**
 * 칸 길이를 seconds로 묶는다. 채팅·후원은 더하고 시각은 첫 칸을 쓴다. 서버 칸보다 짧게는 쪼개지 않는다. t1은 그대로라 마지막
 * 묶음은 덜 찰 수 있다 — 그 칸의 끝은 bucketEnd로 읽는다
 */
export function rebucketTo(chart: StatsChart, seconds: number): StatsChart {
  const k = Math.max(1, Math.round(seconds / chart.bucketSeconds));
  if (k === 1) return chart;
  const buckets: StatsBucket[] = [];
  for (let i = 0; i < chart.buckets.length; i += k) {
    const part = chart.buckets.slice(i, i + k);
    buckets.push({
      start: part[0]!.start,
      chats: part.reduce((a, b) => a + b.chats, 0),
      donations: part.reduce((a, b) => a + b.donations, 0),
    });
  }
  return { ...chart, buckets, bucketSeconds: chart.bucketSeconds * k };
}

/**
 * 가로 스크롤을 켤지와 칸 길이. 2분 칸이 SCROLL_SLOT보다 좁아지면(넓은 화면 약 2시간 반, 좁은 화면 약 1시간 반) 스크롤하고 칸을
 * 2분으로 고정한다. 그보다 짧은 방송은 폭에 맞춰 늘린다. 기준을 방송 길이가 아니라 밀도로 잡은 이유: 같은 2시간 방송도 넓은 화면은
 * 한눈에 들어가고 좁은 화면은 막대가 뭉개진다.
 * @param visibleW 보이는 그래프 폭(px)
 * @param u --pc-u 한 칸의 px(브라우저 글꼴을 키우면 커진다) — 칸 폭 기준도 같이 커진다
 */
export function layoutFor(chart: StatsChart, visibleW: number, u = 1) {
  const span = chart.buckets.length * chart.bucketSeconds;
  const minSlot = MIN_SLOT * u;
  const scrollSlot = SCROLL_SLOT * u;
  const fit = FIT_SECONDS.find(
    (sec) => sec >= chart.bucketSeconds && visibleW / (span / sec) >= minSlot,
  );
  const scroll = visibleW > 0 && visibleW / (span / SCROLL_SECONDS) < scrollSlot;
  const view = rebucketTo(chart, scroll ? SCROLL_SECONDS : (fit ?? SCROLL_SECONDS));
  // 폭은 칸 수가 아니라 시간에 비례한다: 막대·핀·눈금이 모두 같은 시각 자(xOf)로 서야 덜 찬 마지막 칸에서도 어긋나지 않는다
  const contentW = scroll
    ? ((chart.t1 - chart.t0) / (SCROLL_SECONDS * 1000)) * scrollSlot
    : visibleW;
  return { view, scroll, contentW };
}

function niceStep(raw: number): number {
  if (raw <= 0) return 1;
  const pow = 10 ** Math.floor(Math.log10(raw));
  const f = raw / pow;
  const step = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
  return Math.max(1, step * pow);
}

/** 0부터 시작하는 눈금 — 막대는 늘 0에서 선다 */
export function zeroScale(max: number, count = 3) {
  const step = niceStep(Math.max(1, max) / count);
  const top = Math.max(step, Math.ceil(max / step) * step);
  const ticks: number[] = [];
  for (let v = 0; v <= top + 1e-9; v += step) ticks.push(v);
  return { min: 0, max: top, ticks };
}

/**
 * 값이 있는 범위만 잡는 눈금(시청자 층). 이 층엔 다른 값이 없어 0을 붙들 까닭이 없다. 범위는 방송 전체로 잡는다 — 보이는 구간으로
 * 잡으면 넘길 때마다 눈금이 바뀌어 선이 출렁인다
 */
export function rangeScale(min: number, max: number, count = 3) {
  const step = niceStep(Math.max(1, max - min) / count);
  const lo = Math.floor(min / step) * step;
  const hi = Math.max(lo + step, Math.ceil(max / step) * step);
  const ticks: number[] = [];
  for (let v = lo; v <= hi + 1e-9; v += step) ticks.push(v);
  return { min: lo, max: hi, ticks };
}

const pad2 = (n: number) => String(n).padStart(2, '0');
export const fmtHM = (ms: number) => {
  const d = new Date(ms);
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
};
export const fmtHMS = (ms: number) => {
  const d = new Date(ms);
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
};
export const fmtNum = (n: number) => n.toLocaleString('ko-KR');
/** 눈금 숫자 — 만부터는 「1.2만」 */
export const fmtTick = (n: number) =>
  n >= 10_000
    ? `${(n / 10_000).toLocaleString('ko-KR', { maximumFractionDigits: 1 })}만`
    : fmtNum(n);
/** 칸 길이 — 덜 찬 마지막 칸은 「1분 30초」처럼 나머지 초까지 쓴다 */
export const fmtSeconds = (sec: number) => {
  const s = Math.round(sec);
  if (s < 60) return `${s}초`;
  return s % 60 === 0 ? `${s / 60}분` : `${Math.floor(s / 60)}분 ${s % 60}초`;
};
export function fmtDuration(minutes: number) {
  // 분을 먼저 반올림한다 — 나머지를 반올림하면 119.5분이 「1시간 60분」이 됐다(PR #316 리뷰)
  const total = Math.round(minutes);
  const h = Math.floor(total / 60);
  const m = total % 60;
  return h > 0 ? `${h}시간 ${m}분` : `${m}분`;
}

const TICK_STEPS_MIN = [1, 2, 5, 10, 15, 30, 60, 120];

/**
 * 시계에 맞춘 시간 눈금(그래프 안 x). nowEdge면 오른쪽 끝을 「지금」으로 두고 그 근처 눈금을 뺀다. 눈금은 시각으로 묶는다(글자로
 * 묶으면 짧은 방송에서 같은 시:분이 겹쳐 React key가 겹쳤다, QA 2026-10-11 리뷰)
 */
export function timeTicks(chart: StatsChart, width: number, nowEdge: boolean) {
  const span = chart.t1 - chart.t0;
  if (span <= 0 || width <= 0) return [];
  const maxTicks = Math.max(2, Math.floor(width / 96));
  const stepMin =
    TICK_STEPS_MIN.find((m) => span / (m * 60_000) <= maxTicks) ?? TICK_STEPS_MIN.at(-1)!;
  const step = stepMin * 60_000;
  const tz = new Date(chart.t0).getTimezoneOffset() * 60_000;
  const ticks: { t: number; label: string; x: number; edge?: boolean }[] = [];
  for (let t = Math.ceil((chart.t0 - tz) / step) * step + tz; t <= chart.t1; t += step) {
    const x = ((t - chart.t0) / span) * width;
    if (nowEdge && width - x < 44) continue;
    if (x < 18) continue;
    ticks.push({ t, label: fmtHM(t), x });
  }
  if (nowEdge) ticks.push({ t: chart.t1, label: '지금', x: width, edge: true });
  return ticks;
}

export const xOf = (chart: StatsChart, t: number, width: number) =>
  ((t - chart.t0) / (chart.t1 - chart.t0)) * width;

export function bucketIndexAt(chart: StatsChart, t: number) {
  const i = Math.floor((t - chart.t0) / (chart.bucketSeconds * 1000));
  return Math.min(chart.buckets.length - 1, Math.max(0, i));
}

/** 칸 i의 끝 시각 — 묶은 마지막 칸은 덜 찼을 수 있어 t1에서 자른다(지금보다 뒤까지 그 칸이라고 하지 않는다) */
export function bucketEnd(chart: StatsChart, i: number) {
  const start = chart.buckets[i]?.start ?? chart.t1;
  return Math.min(start + chart.bucketSeconds * 1000, chart.t1);
}

/** 그 시각까지 마지막으로 잰 시청자 — 관측은 1분마다라 칸과 정확히 겹치지 않는다 */
export function viewersAt(viewers: readonly ViewerPoint[], t: number): number | null {
  let found: number | null = null;
  for (const p of viewers) {
    if (p.t <= t) found = p.v;
    else break;
  }
  return found;
}

/**
 * 그래프 창에 걸치는 시청자 관측 — 창 바로 앞 관측 하나를 붙여 선이 왼쪽 끝에서 시작하게 한다(그 앞은 그리는 쪽이 잘라낸다)
 */
export function viewerWindow(viewers: readonly ViewerPoint[], t0: number, t1: number) {
  const inside = viewers.filter((p) => p.t >= t0 && p.t <= t1);
  const before = viewers.filter((p) => p.t < t0).at(-1);
  return before ? [before, ...inside] : inside;
}

/** 창 [t0, t1] 안의 카테고리 구간 — 창 앞에서 시작한 카테고리는 창 왼쪽 끝에서 자른다 */
export function categorySpans(marks: readonly CategoryMark[], t0: number, t1: number) {
  const out: { from: number; to: number; name: string }[] = [];
  marks.forEach((c, k) => {
    const next = marks[k + 1]?.t ?? t1;
    const from = Math.max(c.t, t0);
    const to = Math.min(next, t1);
    if (to > from) out.push({ from, to, name: c.name });
  });
  return out;
}

type Pt = readonly [number, number];

/** 꺾은선 path */
export function linePath(points: readonly Pt[]) {
  return points
    .map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`)
    .join('');
}

/** 선 아래를 바닥(baseY)까지 닫은 면 */
export function areaPath(points: readonly Pt[], baseY: number, line = linePath(points)) {
  const first = points[0];
  const last = points.at(-1);
  if (!first || !last) return '';
  return `${line}L${last[0].toFixed(1)},${baseY}L${first[0].toFixed(1)},${baseY}Z`;
}

/**
 * 단조 3차 보간(Fritsch–Carlson) — 점 사이를 부드럽게 잇되 점을 넘어 튀지 않는다(없는 최고·최저를 그리지 않는다)
 */
export function smoothPath(points: readonly Pt[]) {
  const n = points.length;
  if (n < 3) return linePath(points);
  const xs = points.map((p) => p[0]);
  const ys = points.map((p) => p[1]);
  const dx: number[] = [];
  const m: number[] = [];
  for (let i = 0; i < n - 1; i++) {
    dx[i] = xs[i + 1]! - xs[i]!;
    m[i] = dx[i]! === 0 ? 0 : (ys[i + 1]! - ys[i]!) / dx[i]!;
  }
  const t: number[] = [m[0]!];
  for (let i = 1; i < n - 1; i++) t[i] = m[i - 1]! * m[i]! <= 0 ? 0 : (m[i - 1]! + m[i]!) / 2;
  t[n - 1] = m[n - 2]!;
  for (let i = 0; i < n - 1; i++) {
    if (m[i] === 0) {
      t[i] = 0;
      t[i + 1] = 0;
      continue;
    }
    const a = t[i]! / m[i]!;
    const b = t[i + 1]! / m[i]!;
    const h = a * a + b * b;
    if (h > 9) {
      const k = 3 / Math.sqrt(h);
      t[i] = k * a * m[i]!;
      t[i + 1] = k * b * m[i]!;
    }
  }
  const f = (v: number) => v.toFixed(1);
  let d = `M${f(xs[0]!)},${f(ys[0]!)}`;
  for (let i = 0; i < n - 1; i++) {
    const h = dx[i]! / 3;
    d += `C${f(xs[i]! + h)},${f(ys[i]! + t[i]! * h)} ${f(xs[i + 1]! - h)},${f(ys[i + 1]! - t[i + 1]! * h)} ${f(xs[i + 1]!)},${f(ys[i + 1]!)}`;
  }
  return d;
}

/** 위만 둥근 막대 — 바닥은 반듯하게 기준선에 붙는다 */
export function barPath(x: number, w: number, top: number, base: number, r: number) {
  const h = base - top;
  if (h <= 0.5) return '';
  const rr = Math.min(r, h, w / 2);
  return `M${x},${base}V${top + rr}Q${x},${top} ${x + rr},${top}H${x + w - rr}Q${x + w},${top} ${x + w},${top + rr}V${base}Z`;
}

/**
 * 하이라이트 핀 묶기 — 핀(알약)이 칸보다 넓어 가까운 카드끼리 겹친다. gap px 안에 붙으면 앞 핀 하나로 묶고 몇 장 더 있는지 붙인다.
 * 묶인 카드는 툴팁이 그 칸의 카드를 전부 말한다
 */
export function clusterPins<T extends { x: number }>(pins: readonly T[], gap: number) {
  const out: { first: T; x: number; more: number }[] = [];
  for (const pin of [...pins].sort((a, b) => a.x - b.x)) {
    const last = out.at(-1);
    if (last && pin.x - last.x < gap) last.more += 1;
    else out.push({ first: pin, x: pin.x, more: 0 });
  }
  return out;
}
