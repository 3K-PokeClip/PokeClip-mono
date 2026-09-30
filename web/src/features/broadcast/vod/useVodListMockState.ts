'use client';

import { fetchAllBroadcasts, fetchAllJumpCards, mediaStreamId } from '@/api/clipEditor';

/** 지난 방송 목록을 다시 읽는 간격 — 끝난 방송이라 자주 볼 까닭이 없다 */
const LIST_POLL_MS = 60_000;
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useToast } from '@/ui';
import { fetchRecordingSpans, playbackConfigured } from '@/api/mediaPlayback';
import { recordingTimeline } from '@/features/player/recordingTimeline';
import { excludeLive, filterByPeriod } from './vodListView';

// 시안 1f 지난 방송 목록의 상태. 목록은 clip `GET /api/clip/broadcasts`(POK-174·ADR-055)에서 온다(POK-251).
// VOD 준비 상태를 줄 백엔드(B5)가 아직 없어, 녹화 재생 서버가 설정된 곳에서만 끝난 방송을 「다시 보기 가능」으로 본다.
// 방송 제목도 명부에 칸이 없어 아직 방송 번호로 보인다.
//
// ⚠ VodBroadcast는 계약 미러다 — services/clip `BroadcastListResponse.Item`과 칸 이름·값이
// 같다. 실연동 때 화면을 안 고치려면 이 모양을 지켜야 한다. 계약에 없는 표기값은 아래
// VodRowVisual로 갈라 뒀다 — 그쪽은 B5가 생기면 통째로 서버 값에 자리를 내준다.
//
// 화면 컴포넌트에는 목업 값을 두지 않는다 — 언젠가 서버가 내려줄 값은 전부 여기서 나오고,
// 화면에는 구조 라벨('지난 방송' 같은 고정 문구)만 남는다.

/** 계약의 status — 소문자 그대로다(BroadcastStatus.dbValue). `state=past`는 뒤 둘로 펼쳐진다 */
export type VodBroadcastStatus = 'live' | 'ended' | 'vod_ready';

/** 「내 방송」과 「내가 편집하는 방송」을 가르는 표시 재료 — 권한이 아니다(ADR-055) */
export type VodBroadcastRelation = 'OWNER' | 'EDITOR';

/** 계약 한 줄 — 이 여섯 칸이 서버가 주는 전부다 */
export interface VodBroadcast {
  streamId: string;
  status: VodBroadcastStatus;
  relation: VodBroadcastRelation;
  /** ⚠ null 가능 — 시작 알림의 발생 시각이 비어 오면 그렇다(ADR-016 종료 선도착) */
  startedAt: string | null;
  endedAt: string | null;
  /** 60일 보관 만료 시각. 기한이 지난 방송도 목록에 남으므로 과거일 수 있다 */
  vodExpiresAt: string | null;
}

/**
 * 계약에 없는 표기값 — 제목·썸네일 길이·카드 수는 목록 문이 아직 안 싣는다.
 * B5(VOD 확정)가 생기면 이 타입은 통째로 사라지고 계약 필드로 대체된다.
 */
export interface VodRowVisual {
  title: string;
  /** 준비 중이라 아직 모르면 null */
  durationSec: number | null;
  cardCount: number;
  /** 만료 임박 행의 「저장하지 않은 카드 N개가 함께 삭제됩니다」 */
  unsavedCardCount?: number;
}

/**
 * 풀 VOD 내려받기 상태 — 시안 1f의 행 오른쪽 조작부 3분기가 이 값에서 갈린다.
 *
 * ⚠ 실제로 받는 일은 아직 아무것도 안 한다. 받기를 시작하면 「준비 중인 기능」 토스트가
 * 뜨고 행은 idle에 머문다 — 다운로드 백엔드가 기능명세·계약에 아직 없기 때문이다(POK-226).
 * 화면은 시안대로 세 상태를 모두 그릴 수 있어야 하므로 목업이 두 상태를 심어 둔다.
 */
export type VodDownloadState =
  { kind: 'idle' } | { kind: 'downloading'; progress: number } | { kind: 'done' };

export const VOD_DOWNLOAD_IDLE: VodDownloadState = { kind: 'idle' };

export type VodPeriodFilter = 'all' | '7d' | '30d' | 'custom';

/** 「기간 지정」의 두 입력. 'YYYY-MM-DD'이고 안 채운 쪽은 null */
export interface VodCustomRange {
  from: string | null;
  to: string | null;
}

/** 테스트 주입 — 빈 상태·경계 케이스를 화면 밖에서 만든다 (ClipEditorOptions 선례) */
export interface VodListOptions {
  broadcasts?: VodBroadcast[];
  visuals?: Record<string, VodRowVisual>;
  downloads?: Record<string, VodDownloadState>;
  /** D-day·기간 계산의 기준 시각 — 시험이 얼린다. 없으면 지금 */
  now?: Date;
}

