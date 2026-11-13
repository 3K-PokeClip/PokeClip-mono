import type { EditorPlaylist, PlaylistSegment } from './editorPlaylist';
import type { HeadCache } from './fileHeadProbe';
import type { EditorPick } from './windowPlacement';

// 고른 프레임 · 표시 초 → 저장할 컷 값 T(③ 목록 PDT 축 ms)(POK-473 설계 prw2 3.2 결정 2 · 계획 4절 P-4′ · 처분 K1′ · K2 ·
// PK1). 원점은 렌더와 같다 — 판 2 는 ③ 영상 첫 DTS(VIDEO_START), 판 NULL 은 ③ 축으로 옮긴 ② 머리 Hd(FILE_HEAD).
//   in  T = PDT_k + ceil(1000·(p_F − o_k − e_k))   → K2: min(T, min(PDT_k + EXTINF_k, PDT_{k+1}) − 1)
//   out T = PDT_k + floor(1000·(p_F + dur_F/2 − o_k)) → K1′: k 가 목록 마지막 행이고 T > PDT_k + EXTINF_k 일 때만 그 끝으로
//   (dur_F = 다음 표시 프레임까지의 표시 간격 — windowPlacement)
// 🔴 out 은 F 의 끝이 아니라 F 와 다음 표시 프레임 사이 가운데다(kty 결정 (다) 2026-11-13 · E4b-3 결정 ② 「저장 out = 그
//    프레임 끝」 을 대체). F 의 끝에 두면 ms 내림 · ③ D3 간격과 PDT 간격의 반 ms 차 · 창 끝에서 앞 간격으로 짐작한 길이가
//    다음 프레임 경계를 넘겨 렌더 끝 비교(PTS − D0 < x_out)가 다음 프레임을 담는다(렌더 리허설 2026-11-13 · r1 반례). 반
//    프레임(약 16ms) 여유는 그 차들을 모두 덮고, 렌더는 F 를 그대로 담는다.
// 🔴 out 은 장부 틈 안에서 맞추지 않는다. 이어진 틈이면 clip locateEnd 가 (k, EXTINF) 로 접어 맞춘 것과 같고, 끊긴 틈이면
//    toSegmentEnd 로 k 를 끝까지 담는다 — 맞추면 오히려 끝 프레임을 잃는다. 웹은 목록에서 장부 이어짐을 모른다(렌더 답 3).
//    맞춤은 다음 행이 없어 clip 이 AfterLast 로 거절할 자리에만 둔다. 이웃 · k 의 존재는 저장할 때 다시 받은 목록으로 본다.
// 🔴 in 의 − e_k 는 옛 조립(-ss · 0점 Hp)과 새 경로(0점 Hd)의 차를 메운다. 반올림은 in 올림 · out 내림이다(kty 결정 2 (다)
//    2026-11-13 · 설계 3.2 결정 2 의 in 내림을 대체). 새 렌더(PR-B2)의 시작은 「PTS − D0 ≤ x′」 인 마지막 프레임이고 다시
//    열기도 그 자리에 보이는 프레임을 고른다 — in 을 내리면 x′ 가 p_F 보다 1ms 미만 앞이라 둘 다 F 앞 프레임에 섰다(실측 M5
//    2026-11-13 · 렌더 21건 중 13건 · 다시 열기 9건). 대가로 옛 조립의 「in 이후 첫 프레임」 은 소수 ms PTS 에서 F 를 빼고
//    다음 프레임부터다 — 웹과 clip PR-B2 를 같은 배포 창에 붙여 그 공백을 줄인다(kty 결정 1 (가)). 남는 한계: ffmpeg 음수
//    cto 로 렌더 PTS 가 원시보다 밀리는 조각(실측 C2 +1.000ms)과 K2 로 내린 in 은 그대로 F 앞이다. S 입력과 out
//    은 내림 그대로다 — out 을 올리면 다음 프레임 쪽으로 다가간다.

export type EdgeInput = { kind: 's'; s: number } | { kind: 'pick'; pick: EditorPick };

/** 컷 값을 셀 때 읽는 것 — 머리는 캐시가, 이웃은 목록이 진실원이다 */
export interface CutContext {
  /** 편집기 0초의 절대 ms — 세션 동안 바꾸지 않는다 */
  base: number;
  heads: HeadCache;
  /** 저장 때 다시 받은 목록(끝난 방송이면 들고 있는 목록). null = 다시 받지 못함 */
  list: EditorPlaylist | null;
}

/** 고름으로 컷 값을 지을 수 없는 까닭 */
export type CutRefusal = 'headMissing' | 'headFailed' | 'outsideList' | 'listUnavailable';

/** 조각 원점 o_k 와 옛 경로 0점과의 차 e_k(③ 축 초) */
export interface SegmentOrigin {
  o: number;
  e: number;
}

