'use client';

import { useCallback, useMemo, useState } from 'react';
import { useToast } from '@/ui';
import {
  countByChip,
  filterByChip,
  filterByQuery,
  sortClips,
  statusFor,
  type LibraryChip,
  type LibrarySort,
} from './libraryView';

// 시안 1g 보관함의 목업 상태.
//
// 편집본 목록·상태를 줄 백엔드가 아직 없다 — 목록 문은 방송·점프카드뿐이고(POK-174·ADR-055)
// 렌더·업로드 잡(POK-125 계열)은 미착수라 데이터와 동작이 전부 목업이다. 연동 티켓에서
// 이 훅 내부만 useQuery/뮤테이션으로 갈아끼우면 화면은 그대로 쓴다(useVodListMockState 선례).
//
// ⚠ LibraryClip은 계약 미러가 아니다 — 편집본 문의 계약이 아직 없어(status.md 열린 미결)
// 시안이 그리는 칸을 그대로 옮긴 화면 모양이다. 계약이 생기면 이 타입이 그쪽에 맞춰지고
// 화면은 여기서 나가는 값만 본다.
//
// 시점(role)은 훅 값이다 — 시안의 스트리머/편집자 토글은 핸드오프용이라 제품 UI에 두지 않는다.
// 실제 신호(계정 권한·화면 모드, ADR-032)는 아직 없다. 편집자 시점의 업로드 요청·승인 대기·
// 반려됨 흐름은 권한 등급 미결에 딸린 가정(시안 2등급)이다 — 등급이 「없음」으로 정해지면
// 편집자도 스트리머와 같은 「업로드」가 되고 이 흐름은 사라진다.
//
// 화면 컴포넌트에는 목업 값을 두지 않는다 — 언젠가 서버가 내려줄 값은 전부 여기서 나오고,
// 화면에는 구조 라벨('보관함' 같은 고정 문구)만 남는다.

/** 화면 모드(ADR-032) — 계정 속성이 아니다 */
export type LibraryRole = 'streamer' | 'editor';

/** 시안 1g ④의 7종. expired는 발행됨 중 원본 VOD 보관이 지난 것이다(ADR-004 60일) */
export type ClipStatus =
  'editing' | 'ready' | 'pending' | 'rejected' | 'published' | 'expired' | 'failed';

export interface LibraryClip {
  id: string;
  title: string;
  status: ClipStatus;
  /** 렌더 실패면 null — 길이를 모른다 */
  durationSec: number | null;
  /** 카드 우상단 이니셜과 접근 이름의 「편집자 ○○」 재료 */
  owner: { name: string; me: boolean };
  /** 「8월 31일 라이브」 — 원본 방송 표기 */
  sourceLabel: string;
  /** 원본 VOD 보관 만료 ISO(종료 + 60일). 지났으면 ddayFor가 expired를 준다 */
  sourceExpiresAt: string | null;
  templateLabel: string;
  subtitleLabel: string;
  /** ISO — 생성순 */
  createdAt: string;
  /** ISO — 최근 편집순 */
  editedAt: string;
  /** 반려됨만 — 사유와 반려 시각 */
  rejection?: { reason: string; at: string };
  /** 발행됨·원본 만료만. 목업 업로드로 발행된 것은 갈 곳이 없어 비워 둔다 */
  youtubeUrl?: string;
}

/** 테스트 주입 — 빈 상태·시점·경계 케이스를 화면 밖에서 만든다 (VodListOptions 선례) */
export interface LibraryOptions {
  role?: LibraryRole;
  clips?: LibraryClip[];
  /** 처음부터 열어 둘 편집본 — 기본은 미선택 */
  selectedId?: string | null;
}

