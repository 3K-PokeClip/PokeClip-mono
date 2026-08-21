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
  const [shaking, setShaking] = useState(false);
  const [restoring, setRestoring] = useState(false);

  // 타이머는 ref에 모아 둔다 — 복귀 대기 중 재드롭·모달 닫기가 전부 이것들을 취소한다
  const restoreTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const fadeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const shakeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const uploadTimer = useRef<ReturnType<typeof setInterval> | null>(null);
  /** 연속 실패 횟수 — 2회째부터는 흔들지 않고 문구만 바꾼다(시안). */
  const errorStreak = useRef(0);

  const clearTimers = useCallback(() => {
    for (const t of [restoreTimer, fadeTimer, shakeTimer]) {
      if (t.current !== null) clearTimeout(t.current);
      t.current = null;
    }
    if (uploadTimer.current !== null) clearInterval(uploadTimer.current);
    uploadTimer.current = null;
  }, []);

  useEffect(() => clearTimers, [clearTimers]);

  const startUpload = useCallback(
    (file: File, dataUrl: string) => {
      clearTimers();
      errorStreak.current = 0;
      setShaking(false);
      setRestoring(false);
      setImageSrc(dataUrl);
      setFileLabel(`${file.name} · ${megabytes(file.size)}`);
      setProgress(0);
      setStep('uploading');
      uploadTimer.current = setInterval(() => {
        setProgress((prev) => {
          const next = prev + UPLOAD_STEP;
          if (next < 100) return next;
          // 올릴 서버가 없어 확정 버튼을 둘 자리가 없다 — 다 차면 그대로 크롭으로 넘긴다
          if (uploadTimer.current !== null) clearInterval(uploadTimer.current);
          uploadTimer.current = null;
          setStep('crop');
          return 100;
        });
      }, UPLOAD_TICK_MS);
    },
    [clearTimers],
  );

  const fail = useCallback(
    (title: string) => {
      clearTimers();
      errorStreak.current += 1;
      setErrorTitle(title);
      setStep('error');
      const quiet = prefersReducedMotion() || errorStreak.current > 1;
      setShaking(!quiet);
      if (!quiet) {
        shakeTimer.current = setTimeout(() => setShaking(false), 340);
      }
      restoreTimer.current = setTimeout(() => {
        setStep('empty');
        if (quiet) return; // 동작 줄이기 — 페이드 없이 상태만 바꾼다
        setRestoring(true);
        fadeTimer.current = setTimeout(() => setRestoring(false), RESTORE_MS);
      }, ERROR_HOLD_MS);
    },
    [clearTimers],
  );

  const selectFile = useCallback(
    (file: File) => {
      if (!ACCEPTED.includes(file.type)) {
        fail('JPG · PNG · WebP만 올릴 수 있어요');
        return;
      }
      if (file.size > MAX_BYTES) {
        fail(`${megabytes(file.size)} 파일은 올릴 수 없어요`);
        return;
      }
      const reader = new FileReader();
      reader.onload = () => startUpload(file, String(reader.result));
      reader.onerror = () => fail('파일을 읽지 못했어요');
      reader.readAsDataURL(file);
    },
    [fail, startUpload],
  );

  const selectImage = useCallback(
    (dataUrl: string) => {
      clearTimers();
      errorStreak.current = 0;
      setShaking(false);
      setRestoring(false);
      setImageSrc(dataUrl);
      setStep('crop');
    },
    [clearTimers],
  );

  const open = useCallback(() => {
    clearTimers();
    errorStreak.current = 0;
    setShaking(false);
    setRestoring(false);
    setStep('empty');
  }, [clearTimers]);

  const close = useCallback(() => {
    clearTimers();
    setShaking(false);
    setRestoring(false);
    setStep('idle');
  }, [clearTimers]);

  const cancelUpload = useCallback(() => {
    clearTimers();
    setProgress(0);
    setStep('empty');
  }, [clearTimers]);

  const apply = useCallback(
    (dataUrl: string) => {
      clearTimers();
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
    [clearTimers, onApply, toast],
  );

  return {
    step,
    stepLabel: step === 'idle' ? '' : STEP_LABEL[step],
    imageSrc,
    errorTitle,
    fileLabel,
    progress,
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