export interface VodListMockState {
  /** 모든 D-day·기간 계산의 기준 시각 */
  now: Date;
  /** live 제외 + 기간 필터를 통과한 행 */
  broadcasts: VodBroadcast[];
  /** 필터와 무관한 전체 수 — 「아직 없다」와 「이 기간에 없다」를 가른다 */
  totalCount: number;
  visuals: Record<string, VodRowVisual>;
  filter: VodPeriodFilter;
  setFilter: (filter: VodPeriodFilter) => void;
  customRange: VodCustomRange;
  setCustomRange: (range: VodCustomRange) => void;
  downloads: Record<string, VodDownloadState>;
  /** 화질을 고르고 받기를 눌렀을 때 — 지금은 「준비 중」만 알린다 */
  requestDownload: (streamId: string, quality: string) => void;
  cancelDownload: (streamId: string) => void;
  /** 「받기 완료」를 눌러 다시 받기 — 자리를 idle로 되돌린다 */
  resetDownload: (streamId: string) => void;
}

// 「지금」을 고정한다. 클라이언트 시계로 계산하면 하이드레이션이 어긋나고(MOCK_GREETING 선례),
// 목업 날짜와 함께 얼면 D-day가 결정적이라 테스트가 시계를 조작할 필요도 없다.
// 연동 때는 서버 응답이 CSR로 오므로 이 상수가 `new Date()`가 된다 — 훅 내부만 바뀐다.

/**
 * MOCK_NOW에서 거슬러 올라간 시각. setHours 같은 지역 시간 계산을 안 쓰는 이유는 D-day가
 * 실행 환경의 시간대에 따라 하루씩 흔들리지 않게 하려는 것이다 — 「지금」을 얼렸으면
 * 배지도 얼어야 한다. 하루 안쪽으로 시간을 물리면 보관 만료가 `D-(60 - days)`로 떨어진다.
 */

/** 보관 만료는 종료 시각 + 60일이다(ADR-004) — D-day가 저절로 행마다 달라진다 */

// 시안 1f의 네 상태를 모두 담고, 기간 칩을 눌렀을 때 목록이 눈에 띄게 달라지도록 종료일을
// 흩어 뒀다 — 7일 이내 4개 · 30일 이내 9개 · 전체 12개.

const EMPTY_RANGE: VodCustomRange = { from: null, to: null };

// ── clip 창구 배선(POK-251): `GET /api/clip/broadcasts?state=past|live` + 방송마다 카드 수 ──

interface WireBroadcast {
  streamId: string;
  status: string;
  relation: string;
  startedAt: string | null;
  endedAt: string | null;
  vodExpiresAt: string | null;
  /** 녹화 경로의 키(POK-233). 옛 서버면 빠져 온다 */
  ingestStreamId?: string | null;
}

function isoMs(iso: string | null): number | null {
  if (!iso) return null;
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : t;
}