export interface LibraryMockState {
  /** 모든 D-day·「어제」 계산의 기준 시각 */
  now: Date;
  role: LibraryRole;
  /** 검색 → 칩 → 정렬을 거친 목록 */
  clips: LibraryClip[];
  /** 필터와 무관한 전체 수 — 「아직 없다」와 「조건에 없다」를 가른다 */
  totalCount: number;
  /** 칩별 수 — 검색어와 무관하게 전체에서 센다(칩은 재고이고 검색은 그 위의 돋보기다) */
  counts: Record<LibraryChip, number>;
  /** 스트리머 배너의 「승인 대기 N건」 */
  pendingCount: number;
  chip: LibraryChip;
  setChip: (chip: LibraryChip) => void;
  query: string;
  setQuery: (query: string) => void;
  sort: LibrarySort;
  setSort: (sort: LibrarySort) => void;
  selectedId: string | null;
  selectedClip: LibraryClip | null;
  /** 같은 id를 다시 주면 해제 — 선택한 썸네일을 다시 누르면 패널이 닫힌다(시안 1g) */
  select: (id: string) => void;
  deselect: () => void;
  /** 제목 인라인 편집 — 입력마다 저장한다(시안 1g ③) */
  renameClip: (id: string, title: string) => void;
  /** 업로드 대기 → 발행됨(스트리머) / 승인 대기(편집자). 그 밖의 상태는 무시 */
  upload: (id: string) => void;
  /** 렌더 실패 → 업로드 대기. 결과를 토스트로 흉내 내지 않는다 */
  retryRender: (id: string) => void;
  /** 받을 파일이 아직 없다 — 「준비 중」만 알린다 */
  download: (id: string) => void;
  /** 목록에서 뺀다. 선택 중이면 해제 — 확인은 화면(ConfirmDialog)이 먼저 받는다 */
  remove: (id: string) => void;
}

// 「지금」을 고정한다. 클라이언트 시계로 계산하면 하이드레이션이 어긋나고(MOCK_GREETING 선례),
// 목업 날짜와 함께 얼면 D-day가 결정적이라 테스트가 시계를 조작할 필요도 없다.
// 시안의 「오늘 라이브 · D-60」「8월 31일 · D-58」을 역산하면 9월 2일 저녁이다.
const MOCK_NOW = new Date('2026-09-02T20:00:00+09:00');

/** 시안의 시점 토글 대신 — 편집자 시점 확인은 이 값을 로컬에서 바꿔 본다(커밋하지 않는다) */
const MOCK_ROLE: LibraryRole = 'streamer';

const DAY_MS = 24 * 60 * 60 * 1000;
const VOD_RETENTION_DAYS = 60;

/** 보관 만료는 원본 방송 종료 + 60일이다(ADR-004) — D-day가 저절로 편집본마다 달라진다 */
function expiresFrom(endedAt: string): string {
  return new Date(new Date(endedAt).getTime() + VOD_RETENTION_DAYS * DAY_MS).toISOString();
}

const ME = { name: '게임하는너구리', me: true };
const GAMJA = { name: '감자대장', me: false };

