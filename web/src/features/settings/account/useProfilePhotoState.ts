'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useToast } from '@/ui';

// 디자인 1p 「프로필 사진」 모달의 상태 기계 (POK-206).
//
// 시안이 모션·복귀 규칙을 문장으로 못박아 뒀다: 실패해도 모달을 닫지 않고, 단계 표시를
// 1 / 3으로 유지해 「처음부터 다시」라는 오해를 막는다. 흔들림은 모달 전체가 아니라
// 드롭존만 흔들어 원인 위치를 지목하고, 연속 실패에서는 재생하지 않는다(멀미 방지).
// 캔버스는 여기 없다 — 그림 만드는 일은 화면 쪽(PhotoCropStage·기본 아바타)이 맡는다.

export type PhotoStep = 'idle' | 'empty' | 'error' | 'uploading' | 'crop';

const MAX_BYTES = 5 * 1024 * 1024;
const ACCEPTED = ['image/jpeg', 'image/png', 'image/webp'];

/** 에러 문구를 붙들어 두는 시간. 한국어 두 줄을 읽기 충분하고 지루하지 않은 길이(시안). */
const ERROR_HOLD_MS = 2400;
/** 복귀 페이드 — 이 동안 드롭존이 scale .985 → 1로 돌아온다(시안). */
const RESTORE_MS = 300;
/** 업로드 진행 눈금. 백엔드가 없어 진행률은 흉내다 — 붙으면 실제 진행으로 갈아끼운다. */
const UPLOAD_TICK_MS = 110;
const UPLOAD_STEP = 12;

const STEP_LABEL: Record<Exclude<PhotoStep, 'idle'>, string> = {
  empty: '1 / 3 · 사진 선택',
  // 실패는 선택 단계에서 났다 — 단계를 2로 올리면 진행한 것처럼 보인다
  error: '1 / 3 · 사진 선택',
  uploading: '2 / 3 · 업로드',
  crop: '3 / 3 · 크롭',
};

function megabytes(bytes: number): string {
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
}

/**
 * 거절 문구 전용 표기 — 소수 첫째 자리에서 **올린다**. 반올림하면 상한(5,242,880)을
 * 갓 넘긴 파일이 「5.0MB 파일은 올릴 수 없어요」가 되어, 「5MB 이하」 안내와 나란히 놓였을 때
 * 왜 거절됐는지 알 수 없다.
 */
function megabytesCeil(bytes: number): string {
  return `${(Math.ceil((bytes / 1024 / 1024) * 10) / 10).toFixed(1)}MB`;
}

function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}

export interface ProfilePhotoState {
  step: PhotoStep;
  /** 모달 머리의 「n / 3 · 이름」. 닫혀 있으면 빈 문자열. */
  stepLabel: string;
  /** 크롭에 얹을 원본 (data URL). */
  imageSrc: string | null;
  /** 에러 제목 — 실제 파일 크기가 들어간다. */
  errorTitle: string;
  /** 업로드 중 파일 표기 `이름 · 크기`. */
  fileLabel: string;
  progress: number;
  /** 새로 고른 그림마다 올라간다 — 크롭 위치를 언제 초기화할지 가르는 값. */
  selectionSeq: number;
  /** 드롭존을 한 번 흔드는 중. 연속 실패·동작 줄이기에서는 켜지지 않는다. */
  shaking: boolean;
  /** 에러에서 선택 화면으로 되돌아오는 중 — 페이드 + scale. */
  restoring: boolean;
  open: () => void;
  close: () => void;
  /** 파일을 받는다. 형식·크기를 여기서 판정한다. */
  selectFile: (file: File) => void;
  /** 이미 만들어진 그림으로 곧장 크롭에 든다 (기본 아바타). */
  selectImage: (dataUrl: string) => void;
  cancelUpload: () => void;
  /** 잘라낸 결과를 확정한다 — 저장 시점은 「적용」 1회로 고정(시안). */
  apply: (dataUrl: string) => void;
}