export function useVodListMockState(options: VodListOptions = {}): VodListMockState {
  const { toast } = useToast();
  const [filter, setFilter] = useState<VodPeriodFilter>('all');
  const [customRange, setCustomRange] = useState<VodCustomRange>(EMPTY_RANGE);
  const [downloads, setDownloads] = useState<Record<string, VodDownloadState>>(
    () => options.downloads ?? {},
  );
  const [fetched, setFetched] = useState<VodBroadcast[]>([]);
  const [fetchedVisuals, setFetchedVisuals] = useState<Record<string, VodRowVisual>>({});
  const [now, setNow] = useState(() => new Date());
  const rowCache = useRef(new Map<string, { cardCount: number; recorded: boolean }>());

  useEffect(() => {
    if (options.broadcasts) return;
    let stopped = false;
    const load = async () => {
      try {
        // 목록을 끝까지 넘긴다 — 한 쪽만 읽으면 51번째부터 조용히 사라진다(POK-251 리뷰).
        // 실패하면 바깥 catch가 받아 지금 목록을 그대로 두고 다음 주기에 다시 한다
        const all: WireBroadcast[] = await fetchAllBroadcasts('past');
        if (stopped) return;
        // 방송마다 카드 수·녹화 여부는 한 번만 재서 기억한다 — 끝난 방송은 바뀌지 않는데, 주기마다 전체 이력에
        // 카드 목록을 부르면 계정이 오래될수록 요청이 끝없이 는다(PR #200 codex)
        const cache = rowCache.current;
        const checkRecording = (b: WireBroadcast) =>
          b.status === 'ended' && playbackConfigured() && cache.get(b.streamId)?.recorded !== true;
        // 녹화 목록은 영상 경로 전체라 같은 키를 나눠 쓰는 방송들이 같은 답을 받는다(POK-233) — 한 번 불러올 때 키마다
        // 한 번만 묻는다. 방송마다 물으면 방송이 쌓인 계정은 같은 큰 요청을 주기마다 여러 번 보낸다(PR #208 codex)
        const spansByKey = new Map<string, ReturnType<typeof fetchRecordingSpans>>();
        const spansOf = (key: string) => {
          let found = spansByKey.get(key);
          if (found === undefined) {
            found = fetchRecordingSpans(key);
            spansByKey.set(key, found);
          }
          return found;
        };
        await Promise.all(
          all
            .filter((b) => !cache.has(b.streamId) || checkRecording(b))
            .map(async (b) => {
              const known = cache.get(b.streamId);
              const [cardCount, spans] = await Promise.all([
                known !== undefined
                  ? Promise.resolve(known.cardCount)
                  : fetchAllJumpCards(b.streamId).then(
                      (cards) => cards.length,
                      () => null,
                    ),
                // 녹화 재생 서버가 있으면 그 방송의 녹화가 실제로 있는지 본다 — 없으면 열어도 「영상 신호 없음」뿐이다
                checkRecording(b) ? spansOf(mediaStreamId(b)) : Promise.resolve([]),
              ]);
              // 카드 수를 못 읽었으면 기억하지 않는다 — 다음 주기에 다시 잰다.
              // 「녹화 있음」만 굳힌다: 방송 직후엔 녹화가 늦게 생기고 재생 서버가 잠깐 실패해도 빈 목록이 온다.
              // 「없음」을 굳히면 녹화가 생겨도 이 탭은 영영 준비 중으로 남는다(PR #200 codex)
              if (cardCount !== null)
                cache.set(b.streamId, {
                  cardCount,
                  // 녹화 목록은 영상 경로 전체라 같은 키를 나눠 쓰는 다른 방송의 녹화도 섞여 온다(POK-233) — 다시보기와 같은
                  // 규칙(방송 시간과 겹치는 구간)으로 거른다. 안 거르면 녹화 없는 방송이 「다시 보기 가능」으로 올라 열면 빈 화면이다
                  recorded:
                    known?.recorded === true ||
                    recordingTimeline(spans, isoMs(b.startedAt), isoMs(b.endedAt)) !== null,
                });
            }),
        );
        const rows: VodBroadcast[] = all.map((b) => ({
          streamId: b.streamId,
          // 서버 상태가 정본이다. 녹화 재생 서버가 있고 그 방송의 녹화가 실제로 있을 때만 「다시 보기 가능」으로 올린다
          status:
            b.status === 'ended' && cache.get(b.streamId)?.recorded
              ? 'vod_ready'
              : ((b.status as VodBroadcastStatus) ?? 'ended'),
          relation: b.relation === 'EDITOR' ? 'EDITOR' : 'OWNER',
          startedAt: b.startedAt,
          endedAt: b.endedAt,
          vodExpiresAt: b.vodExpiresAt,
        }));
        const visuals: Record<string, VodRowVisual> = {};
        for (const b of rows) {
          const dur =
            b.startedAt && b.endedAt
              ? Math.max(0, Math.round((Date.parse(b.endedAt) - Date.parse(b.startedAt)) / 1000))
              : null;
          visuals[b.streamId] = {
            title: b.streamId,
            durationSec: dur,
            cardCount: cache.get(b.streamId)?.cardCount ?? 0,
          };
        }
        if (stopped) return;
        setFetched(rows);
        setFetchedVisuals(visuals);
        setNow(new Date());
      } catch {
        /* 다음 주기에 다시 */
      }
    };
    void load();
    const t = window.setInterval(load, LIST_POLL_MS);
    return () => {
      stopped = true;
      window.clearInterval(t);
    };
  }, [options.broadcasts]);

  const source = options.broadcasts ?? fetched;
  const visuals = options.visuals ?? fetchedVisuals;

  const setDownload = useCallback((streamId: string, state: VodDownloadState) => {
    setDownloads((prev) => ({ ...prev, [streamId]: state }));
  }, []);
  const requestDownload = useCallback(() => {
    toast({
      tone: 'info',
      title: '준비 중인 기능이에요',
      description: '풀 VOD 내려받기는 아직 준비 중이에요. 준비되면 알려드릴게요.',
    });
  }, [toast]);
  const cancelDownload = useCallback(
    (streamId: string) => setDownload(streamId, VOD_DOWNLOAD_IDLE),
    [setDownload],
  );
  const resetDownload = useCallback(
    (streamId: string) => setDownload(streamId, VOD_DOWNLOAD_IDLE),
    [setDownload],
  );

  const past = useMemo(() => excludeLive(source), [source]);
  const baseNow = options.now ?? now;
  const broadcasts = useMemo(
    () => filterByPeriod(past, filter, customRange, baseNow),
    [past, filter, customRange, baseNow],
  );

  return {
    now: baseNow,
    broadcasts,
    totalCount: past.length,
    visuals,
    filter,
    setFilter,
    customRange,
    setCustomRange,
    downloads,
    requestDownload,
    cancelDownload,
    resetDownload,
  };
}
