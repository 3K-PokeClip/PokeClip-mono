import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useProfilePhotoState } from './useProfilePhotoState';
import { withToastProvider } from '@/test/testProviders';

// 1p의 모션·복귀 규칙이 시간에 걸려 있어(0.34s 흔들림 · 2.4s 유지 · 0.3s 복귀)
// 화면 테스트로는 잡히지 않는다 — 상태 기계를 시계째 붙잡고 검사한다.

const DATA_URL = 'data:image/png;base64,AAA';

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

function setup(onApply = vi.fn()) {
  const view = renderHook(() => useProfilePhotoState(onApply), { wrapper: withToastProvider });
  act(() => view.result.current.open());
  return view;
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

  it('복귀 대기 중 다시 놓으면 타이머를 취소하고 즉시 업로드로 간다', () => {
    const { result } = setup();
    act(() => result.current.selectFile(OVERSIZE()));

    act(() => void vi.advanceTimersByTime(1000)); // 2.4초가 되기 전
    act(() => result.current.selectFile(OK_FILE()));
    expect(result.current.step).toBe('uploading');

    // 취소된 복귀 타이머가 뒤늦게 깨어나 업로드를 선택 화면으로 되돌리면 안 된다
    act(() => void vi.advanceTimersByTime(2400));
    expect(result.current.step).not.toBe('empty');
  });

  it('업로드는 파일 표기를 달고 진행률이 다 차면 크롭으로 넘어간다', () => {
    const { result } = setup();

    act(() => result.current.selectFile(OK_FILE()));
    expect(result.current.step).toBe('uploading');
    expect(result.current.stepLabel).toBe('2 / 3 · 업로드');
    expect(result.current.fileLabel).toBe('face-cam-0812.png · 2.4MB');
    expect(result.current.imageSrc).toBe(DATA_URL);

    act(() => void vi.advanceTimersByTime(110 * 9));
    expect(result.current.step).toBe('crop');
    expect(result.current.stepLabel).toBe('3 / 3 · 크롭');
    expect(result.current.progress).toBe(100);
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

  it('업로드 취소는 선택 화면으로 되돌린다', () => {
    const { result } = setup();
    act(() => result.current.selectFile(OK_FILE()));

    act(() => result.current.cancelUpload());
    expect(result.current.step).toBe('empty');
    expect(result.current.progress).toBe(0);
  });

  it('기본 아바타는 업로드를 건너뛰고 곧장 크롭으로 든다', () => {
    const { result } = setup();

    act(() => result.current.selectImage('data:image/png;base64,BBB'));
    expect(result.current.step).toBe('crop');
    expect(result.current.imageSrc).toBe('data:image/png;base64,BBB');
  });

  it('적용은 결과를 넘기고 모달을 닫는다 — 저장 시점은 이 1회다', () => {
    const onApply = vi.fn();
    const { result } = setup(onApply);
    act(() => result.current.selectImage(DATA_URL));

    act(() => result.current.apply('data:image/png;base64,CROPPED'));

    expect(onApply).toHaveBeenCalledWith('data:image/png;base64,CROPPED');
    expect(result.current.step).toBe('idle');
    expect(result.current.stepLabel).toBe('');
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
