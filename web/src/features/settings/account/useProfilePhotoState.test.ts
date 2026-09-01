import { act, renderHook, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { useProfilePhotoState } from './useProfilePhotoState';
import { withToastProvider } from '@/test/testProviders';

// 1p의 모션·복귀 규칙이 시간에 걸려 있어(0.34s 흔들림 · 2.4s 유지 · 0.3s 복귀)
// 화면 테스트로는 잡히지 않는다 — 상태 기계를 시계째 붙잡고 검사한다.
// 업로드는 주입한 프라미스를 붙들었다 풀어 진행 중·성공·실패·취소를 시계와 무관하게 만든다.

const DATA_URL = 'data:image/png;base64,AAA';
/** 크롭 결과 흉내 — apply가 Blob으로 바꿔 올린다. 'AAA'는 base64 길이가 4k+3이라 atob이 통과시킨다. */
const CROPPED = `data:image/png;base64,${btoa('cropped-png')}`;

/** onload를 동기로 부르는 가짜 — jsdom FileReader의 비동기 타이밍을 시계와 섞지 않는다. */
class SyncFileReader {
  result: string = DATA_URL;
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  readAsDataURL() {
    this.onload?.();
  }
}

function fileOf(name: string, type: string, size: number): File {
  const file = new File(['x'], name, { type });
  Object.defineProperty(file, 'size', { value: size });
  return file;
}

const OVERSIZE = () => fileOf('face-cam-0812.png', 'image/png', Math.round(8.2 * 1024 * 1024));
const OK_FILE = () => fileOf('face-cam-0812.png', 'image/png', Math.round(2.4 * 1024 * 1024));

type Upload = (blob: Blob, filename: string, signal: AbortSignal) => Promise<void>;

/** 업로드를 붙들어 두고 원할 때 풀어 준다 — 「요청이 나가 있는 동안」을 만든다. */
function deferredUpload() {
  let resolve: (() => void) | null = null;
  let reject: ((e: unknown) => void) | null = null;
  const calls: Array<{ blob: Blob; filename: string; signal: AbortSignal }> = [];
  const upload = vi.fn<Upload>((blob, filename, signal) => {
    calls.push({ blob, filename, signal });
    return new Promise<void>((res, rej) => {
      resolve = res;
      reject = rej;
    });
  });
  return {
    upload,
    calls,
    resolve: () => act(async () => resolve?.()),
    reject: (e: unknown) => act(async () => reject?.(e)),
  };
}

function setup(options: { upload?: Upload; onCanceled?: () => void } = {}) {
  const upload = options.upload ?? vi.fn<Upload>().mockResolvedValue(undefined);
  const view = renderHook(() => useProfilePhotoState({ upload, onCanceled: options.onCanceled }), {
    wrapper: withToastProvider,
  });
  act(() => view.result.current.open());
  return { ...view, upload };
}

describe('useProfilePhotoState', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.stubGlobal('FileReader', SyncFileReader);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('열면 사진 선택 단계로 시작한다', () => {
    const { result } = setup();
    expect(result.current.step).toBe('empty');
    expect(result.current.stepLabel).toBe('1 / 3 · 사진 선택');
  });

  it('5MB를 넘으면 실제 크기를 넣어 거절하고 단계 표시는 1 / 3에 머문다', () => {
    const { result } = setup();

    act(() => result.current.selectFile(OVERSIZE()));

    expect(result.current.step).toBe('error');
    expect(result.current.errorTitle).toBe('8.2MB 파일은 올릴 수 없어요');
    // 실패는 선택 단계에서 났다 — 2 / 3으로 올리면 진행한 것처럼 보인다
    expect(result.current.stepLabel).toBe('1 / 3 · 사진 선택');
    expect(result.current.shaking).toBe(true);
  });

  it('허용하지 않는 형식도 같은 자리에서 거절한다', () => {
    const { result } = setup();

    act(() => result.current.selectFile(fileOf('clip.gif', 'image/gif', 1024)));

    expect(result.current.step).toBe('error');
    expect(result.current.errorTitle).toBe('JPG · PNG · WebP만 올릴 수 있어요');
  });

  it('안내는 실패 종류를 따라간다 — 형식 오류에 「5MB 이하로 줄여」라 하지 않는다', () => {
    const { result } = setup();

    act(() => result.current.selectFile(fileOf('clip.gif', 'image/gif', 1024)));
    expect(result.current.errorHint).not.toContain('5MB');
    expect(result.current.errorHint).toContain('형식');

    act(() => result.current.selectFile(OVERSIZE()));
    expect(result.current.errorHint).toContain('5MB');
  });

  it('흔들림은 340ms에 끝나고, 2.4초 뒤 선택 화면으로 돌아오며 300ms 복귀가 붙는다', () => {
    const { result } = setup();
    act(() => result.current.selectFile(OVERSIZE()));

    act(() => void vi.advanceTimersByTime(340));
    expect(result.current.shaking).toBe(false);
    expect(result.current.step).toBe('error'); // 아직 에러 문구를 붙들고 있다

    act(() => void vi.advanceTimersByTime(2400 - 340));
    expect(result.current.step).toBe('empty');
    expect(result.current.restoring).toBe(true);

    act(() => void vi.advanceTimersByTime(300));
    expect(result.current.restoring).toBe(false);
  });

  it('연속 실패에서는 흔들지 않고 문구만 바꾼다 — 멀미 방지', () => {
    const { result } = setup();

    act(() => result.current.selectFile(OVERSIZE()));
    expect(result.current.shaking).toBe(true);

    // 복귀를 기다리지 않고 곧장 다시 실패시킨다
    act(() => result.current.selectFile(fileOf('big.png', 'image/png', 9 * 1024 * 1024)));
    expect(result.current.step).toBe('error');
    expect(result.current.shaking).toBe(false);
    expect(result.current.errorTitle).toBe('9.0MB 파일은 올릴 수 없어요');
  });

  it('선택 화면으로 돌아온 뒤의 실패는 다시 흔든다 — 연속이 끊겼다', () => {
    const { result } = setup();

    act(() => result.current.selectFile(OVERSIZE()));
    expect(result.current.shaking).toBe(true);

    // 2.4초 유지 + 0.3초 복귀를 다 태워 선택 화면으로 돌아온다
    act(() => void vi.advanceTimersByTime(2400 + 300));
    expect(result.current.step).toBe('empty');

    // 여기서도 조용하면 첫 실패 뒤 흔들림이 영영 사라진다 — 원인을 짚어 주는 신호를 잃는다
    act(() => result.current.selectFile(OVERSIZE()));
    expect(result.current.shaking).toBe(true);
  });

  it('연속 실패에서도 복귀 페이드는 살아 있다 — 빼는 것은 흔들림뿐이다', () => {
    const { result } = setup();

    act(() => result.current.selectFile(OVERSIZE()));
    act(() => result.current.selectFile(OVERSIZE())); // 연속 — 흔들림은 빠진다
    expect(result.current.shaking).toBe(false);

    act(() => void vi.advanceTimersByTime(2400));
    expect(result.current.step).toBe('empty');
    // 멀미 방지는 흔들림에 대한 것이다. 페이드까지 빼면 시안의 복귀 규칙이 깨진다
    expect(result.current.restoring).toBe(true);
  });

  it('복귀 대기 중 다시 놓으면 타이머를 취소하고 즉시 크롭으로 간다', () => {
    const { result } = setup();
    act(() => result.current.selectFile(OVERSIZE()));

    act(() => void vi.advanceTimersByTime(1000)); // 2.4초가 되기 전
    act(() => result.current.selectFile(OK_FILE()));
    expect(result.current.step).toBe('crop');

    // 취소된 복귀 타이머가 뒤늦게 깨어나 크롭을 선택 화면으로 되돌리면 안 된다
    act(() => void vi.advanceTimersByTime(2400));
    expect(result.current.step).toBe('crop');
  });

  it('파일을 고르면 읽자마자 곧장 크롭이다 — 파일 표기를 달고, 업로드 화면을 거치지 않는다', () => {
    const { result } = setup();
    const before = result.current.selectionSeq;

    act(() => result.current.selectFile(OK_FILE()));

    // 읽기는 브라우저 안의 일이다 — 「업로드」라고 부르면 서버로 간 것처럼 보인다
    expect(result.current.step).toBe('crop');
    expect(result.current.stepLabel).toBe('2 / 3 · 크롭');
    expect(result.current.fileLabel).toBe('face-cam-0812.png · 2.4MB');
    expect(result.current.imageSrc).toBe(DATA_URL);
    // 새 그림이라 크롭 위치가 초기화될 자리 — 세대가 오른다
    expect(result.current.selectionSeq).toBe(before + 1);
  });

  it('닫은 뒤 도착한 읽기는 버린다 — 닫은 모달이 혼자 다시 열리지 않는다', () => {
    // onload를 붙들었다가 원할 때 터뜨린다
    let fire: (() => void) | null = null;
    vi.stubGlobal(
      'FileReader',
      class {
        result = DATA_URL;
        onload: (() => void) | null = null;
        onerror: (() => void) | null = null;
        readAsDataURL() {
          fire = () => this.onload?.();
        }
      },
    );
    const { result } = setup();

    act(() => result.current.selectFile(OK_FILE()));
    expect(result.current.step).toBe('empty'); // 아직 읽는 중

    act(() => result.current.close());
    act(() => fire?.());

    expect(result.current.step).toBe('idle');
  });

  it('새로 고르면 먼저 시작한 읽기가 나중에 끝나도 덮어쓰지 못한다', () => {
    const fires: Array<() => void> = [];
    vi.stubGlobal(
      'FileReader',
      class {
        result = DATA_URL;
        onload: (() => void) | null = null;
        onerror: (() => void) | null = null;
        readAsDataURL() {
          fires.push(() => this.onload?.());
        }
      },
    );
    const { result } = setup();

    act(() => result.current.selectFile(fileOf('first.png', 'image/png', 1024)));
    act(() => result.current.selectFile(fileOf('second.png', 'image/png', 2048)));

    act(() => fires[1]?.()); // 나중 것이 먼저 끝난다
    expect(result.current.fileLabel).toContain('second.png');

    act(() => fires[0]?.()); // 뒤늦게 도착한 첫 읽기
    expect(result.current.fileLabel).toContain('second.png');
  });

  it('상한을 갓 넘긴 파일도 5.0MB로 표기되지 않는다 — 올려서 적는다', () => {
    const { result } = setup();

    // 5,250,000B = 5.006MB. 반올림하면 「5.0MB 파일은 올릴 수 없어요」가 되어
    // 「5MB 이하」 안내와 나란히 놓였을 때 왜 거절됐는지 알 수 없다
    act(() => result.current.selectFile(fileOf('edge.png', 'image/png', 5_250_000)));

    expect(result.current.errorTitle).toBe('5.1MB 파일은 올릴 수 없어요');
  });

  it('같은 그림을 다시 골라도 선택 세대가 올라간다 — 크롭 위치가 초기화될 자리', () => {
    const { result } = setup();
    const same = 'data:image/png;base64,SAME';

    act(() => result.current.selectImage(same));
    const first = result.current.selectionSeq;

    act(() => result.current.open());
    act(() => result.current.selectImage(same)); // imageSrc는 그대로다
    expect(result.current.selectionSeq).toBeGreaterThan(first);
  });

  it('기본 아바타는 곧장 크롭으로 든다 — 파일 표기는 「기본 아바타」', () => {
    const { result } = setup();

    act(() => result.current.selectImage('data:image/png;base64,BBB'));
    expect(result.current.step).toBe('crop');
    expect(result.current.imageSrc).toBe('data:image/png;base64,BBB');
    // 업로드 중 파일 표기 자리가 비지 않게 — 기본 아바타에는 파일 이름이 없다
    expect(result.current.fileLabel).toBe('기본 아바타');
  });

  it('적용은 크롭 결과를 Blob으로 올리고, 성공하면 모달을 닫고 토스트로 알린다 — 저장 시점은 이 1회다', async () => {
    const deferred = deferredUpload();
    const { result } = setup({ upload: deferred.upload });
    act(() => result.current.selectImage(DATA_URL));

    act(() => result.current.apply(CROPPED));

    expect(result.current.step).toBe('uploading');
    expect(result.current.stepLabel).toBe('3 / 3 · 업로드');
    expect(result.current.uploading).toBe(true);
    expect(deferred.upload).toHaveBeenCalledTimes(1);
    const call = deferred.calls[0];
    expect(call?.blob.type).toBe('image/png');
    expect(call?.blob.size).toBe('cropped-png'.length);
    expect(call?.filename).toBe('avatar.png');
    expect(call?.signal.aborted).toBe(false);

    await deferred.resolve();

    expect(result.current.step).toBe('idle');
    expect(result.current.stepLabel).toBe('');
    expect(result.current.uploading).toBe(false);
    expect(screen.getByText('프로필 사진을 변경했습니다')).toBeInTheDocument();
  });

  it('업로드 실패는 자르던 자리로 돌아와 사유를 말한다 — 선택 세대는 그대로라 크롭 위치가 살아남는다', async () => {
    const deferred = deferredUpload();
    const { result } = setup({ upload: deferred.upload });
    act(() => result.current.selectImage(DATA_URL));
    const seq = result.current.selectionSeq;

    act(() => result.current.apply(CROPPED));
    await deferred.reject(new ApiError(503, 'PHOTO_STORAGE_DISABLED'));

    expect(result.current.step).toBe('crop');
    expect(result.current.uploading).toBe(false);
    expect(result.current.uploadError).toBe(
      '지금은 사진을 올릴 수 없어요 · 잠시 후 다시 시도해 주세요',
    );
    // 세대가 오르면 화면이 transform을 초기화해 재시도마다 자른 자리가 날아간다
    expect(result.current.selectionSeq).toBe(seq);
    expect(screen.queryByText('프로필 사진을 변경했습니다')).toBeNull();
  });

  it('실패 사유는 상태 코드를 따라 갈린다 — 413은 「줄여서 다시」, 폴백은 원인을 단정하지 않는다', async () => {
    const deferred = deferredUpload();
    const { result } = setup({ upload: deferred.upload });
    act(() => result.current.selectImage(DATA_URL));

    act(() => result.current.apply(CROPPED));
    await deferred.reject(new ApiError(413, 'PHOTO_TOO_LARGE'));
    expect(result.current.uploadError).toContain('더 작은 사진');

    act(() => result.current.apply(CROPPED));
    await deferred.reject(new TypeError('Failed to fetch'));
    expect(result.current.uploadError).toBe('사진을 올리지 못했어요 · 잠시 후 다시 시도해 주세요');
  });

  it('다시 적용하면 앞선 실패 문구는 걷힌다', async () => {
    const deferred = deferredUpload();
    const { result } = setup({ upload: deferred.upload });
    act(() => result.current.selectImage(DATA_URL));
    act(() => result.current.apply(CROPPED));
    await deferred.reject(new ApiError(503, 'PHOTO_STORAGE_DISABLED'));
    expect(result.current.uploadError).not.toBeNull();

    act(() => result.current.apply(CROPPED));

    expect(result.current.uploadError).toBeNull();
    expect(result.current.step).toBe('uploading');
  });

  it('업로드 중 취소는 요청을 끊고 자르던 자리로 돌아온다 — 호출부에 알려 진실을 다시 읽게 한다', async () => {
    const deferred = deferredUpload();
    const onCanceled = vi.fn();
    const { result } = setup({ upload: deferred.upload, onCanceled });
    act(() => result.current.selectImage(DATA_URL));
    const seq = result.current.selectionSeq;
    act(() => result.current.apply(CROPPED));

    act(() => result.current.cancelUpload());

    expect(deferred.calls[0]?.signal.aborted).toBe(true);
    expect(result.current.step).toBe('crop');
    expect(result.current.uploading).toBe(false);
    expect(result.current.uploadError).toBeNull();
    expect(result.current.selectionSeq).toBe(seq);
    // 서버는 창고에 먼저 쓰므로 취소해도 이미 올라갔을 수 있다 — me를 다시 읽는 신호
    expect(onCanceled).toHaveBeenCalledTimes(1);

    // 끊긴 요청이 뒤늦게 AbortError로 끝나도 실패 문구를 띄우지 않는다
    await deferred.reject(new DOMException('The operation was aborted.', 'AbortError'));
    expect(result.current.step).toBe('crop');
    expect(result.current.uploadError).toBeNull();
  });

  it('업로드 중 닫으면 요청을 끊고 알린다 — 늦게 온 성공이 화면을 되돌리거나 토스트를 띄우지 않는다', async () => {
    const deferred = deferredUpload();
    const onCanceled = vi.fn();
    const { result } = setup({ upload: deferred.upload, onCanceled });
    act(() => result.current.selectImage(DATA_URL));
    act(() => result.current.apply(CROPPED));

    act(() => result.current.close());

    expect(result.current.step).toBe('idle');
    expect(deferred.calls[0]?.signal.aborted).toBe(true);
    expect(onCanceled).toHaveBeenCalledTimes(1);

    await deferred.resolve();
    expect(result.current.step).toBe('idle');
    expect(screen.queryByText('프로필 사진을 변경했습니다')).toBeNull();
  });

  it('화면이 사라지면 진행 중인 업로드를 끊고 알린다', () => {
    const deferred = deferredUpload();
    const onCanceled = vi.fn();
    const { result, unmount } = setup({ upload: deferred.upload, onCanceled });
    act(() => result.current.selectImage(DATA_URL));
    act(() => result.current.apply(CROPPED));

    unmount();

    expect(deferred.calls[0]?.signal.aborted).toBe(true);
    expect(onCanceled).toHaveBeenCalledTimes(1);
  });

  it('진행 중이 아닐 때의 취소는 아무 일도 하지 않는다', () => {
    const onCanceled = vi.fn();
    const { result } = setup({ onCanceled });
    act(() => result.current.selectImage(DATA_URL));

    act(() => result.current.cancelUpload());

    expect(result.current.step).toBe('crop');
    expect(onCanceled).not.toHaveBeenCalled();
  });

  it('업로드 중에는 적용을 다시 눌러도 두 번째 요청을 만들지 않는다', () => {
    const deferred = deferredUpload();
    const { result } = setup({ upload: deferred.upload });
    act(() => result.current.selectImage(DATA_URL));

    act(() => result.current.apply(CROPPED));
    act(() => result.current.apply(CROPPED));

    expect(deferred.upload).toHaveBeenCalledTimes(1);
  });

  it('Blob으로 못 만드는 결과는 보내지 않고 그 자리에서 알린다 — 캔버스 폴백으로 원본이 그대로 온 경우', () => {
    const { result, upload } = setup();
    act(() => result.current.selectImage(DATA_URL));

    act(() => result.current.apply('data:original'));

    expect(upload).not.toHaveBeenCalled();
    expect(result.current.step).toBe('crop');
    expect(result.current.uploadError).toBe('이 사진은 올릴 수 없어요 · 다른 사진을 골라 주세요');
  });

  it('동작 줄이기에서는 흔들림도 복귀 페이드도 재생하지 않고 상태만 바꾼다', () => {
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() })),
    );
    const { result } = setup();

    act(() => result.current.selectFile(OVERSIZE()));
    expect(result.current.step).toBe('error');
    expect(result.current.shaking).toBe(false);

    act(() => void vi.advanceTimersByTime(2400));
    expect(result.current.step).toBe('empty');
    expect(result.current.restoring).toBe(false);
  });
});