// 시안 1g 스크립트(libVals)의 8건 그대로 — 상태 7종이 한 화면에 다 보이도록 심어 뒀다.
// D-day는 시안의 손글씨(7월 22일 → D-19)가 아니라 종료 + 60일에서 계산된다(vod 목업 선례).
const MOCK_CLIPS: LibraryClip[] = [
  {
    id: 'lib2-1',
    title: '보스 막타 · 역전 순간',
    status: 'editing',
    durationSec: 82,
    owner: ME,
    sourceLabel: '8월 31일 라이브',
    sourceExpiresAt: expiresFrom('2026-08-31T18:00:00+09:00'),
    templateLabel: '기본 쇼츠',
    subtitleLabel: '자동 자막 12줄 · 수정 중',
    createdAt: '2026-08-31T22:40:00+09:00',
    editedAt: '2026-09-02T14:20:00+09:00',
  },
  {
    id: 'lib2-2',
    title: '채팅 폭발 · 3연속 클러치',
    status: 'ready',
    durationSec: 65,
    owner: ME,
    sourceLabel: '9월 2일 라이브',
    sourceExpiresAt: expiresFrom('2026-09-02T18:00:00+09:00'),
    templateLabel: '기본 쇼츠',
    subtitleLabel: '자동 자막 9줄',
    createdAt: '2026-09-02T14:31:00+09:00',
    editedAt: '2026-09-02T15:02:00+09:00',
  },
  {
    id: 'lib2-3',
    title: '시청자 도네 반응 모음',
    status: 'pending',
    durationSec: 44,
    owner: GAMJA,
    sourceLabel: '8월 30일 라이브',
    sourceExpiresAt: expiresFrom('2026-08-30T18:00:00+09:00'),
    templateLabel: '리액션 컷',
    subtitleLabel: '자동 자막 7줄',
    createdAt: '2026-08-30T20:10:00+09:00',
    editedAt: '2026-09-02T18:00:00+09:00',
  },
  {
    id: 'lib2-4',
    title: '스크림 에이스 장면',
    status: 'published',
    durationSec: 51,
    owner: ME,
    sourceLabel: '7월 22일 라이브',
    sourceExpiresAt: expiresFrom('2026-07-22T18:00:00+09:00'),
    templateLabel: '기본 쇼츠',
    subtitleLabel: '자동 자막 10줄',
    createdAt: '2026-07-22T23:05:00+09:00',
    editedAt: '2026-07-23T10:12:00+09:00',
    youtubeUrl: 'https://www.youtube.com/shorts/pokeclip-mock-2604',
  },
  {
    id: 'lib2-5',
    title: '고민상담 레전드 사연',
    status: 'rejected',
    durationSec: 100,
    owner: GAMJA,
    sourceLabel: '8월 28일 라이브',
    sourceExpiresAt: expiresFrom('2026-08-28T18:00:00+09:00'),
    templateLabel: '토크 컷',
    subtitleLabel: '자동 자막 24줄',
    createdAt: '2026-08-28T22:00:00+09:00',
    editedAt: '2026-09-01T21:32:00+09:00',
    rejection: { reason: '앞부분 20초 컷', at: '2026-09-01T21:32:00+09:00' },
  },
  {
    id: 'lib2-6',
    title: '5월 이벤트 · 시참 레전드',
    status: 'expired',
    durationSec: 38,
    owner: ME,
    sourceLabel: '5월 12일 라이브',
    sourceExpiresAt: expiresFrom('2026-05-12T18:00:00+09:00'),
    templateLabel: '기본 쇼츠',
    subtitleLabel: '자동 자막 8줄',
    createdAt: '2026-05-13T11:00:00+09:00',
    editedAt: '2026-05-14T09:30:00+09:00',
    youtubeUrl: 'https://www.youtube.com/shorts/pokeclip-mock-2512',
  },
  {
    id: 'lib2-7',
    title: '새벽 랭크 · 승급 확정',
    status: 'ready',
    durationSec: 58,
    owner: ME,
    sourceLabel: '9월 2일 라이브',
    sourceExpiresAt: expiresFrom('2026-09-02T18:00:00+09:00'),
    templateLabel: '기본 쇼츠',
    subtitleLabel: '자동 자막 6줄',
    createdAt: '2026-09-02T15:40:00+09:00',
    editedAt: '2026-09-02T15:40:00+09:00',
  },
  {
    id: 'lib2-8',
    title: '팀원 미스 · 웃참 실패',
    status: 'failed',
    durationSec: null,
    owner: ME,
    sourceLabel: '8월 29일 라이브',
    sourceExpiresAt: expiresFrom('2026-08-29T18:00:00+09:00'),
    templateLabel: '리액션 컷',
    subtitleLabel: '—',
    createdAt: '2026-08-29T21:00:00+09:00',
    editedAt: '2026-08-29T21:04:00+09:00',
  },
];

