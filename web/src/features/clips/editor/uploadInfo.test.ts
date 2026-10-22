import { describe, expect, it } from 'vitest';
import {
  addTags,
  CATEGORIES,
  defaultSceneOffsetMs,
  descriptionBytes,
  descriptionProblem,
  quickSchedules,
  sceneLabel,
  sceneOffsetFromPlayhead,
  scenePicks,
  scheduleLabel,
  scheduleProblem,
  tagsLength,
  thumbnailFileProblem,
  THUMBNAIL_MAX_BYTES,
} from './uploadInfo';

describe('추천 장면(POK-381): 하이라이트 · 시작 · 재생 위치 · 중간 · 마지막', () => {
  const ids = (picks: ReturnType<typeof scenePicks>) => picks.map((p) => `${p.id}:${p.offsetMs}`);

  it('다 알면 다섯, 순서는 하이라이트가 먼저', () => {
    expect(ids(scenePicks(12_400, 8_000, 3_250))).toEqual([
      'highlight:8000',
      'start:0',
      'playhead:3250',
      'middle:6200',
      'end:12399',
    ]);
  });

  it('모르거나 영상 밖이면 빼고, 앞의 것과 한 눈금(0.25초) 안으로 겹치면 뒤의 것을 뺀다', () => {
    expect(ids(scenePicks(12_400, null, null))).toEqual(['start:0', 'middle:6200', 'end:12399']);
    expect(ids(scenePicks(12_400, 20_000, -1))).toEqual(['start:0', 'middle:6200', 'end:12399']);
    // 재생 위치가 처음(0.1초)이면 「시작」과 같은 그림이고, 하이라이트가 가운데 근처면 「중간」과 같다
    expect(ids(scenePicks(12_400, 6_300, 100))).toEqual(['highlight:6300', 'start:0', 'end:12399']);
  });

  it('시각 표기는 분:초.십분의일초', () => {
    expect(sceneLabel(3_250)).toBe('0:03.2');
    expect(sceneLabel(65_400)).toBe('1:05.4');
  });
});

describe('예약 공개(POK-381): 지금부터 1시간 뒤부터', () => {
  // 2026-10-22 20:30(이 기기 시각)
  const now = new Date(2026, 9, 22, 20, 30).getTime();

  it('날짜·시각이 틀렸거나 1시간 안이면 까닭을 말한다', () => {
    expect(scheduleProblem('', '18:00', now)).toBe('날짜와 시각을 골라 주세요');
    expect(scheduleProblem('2026-10-22', '21:00', now)).toBe(
      '지금부터 1시간 뒤부터 고를 수 있어요',
    );
    expect(scheduleProblem('2026-10-22', '21:30', now)).toBeNull();
    expect(scheduleProblem('2026-10-21', '23:00', now)).toBe(
      '지금부터 1시간 뒤부터 고를 수 있어요',
    );
  });

  it('빠른 고르기는 1시간이 안 남은 것을 뺀다(저녁 늦게 열면 「오늘 21:00」이 없다)', () => {
    expect(quickSchedules(now).map((q) => q.label)).toEqual(['내일 18:00', '내일 21:00']);
    const morning = new Date(2026, 9, 22, 9, 0).getTime();
    expect(quickSchedules(morning).map((q) => q.label)).toEqual([
      '오늘 21:00',
      '내일 18:00',
      '내일 21:00',
    ]);
  });

  it('표기: 10월 23일 (금) 18:00', () => {
    expect(scheduleLabel('2026-10-23', '18:00')).toBe('10월 23일 (금) 18:00');
    expect(scheduleLabel('2026-13-40', '18:00')).toBe('');
  });
});

describe('카테고리(POK-381)', () => {
  it('clip이 받는 목록과 같다(UploadInfoParser.CATEGORIES) · 처음 값 게임이 맨 앞', () => {
    expect(CATEGORIES.map((c) => c.value).sort((a, b) => Number(a) - Number(b))).toEqual([
      '1',
      '2',
      '10',
      '15',
      '17',
      '19',
      '20',
      '22',
      '23',
      '24',
      '25',
      '26',
      '27',
      '28',
      '29',
    ]);
    expect(CATEGORIES[0]).toEqual({ value: '20', label: '게임' });
  });
});

// 업로드 정보 창(POK-291)의 규칙. 서버(clip 렌더 주문 문)가 같은 것을 400으로 거절하지만 보내기 전에 창 안에서 말한다.