/**
 * 1ms 내림 앞에 더하고 올림 앞에 빼는 여유(ms). 정수 ms 에 떨어지는 값(예: 4.2 − 4.0 → 200.00000000000017)의 부동소수 잡음
 * (하루 길이 축 초에서도 약 1e-8 ms)은 덮어 1ms 밀리지 않게 한다. 실제 값의 소수 가운데 가장 작은 것보다는 훨씬 작다 — 판 NULL
 * 원점 o = D3 − (D2 − Hd) 는 영상 틱(1/90000초)과 소리 틱의 차를 담아 1/lcm(90000, 소리 rate)초까지 내려간다(44.1kHz 1/4410
 * ms ≈ 2.3e-4 · 96 · 32kHz 1/1440 ms ≈ 6.9e-4). 여유가 그만큼 크면 그 소수를 지워 in 이 F 앞에 선다
 */
const ROUNDING_TOLERANCE_MS = 1e-6;

function floorMs(seconds: number): number {
  return Math.floor(seconds * 1000 + ROUNDING_TOLERANCE_MS);
}

function ceilMs(seconds: number): number {
  return Math.ceil(seconds * 1000 - ROUNDING_TOLERANCE_MS);
}

/** 고름 없는 S 입력(창 밖 개요 끌기 · 화살표)의 컷 값 — 반올림만 탄다 */
export function sInputMs(s: number, base: number): number {
  return base + floorMs(s);
}

/**
 * 조각 원점. 판 2 는 머리 없이 영상 첫 DTS, 판 NULL 은 그 seq 의 ② 머리로 o = D3 − (D2 − Hd) · e = Hp − Hd 다. 머리를
 * 아직 묻지 않았거나 행이 없으면 headMissing, 판독이 실패했으면 headFailed
 */
export function originOf(
  seg: Pick<EditorPick, 'seq' | 'timing' | 'videoDts'>,
  heads: HeadCache,
): SegmentOrigin | 'headMissing' | 'headFailed' {
  if (seg.timing === 'v2') return { o: seg.videoDts, e: 0 };
  const entry = heads.get(seg.seq);
  if (entry === undefined || entry.state === 'missing') return 'headMissing';
  if (entry.state === 'failed') return 'headFailed';
  const { hd, hp, videoDts } = entry.head;
  return { o: seg.videoDts - (videoDts - hd), e: hp - hd };
}

/**
 * 조각 원점 — 머리가 아직 없으면 영상 첫 DTS 로 근사한다(표시만 · 저장에는 originOf 가 머리를 요구한다). 머리가 오면 같은
 * 고름 · 같은 S 가 맞는 자리로 다시 그려진다
 */
export function displayOriginOf(
  seg: Pick<EditorPick, 'seq' | 'timing' | 'videoDts'>,
  heads: HeadCache,
): number {
  const origin = originOf(seg, heads);
  return typeof origin === 'string' ? seg.videoDts : origin.o;
}

/** 고름의 표시 초 S — 고름이 든 PDT · D3 와 머리 캐시로만 셈한다(배치 · 목록 없이도 그린다) */
export function sOfPick(pick: EditorPick, heads: HeadCache, base: number): number {
  return (pick.pdtMs - base) / 1000 + (pick.pts - displayOriginOf(pick, heads));
}

function startMsOf(pick: EditorPick, origin: SegmentOrigin, next: PlaylistSegment | null): number {
  const t = pick.pdtMs + ceilMs(pick.pts - origin.o - origin.e);
  const segmentEnd = pick.pdtMs + pick.durationMs;
  return Math.min(t, Math.min(segmentEnd, next?.pdtMs ?? segmentEnd) - 1);
}

function endMsOf(pick: EditorPick, origin: SegmentOrigin, next: PlaylistSegment | null): number {
  const t = pick.pdtMs + floorMs(pick.pts + pick.dur / 2 - origin.o);
  const segmentEnd = pick.pdtMs + pick.durationMs;
  if (next !== null || t <= segmentEnd) return t;
  console.info('[editor] 끝점이 목록 마지막 조각 끝을 넘어 그 끝으로 맞췄어요', {
    overMs: t - segmentEnd,
  });
  return segmentEnd;
}

/**
 * 끝점 입력 → 컷 값 T. S 입력은 floor(base + 1000·S) 다. 고름은 머리 · 다시 받은 목록이 있어야 짓는다 — k 가 목록에서
 * 빠졌으면(방송 중 1시간 창 밖) 이웃을 확인할 수 없어 짓지 않는다(틀린 컷 금지)
 */
export function cutMsOf(
  input: EdgeInput,
  edge: 'start' | 'end',
  ctx: CutContext,
): number | CutRefusal {
  if (input.kind === 's') return sInputMs(input.s, ctx.base);
  const { pick } = input;
  const origin = originOf(pick, ctx.heads);
  if (typeof origin === 'string') return origin;
  if (ctx.list === null) return 'listUnavailable';
  const at = ctx.list.rows.findIndex((row) => row.seq === pick.seq);
  if (at < 0) return 'outsideList';
  const next = ctx.list.rows[at + 1] ?? null;
  return edge === 'start' ? startMsOf(pick, origin, next) : endMsOf(pick, origin, next);
}