export function useLibraryMockState(options: LibraryOptions = {}): LibraryMockState {
  const { toast } = useToast();
  const role = options.role ?? MOCK_ROLE;
  const [clips, setClips] = useState<LibraryClip[]>(() => options.clips ?? MOCK_CLIPS);
  const [chip, setChip] = useState<LibraryChip>('all');
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState<LibrarySort>('edited');
  const [selectedId, setSelectedId] = useState<string | null>(options.selectedId ?? null);

  const patch = useCallback((id: string, update: (clip: LibraryClip) => LibraryClip) => {
    setClips((prev) => prev.map((clip) => (clip.id === id ? update(clip) : clip)));
  }, []);

  const select = useCallback((id: string) => {
    setSelectedId((prev) => (prev === id ? null : id));
  }, []);
  const deselect = useCallback(() => setSelectedId(null), []);

  const renameClip = useCallback(
    (id: string, title: string) => patch(id, (clip) => ({ ...clip, title })),
    [patch],
  );

  // 상태 전이만 흉내 낸다 — 업로드 모달(1e)·승인 요청 문은 별도 티켓이고 성공 토스트는
  // 결과를 지어내는 일이라 띄우지 않는다.
  const upload = useCallback(
    (id: string) =>
      patch(id, (clip) =>
        clip.status === 'ready'
          ? { ...clip, status: role === 'editor' ? 'pending' : 'published' }
          : clip,
      ),
    [patch, role],
  );

  const retryRender = useCallback(
    (id: string) =>
      patch(id, (clip) => (clip.status === 'failed' ? { ...clip, status: 'ready' } : clip)),
    [patch],
  );

  // 받기를 실제로 시작하지 않는다 — 편집본 파일을 줄 문이 계약에 없다.
  // 받는 척하고 멈춰 있느니 준비 중이라고 말하는 편이 낫다(ADR-044의 「거짓말 금지」).
  //
  // id를 받고도 쓰지 않는 것은 일부러다 — 실연동 때 이 자리가 「어느 편집본을 받는가」를
  // 채워야 할 곳임을 시그니처로 남긴다.
  const download = useCallback(
    (_id: string) => {
      toast({
        tone: 'info',
        title: '준비 중인 기능이에요',
        description: '편집본 내려받기는 아직 준비 중이에요. 준비되면 알려드릴게요.',
      });
    },
    [toast],
  );

  const remove = useCallback((id: string) => {
    setClips((prev) => prev.filter((clip) => clip.id !== id));
    setSelectedId((prev) => (prev === id ? null : prev));
  }, []);

  // 걸러내기·정렬은 순수 함수에 맡긴다 — 연동 때 이 계산이 서버 질의 조건으로 옮겨가더라도
  // 화면은 클라에서 걸렀는지 서버가 걸러 줬는지 몰라야 한다.
  const counts = useMemo(() => countByChip(clips, role, MOCK_NOW), [clips, role]);
  const visible = useMemo(
    () =>
      sortClips(filterByChip(filterByQuery(clips, query), chip, role, MOCK_NOW), sort, MOCK_NOW),
    [clips, query, chip, role, sort],
  );
  const pendingCount = useMemo(
    () => clips.filter((clip) => statusFor(clip, MOCK_NOW) === 'pending').length,
    [clips],
  );
  // 칩을 바꿔 선택한 카드가 목록에서 빠져도 선택은 남긴다 — 패널은 카드가 아니라 편집본에 대한 것이다
  const selectedClip = useMemo(
    () => clips.find((clip) => clip.id === selectedId) ?? null,
    [clips, selectedId],
  );

  return {
    now: MOCK_NOW,
    role,
    clips: visible,
    totalCount: clips.length,
    counts,
    pendingCount,
    chip,
    setChip,
    query,
    setQuery,
    sort,
    setSort,
    selectedId,
    selectedClip,
    select,
    deselect,
    renameClip,
    upload,
    retryRender,
    download,
    remove,
  };
}
