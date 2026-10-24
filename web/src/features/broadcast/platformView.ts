import { platformLabel } from '@/api/clipEditor';

// 한 방송이 치지직 · SOOP에 나란히 켜질 수 있다(POK-397). 서버는 플랫폼마다 값을 따로 준다: 방송 목록의 platforms(시청자 수 ·
// 관측 시각), 방송 정보의 platforms(제목 · 카테고리 · 시청자 수 · 관측 시각)와 시청자 기록 점마다 platform. 화면은 그중 무엇을
// 보일지를 여기 한 곳에서 정한다: 홈 · 라이브가 따로 고르면 두 화면의 제목 · 숫자가 갈린다.

/** 플랫폼 하나의 최신 값. 방송 목록 · 방송 정보의 platforms 원소 */
export interface PlatformLatest {
  platform: string;
  title?: string | null;
  category?: string | null;
  /** null은 「모른다」이지 0명이 아니다 */
  viewers?: number | null;
  observedAt?: string | null;
}

/**
 * 켜진 플랫폼으로 보는 관측 나이. 수집기가 1분마다 보므로 두 번 놓쳐도 켜짐이다. 꺼진 플랫폼도 서버의 묶음에 남으므로(서버가
 * 지우지 않는다) 관측이 이보다 오래되면 꺼진 것으로 본다
 */
export const ON_AIR_WINDOW_MS = 3 * 60_000;

/**
 * 켜진 플랫폼(관측이 지금에서 ON_AIR_WINDOW_MS 안). 지금 켜진 것이 없으면(끝난 방송, 수집기가 잠깐 못 봄) 마지막 관측에서 같은
 * 폭 안에 관측된 것, 곧 끝날 무렵 켜져 있던 것이다: 서버가 방송 목록 제목을 고르는 기준과 같다(clip BroadcastListService, 끝난
 * 방송에서 웹과 서버가 다른 플랫폼을 고르지 않게). 관측 시각이 하나도 없으면 받은 것을 그대로 돌려준다: 다 숨기면 플랫폼 ·
 * 제목이 사라진다. 순서는 서버 순서(치지직 → SOOP)를 지킨다
 */
export function onAirPlatforms<T extends PlatformLatest>(
  list: readonly T[] | null | undefined,
  nowMs: number,
): T[] {
  const all = list ?? [];
  const seen = all.filter((p) => p.observedAt != null);
  const within = (from: number) =>
    seen.filter((p) => from - Date.parse(p.observedAt!) <= ON_AIR_WINDOW_MS);
  const now = within(nowMs);
  if (now.length > 0) return now;
  if (seen.length === 0) return [...all];
  return within(Math.max(...seen.map((p) => Date.parse(p.observedAt!))));
}

/**
 * 대표 플랫폼: 켜진 것 중 시청자가 가장 많은 쪽, 같으면 앞(치지직 → SOOP). 이름 · 제목 · 시청자 수 · 그래프를 모두 이 하나에서
 * 가져와 한 화면의 값들이 서로 맞는다. 플랫폼 값이 없으면(옛 서버) null이다
 */
export function primaryPlatform<T extends PlatformLatest>(
  list: readonly T[] | null | undefined,
  nowMs: number,
): T | null {
  let best: T | null = null;
  for (const p of onAirPlatforms(list, nowMs))
    if (best === null || (p.viewers ?? -1) > (best.viewers ?? -1)) best = p;
  return best;
}

/** 켜진 플랫폼 태그(「치지직 · SOOP」). 모르는 코드는 빼고, 남는 것이 없으면 null(태그를 숨긴다) */
export function onAirLabel(
  list: readonly PlatformLatest[] | null | undefined,
  nowMs: number,
): string | null {
  const labels = onAirPlatforms(list, nowMs)
    .map((p) => platformLabel(p.platform))
    .filter((label): label is string => label !== null);
  return labels.length > 0 ? labels.join(' · ') : null;
}

/** 방송 정보 창구의 답(필요한 칸만). platforms · 점의 platform은 PR3a 앞 서버면 빠져 온다 */
export interface RawBroadcastInfo<P extends { observedAt: string; platform?: string | null }> {
  latest: {
    title: string | null;
    tags?: string[];
    category: string | null;
    viewers?: number | null;
    observedAt?: string | null;
  } | null;
  platforms?: PlatformLatest[];
  series: P[];
}