describe('장면 시각: 완성 영상 첫 장면 기준 ms', () => {
  // 구간 시작 60초, 컷 12.4초
  const start = 60;
  const cut = 12_400;

  it('재생 위치가 구간 안이면 그 상대 시각이다', () => {
    expect(sceneOffsetFromPlayhead(63.25, start, cut)).toBe(3_250);
    expect(defaultSceneOffsetMs(63.25, start, cut)).toBe(3_250);
    expect(defaultSceneOffsetMs(60, start, cut)).toBe(0);
  });

  it('재생 위치가 구간 앞이나 뒤면 기본값은 가운데다', () => {
    expect(defaultSceneOffsetMs(59.9, start, cut)).toBe(6_200);
    expect(defaultSceneOffsetMs(72.4, start, cut)).toBe(6_200);
    expect(defaultSceneOffsetMs(500, start, cut)).toBe(6_200);
  });

  it('「지금 재생 위치로」는 구간 밖이어도 영상 안으로 자른다: 0 ~ 컷 길이−1', () => {
    expect(sceneOffsetFromPlayhead(10, start, cut)).toBe(0);
    expect(sceneOffsetFromPlayhead(90, start, cut)).toBe(cut - 1);
  });

  it('5초보다 짧아 늘어난 컷은 늘어난 뒤도 영상 안이다', () => {
    // 구간 2초짜리가 5초 컷으로 늘었다: 구간 끝 뒤 3초 자리도 완성 영상에 있다
    expect(defaultSceneOffsetMs(63, start, 5_000)).toBe(3_000);
  });

  it('3분보다 길어 잘린 컷은 잘린 뒤가 영상 밖이다', () => {
    // 구간 4분이 3분 컷으로 잘렸다: 3분 20초 자리는 완성 영상에 없다
    expect(defaultSceneOffsetMs(start + 200, start, 180_000)).toBe(90_000);
    expect(defaultSceneOffsetMs(start + 179.5, start, 180_000)).toBe(179_500);
  });

  it('녹화가 없으면(재생 위치를 모르면) 가운데고, 지금 위치로 맞출 수도 없다', () => {
    expect(defaultSceneOffsetMs(null, start, cut)).toBe(6_200);
    expect(sceneOffsetFromPlayhead(null, start, cut)).toBeNull();
  });
});

describe('태그: 합계 500자 규칙', () => {
  it('공백이 든 태그는 따옴표 두 글자를 더 세고, 태그 사이 쉼표를 하나씩 센다', () => {
    expect(tagsLength([])).toBe(0);
    expect(tagsLength(['롤'])).toBe(1);
    expect(tagsLength(['롤', '보스 막타'])).toBe(1 + (5 + 2) + 1);
    // 글자는 코드포인트로 센다: 이모지 하나가 1이다
    expect(tagsLength(['😀'])).toBe(1);
  });

  it('쉼표로 여럿을 한 번에 넣고, 앞뒤 공백을 깎고, 빈 것은 건너뛰고, 같은 것은 하나만 둔다', () => {
    expect(addTags(['롤'], ' 보스 ,, 막타 ,롤,보스')).toEqual({ tags: ['롤', '보스', '막타'] });
  });

  it('< > 가 든 태그는 넣지 않는다', () => {
    expect(addTags(['롤'], 'a<b')).toEqual({
      tags: ['롤'],
      problem: '태그에는 < 와 > 를 쓸 수 없어요.',
    });
  });

  it('합계가 500을 넘으면 넣지 않는다: 공백 따옴표까지 세서 정확히 경계에서 갈린다', () => {
    // 'a b'(3) + 따옴표 2 = 5, 쉼표 1 → 99개면 99×5 + 98 = 593. 경계를 직접 맞춘다
    const base = Array.from({ length: 83 }, (_, i) => `t${String(i).padStart(3, '0')}`); // 4글자 × 83 + 쉼표 82 = 414
    expect(tagsLength(base)).toBe(414);
    // 공백 든 태그 하나: 쉼표 1 + 글자 83 + 따옴표 2 = 86 → 정확히 500
    const fits = `${'x'.repeat(41)} ${'y'.repeat(41)}`;
    expect(addTags(base, fits).problem).toBeUndefined();
    expect(tagsLength(addTags(base, fits).tags)).toBe(500);
    // 한 글자 더 길면 501: 따옴표 두 글자를 빼먹으면 이것이 들어간다
    const over = `${'x'.repeat(42)} ${'y'.repeat(41)}`;
    expect(addTags(base, over)).toEqual({ tags: base, problem: '태그는 합쳐서 500자까지예요.' });
  });
});

describe('설명: UTF-8 5000바이트', () => {
  it('한글은 한 글자에 3바이트다', () => {
    expect(descriptionBytes('가a')).toBe(4);
  });

  it('5000바이트를 넘거나 < > 가 있으면 까닭을 말한다', () => {
    expect(descriptionProblem('가'.repeat(1666) + 'aa')).toBeNull(); // 5000
    expect(descriptionProblem('가'.repeat(1667))).toBe('설명은 5000바이트까지예요.');
    expect(descriptionProblem('a > b')).toBe('설명에는 < 와 > 를 쓸 수 없어요.');
  });
});

describe('썸네일 이미지 파일', () => {
  const file = (type: string, size: number) => new File([new Uint8Array(size)], 'x', { type });

  it('JPG·PNG이고 10MB 이하면 받는다', () => {
    expect(thumbnailFileProblem(file('image/jpeg', 10))).toBeNull();
    expect(thumbnailFileProblem(file('image/png', THUMBNAIL_MAX_BYTES))).toBeNull();
  });

  it('10MB를 넘거나 다른 형식이면 까닭을 말한다', () => {
    expect(thumbnailFileProblem(file('image/png', THUMBNAIL_MAX_BYTES + 1))).toBe(
      '이미지는 10MB까지 올릴 수 있어요',
    );
    expect(thumbnailFileProblem(file('image/webp', 10))).toBe('JPG나 PNG만 올릴 수 있어요');
  });
});
