import type { LibraryClip, LibraryOptions } from './useLibraryMockState';

// 시안 1g 보관함의 고정 데이터 — 시험·스토리북 전용(POK-251).
// 화면은 clip 보관함 문에서 편집본을 받는다. 예전에 훅 안에 있던 목업 8건(상태 7종이 한 화면에 다 보인다)을
// 여기로 옮겨, 시험이 시안의 흐름(업로드 요청·승인 대기·반려·만료)을 계속 잴 수 있게 한다.

// 「지금」을 고정한다. 클라이언트 시계로 계산하면 하이드레이션이 어긋나고(MOCK_GREETING 선례),
// 목업 날짜와 함께 얼면 D-day가 결정적이라 테스트가 시계를 조작할 필요도 없다.
// 시안의 「오늘 라이브 · D-60」「8월 31일 · D-58」을 역산하면 9월 2일 저녁이다.
export const MOCK_NOW = new Date('2026-09-02T20:00:00+09:00');

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
export const MOCK_CLIPS: LibraryClip[] = [
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

/** LibraryScreen에 그대로 펼쳐 넣는 주입값 */
export const LIBRARY_FIXTURE: Pick<Required<LibraryOptions>, 'clips' | 'now'> = {
  clips: MOCK_CLIPS,
  now: MOCK_NOW,
};