/** 화면이 그릴 방송 정보: 대표 플랫폼 하나의 값 */
export interface PrimaryInfo<P> {
  /** 대표 플랫폼 코드. 플랫폼 값이 없는 옛 서버면 null */
  platform: string | null;
  latest: {
    title: string | null;
    tags: string[];
    category: string | null;
    viewers: number | null;
  } | null;
  /** 시청자 기록에서 대표 플랫폼 점만. 다른 플랫폼 숫자를 섞으면 그래프가 두 방송 사이를 오간다 */
  series: P[];
}

/**
 * 방송 정보를 대표 플랫폼 하나의 값으로 고른다. 플랫폼 구분 없는 latest(모든 플랫폼 중 가장 새 관측)를 그대로 쓰면 다른
 * 플랫폼 값이 섞인다(SOOP로만 켠 방송에 치지직 제목 · 시청자 수가 떴다, POK-397). 태그는 플랫폼마다 따로 오지 않아 latest가
 * 대표 플랫폼의 그 관측일 때만 쓴다. 플랫폼 값이 없는 옛 서버면 받은 그대로다
 */
export function primaryInfo<P extends { observedAt: string; platform?: string | null }>(
  info: RawBroadcastInfo<P>,
  nowMs: number,
): PrimaryInfo<P> {
  const primary = primaryPlatform(info.platforms, nowMs);
  if (primary === null)
    return {
      platform: null,
      latest:
        info.latest === null
          ? null
          : {
              title: info.latest.title,
              tags: info.latest.tags ?? [],
              category: info.latest.category,
              viewers: info.latest.viewers ?? null,
            },
      series: info.series,
    };
  // latest에는 플랫폼 칸이 없어 관측 시각으로만 가른다. 다른 플랫폼도 같은 시각에 관측됐으면 어느 쪽 줄인지 알 수 없어 태그를 쓰지
  // 않는다(PR #368 codex)
  const sameRow =
    info.latest?.observedAt != null &&
    info.latest.observedAt === primary.observedAt &&
    !(info.platforms ?? []).some(
      (p) => p.platform !== primary.platform && p.observedAt === primary.observedAt,
    );
  return {
    platform: primary.platform,
    latest: {
      title: primary.title ?? null,
      tags: sameRow ? (info.latest?.tags ?? []) : [],
      category: primary.category ?? null,
      viewers: primary.viewers ?? null,
    },
    // 플랫폼이 없는 점은 옛 서버가 남긴 것이라 둔다
    series: info.series.filter((p) => p.platform == null || p.platform === primary.platform),
  };
}

/**
 * 실시간으로 온 관측 하나를 플랫폼마다 최신 값에 얹는다. 서버와 같은 규칙이다: 시청자 수 · 관측 시각은 그 플랫폼의 가장 새
 * 관측, 제목 · 카테고리는 제목이 있는 가장 새 관측. 플랫폼이 없는 관측(옛 서버)은 얹지 않는다
 */
export function withPlatformObservation(
  platforms: PlatformLatest[] | undefined,
  ev: {
    time: string;
    title: string | null;
    category: string | null;
    viewers: number | null;
    platform?: string | null;
  },
): PlatformLatest[] | undefined {
  if (!ev.platform) return platforms;
  const prev = platforms?.find((p) => p.platform === ev.platform);
  const titled = Boolean(ev.title?.trim());
  const next: PlatformLatest = {
    platform: ev.platform,
    title: titled ? ev.title : (prev?.title ?? null),
    category: titled ? ev.category : (prev?.category ?? null),
    viewers: ev.viewers,
    observedAt: ev.time,
  };
  if (prev !== undefined) return (platforms ?? []).map((p) => (p === prev ? next : p));
  // 처음 보는 플랫폼은 서버 순서(치지직 → SOOP → 그 밖 이름순)에 끼운다: 끝에 붙이면 시청자가 같을 때 「앞쪽이 대표」가
  // 깨졌다(PR #368 codex)
  return [...(platforms ?? []), next].sort(
    (a, b) => platformRank(a) - platformRank(b) || a.platform.localeCompare(b.platform),
  );
}

/** 서버의 플랫폼 순서(clip PLATFORM_ORDER): 치지직 → SOOP → 그 밖 */
function platformRank(p: PlatformLatest): number {
  if (p.platform === 'CHZZK') return 0;
  if (p.platform === 'SOOP') return 1;
  return 2;
}