export function useProfilePhotoState(onApply: (dataUrl: string) => void): ProfilePhotoState {
  const { toast } = useToast();
  const [step, setStep] = useState<PhotoStep>('idle');
  const [imageSrc, setImageSrc] = useState<string | null>(null);
  const [errorTitle, setErrorTitle] = useState('');
  const [fileLabel, setFileLabel] = useState('');
  const [progress, setProgress] = useState(0);
  const progressRef = useRef(0);
  /**
   * 「새로 고른 그림」의 세대. 크롭 위치 초기화가 이것에 걸린다 — imageSrc 문자열에 걸면
   * 같은 기본 아바타·같은 파일을 다시 골랐을 때 값이 안 변해 직전 위치·회전이 남는다.
   * 토스트의 「편집」은 올리지 않는다: 방금 자른 자리를 그대로 이어 손보는 동선이다.
   */
  const [selectionSeq, setSelectionSeq] = useState(0);
  const [shaking, setShaking] = useState(false);
  const [restoring, setRestoring] = useState(false);

  // 타이머는 ref에 모아 둔다 — 복귀 대기 중 재드롭·모달 닫기가 전부 이것들을 취소한다
  const restoreTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const fadeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const shakeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const uploadTimer = useRef<ReturnType<typeof setInterval> | null>(null);
  /**
   * 에러 화면을 붙들고 있는 동안 겹쳐 난 실패 횟수 — 2회째부터는 흔들지 않고 문구만
   * 바꾼다(시안의 멀미 방지). 선택 화면으로 복귀하면 0으로 끊긴다: 「연속」은 쉼 없이
   * 이어진 실패를 말하는 것이지 「첫 실패 이후 전부」가 아니다.
   */
  const errorStreak = useRef(0);
  /**
   * 진행 중인 FileReader의 세대. 모달을 닫거나 다른 것을 고르면 올려서 뒤늦게 도착하는
   * onload를 버린다 — 안 그러면 닫은 모달이 업로드 단계로 혼자 다시 열리고, 먼저 시작한
   * 읽기가 나중에 끝나 새 선택을 덮어쓴다.
   */
  const readSeq = useRef(0);

  /** 예약된 타이머와 진행 중인 읽기를 한꺼번에 버린다 — 단계를 옮기는 모든 자리가 부른다. */
  const resetPending = useCallback(() => {
    for (const t of [restoreTimer, fadeTimer, shakeTimer]) {
      if (t.current !== null) clearTimeout(t.current);
      t.current = null;
    }
    if (uploadTimer.current !== null) clearInterval(uploadTimer.current);
    uploadTimer.current = null;
    readSeq.current += 1;
  }, []);

  useEffect(() => resetPending, [resetPending]);

  const startUpload = useCallback(
    (file: File, dataUrl: string) => {
      resetPending();
      errorStreak.current = 0;
      setShaking(false);
      setRestoring(false);
      setImageSrc(dataUrl);
      setFileLabel(`${file.name} · ${megabytes(file.size)}`);
      progressRef.current = 0;
      setProgress(0);
      setStep('uploading');
      uploadTimer.current = setInterval(() => {
        // 진행률은 ref로 센다 — setState 업데이터 안에서 끝을 판정하면 StrictMode가 그
        // 업데이터를 두 번 태우면서 부수효과(타이머 정리·단계 이동)도 두 번 돈다
        const next = progressRef.current + UPLOAD_STEP;
        if (next >= 100) {
          progressRef.current = 100;
          setProgress(100);
          // 올릴 서버가 없어 확정 버튼을 둘 자리가 없다 — 다 차면 그대로 크롭으로 넘긴다
          if (uploadTimer.current !== null) clearInterval(uploadTimer.current);
          uploadTimer.current = null;
          setSelectionSeq((n) => n + 1);
          setStep('crop');
          return;
        }
        progressRef.current = next;
        setProgress(next);
      }, UPLOAD_TICK_MS);
    },
    [resetPending],
  );

  const fail = useCallback(
    (title: string) => {
      resetPending();
      errorStreak.current += 1;
      setErrorTitle(title);
      setStep('error');
      const quiet = prefersReducedMotion() || errorStreak.current > 1;
      setShaking(!quiet);
      if (!quiet) {
        shakeTimer.current = setTimeout(() => setShaking(false), 340);
      }
      restoreTimer.current = setTimeout(() => {
        // 선택 화면으로 돌아온 순간 「연속」은 끊긴다. 여기서 안 지우면 카운터가 계속
        // 쌓여 첫 실패 뒤의 모든 실패가 조용해지고, 원인을 짚어 주던 흔들림이 사라진다.
        errorStreak.current = 0;
        setStep('empty');
        if (quiet) return; // 동작 줄이기 — 페이드 없이 상태만 바꾼다
        setRestoring(true);
        fadeTimer.current = setTimeout(() => setRestoring(false), RESTORE_MS);
      }, ERROR_HOLD_MS);
    },
    [resetPending],
  );

  const selectFile = useCallback(
    (file: File) => {
      if (!ACCEPTED.includes(file.type)) {
        fail('JPG · PNG · WebP만 올릴 수 있어요');
        return;
      }
      if (file.size > MAX_BYTES) {
        fail(`${megabytesCeil(file.size)} 파일은 올릴 수 없어요`);
        return;
      }
      // 이 읽기의 세대를 붙들어 둔다 — 도착했을 때 아직 유효한지 이것으로 판별한다
      const seq = (readSeq.current += 1);
      const reader = new FileReader();
      reader.onload = () => {
        if (seq !== readSeq.current) return; // 닫혔거나 다른 것을 고른 뒤 — 버린다
        startUpload(file, String(reader.result));
      };
      reader.onerror = () => {
        if (seq !== readSeq.current) return;
        fail('파일을 읽지 못했어요');
      };
      reader.readAsDataURL(file);
    },
    [fail, startUpload],
  );

  const selectImage = useCallback(
    (dataUrl: string) => {
      resetPending();
      errorStreak.current = 0;
      setShaking(false);
      setRestoring(false);
      setImageSrc(dataUrl);
      setSelectionSeq((n) => n + 1);
      setStep('crop');
    },
    [resetPending],
  );

  const open = useCallback(() => {
    resetPending();
    errorStreak.current = 0;
    setShaking(false);
    setRestoring(false);
    setStep('empty');
  }, [resetPending]);

  const close = useCallback(() => {
    resetPending();
    setShaking(false);
    setRestoring(false);
    setStep('idle');
  }, [resetPending]);

  const cancelUpload = useCallback(() => {
    resetPending();
    setProgress(0);
    setStep('empty');
  }, [resetPending]);

  const apply = useCallback(
    (dataUrl: string) => {
      resetPending();
      onApply(dataUrl);
      setStep('idle');
      toast({
        tone: 'success',
        title: '프로필 사진을 변경했습니다',
        description: '헤더·사이드바에 바로 반영',
        // 되돌리기가 아니라 편집이다 — 바꾼 사진을 다시 잘라내러 크롭으로 돌아간다
        action: { label: '편집', onClick: () => setStep('crop') },
      });
    },
    [resetPending, onApply, toast],
  );

  return {
    step,
    stepLabel: step === 'idle' ? '' : STEP_LABEL[step],
    imageSrc,
    errorTitle,
    fileLabel,
    progress,
    selectionSeq,
    shaking,
    restoring,
    open,
    close,
    selectFile,
    selectImage,
    cancelUpload,
    apply,
  };
}
