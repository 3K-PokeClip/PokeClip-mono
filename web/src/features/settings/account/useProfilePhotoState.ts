'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { photoFailureMessage } from '@/api/profile';
import { useToast } from '@/ui';
import { dataUrlToBlob } from './dataUrl';

// 디자인 1p 「프로필 사진」 모달의 상태 기계 (POK-206 → 실서버 배선 POK-208).
//
// 시안이 모션·복귀 규칙을 문장으로 못박아 뒀다: 실패해도 모달을 닫지 않고, 단계 표시를
// 1 / 3으로 유지해 「처음부터 다시」라는 오해를 막는다. 흔들림은 모달 전체가 아니라
// 드롭존만 흔들어 원인 위치를 지목하고, 연속 실패에서는 재생하지 않는다(멀미 방지).
// 캔버스는 여기 없다 — 그림 만드는 일은 화면 쪽(PhotoCropStage·기본 아바타)이 맡는다.
//
// 단계 순서는 시안(선택 → 업로드 → 크롭)과 다르게 **선택 → 크롭 → 업로드**다. 서버는 잘라낸
// 최종 그림만 받으므로(서버 크롭 없음, PUT /api/auth/me/photo) 실제 업로드는 크롭 뒤에만 있을 수
// 있다. 시안의 2단계 「업로드」는 목업 시절의 가짜 진행률이었다. 진행률은 그리지 않는다 —
// fetch는 업로드 진행 이벤트를 주지 않고, XHR로 바꾸면 apiFetch의 401 회전을 잃는다.

export type PhotoStep = 'idle' | 'empty' | 'error' | 'uploading' | 'crop';

/**
 * 원본 파일 상한. 서버 상한(2MB)의 미러가 아니다 — 서버로 가는 것은 512px 크롭 결과(최악 ~1MB,
 * services/README 실측)라 원본 크기와 무관하고, 이 값은 FileReader·디코드 비용을 막는 화면 쪽 상한이다.
 */
const MAX_BYTES = 5 * 1024 * 1024;
const ACCEPTED = ['image/jpeg', 'image/png', 'image/webp'];

/** 에러 문구를 붙들어 두는 시간. 한국어 두 줄을 읽기 충분하고 지루하지 않은 길이(시안). */
const ERROR_HOLD_MS = 2400;
/** 복귀 페이드 — 이 동안 드롭존이 scale .985 → 1로 돌아온다(시안). */
const RESTORE_MS = 300;

const STEP_LABEL: Record<Exclude<PhotoStep, 'idle'>, string> = {
  empty: '1 / 3 · 사진 선택',
  // 실패는 선택 단계에서 났다 — 단계를 2로 올리면 진행한 것처럼 보인다
  error: '1 / 3 · 사진 선택',
  crop: '2 / 3 · 크롭',
  uploading: '3 / 3 · 업로드',
};

/**
 * 보낼 그림을 만들지 못했을 때 — 캔버스를 못 잡아 `cropToDataUrl`이 `null`을 줬거나(브라우저의
 * 캔버스 한도·메모리 압박), 만들어진 주소가 base64 data URL이 아니다. 어느 쪽이든 **자르지 못한
 * 것을 대신 보내지 않는다** — 잘리지 않은 원본이 아바타로 굳는 것보다 여기서 끊는 편이 낫다.
 */
const UNSENDABLE_MESSAGE = '이 사진은 올릴 수 없어요 · 다른 사진을 골라 주세요';

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

export interface ProfilePhotoUploadOptions {
  /**
   * 잘라낸 사진을 서버에 올린다 — 성공하면 호출부가 me 캐시를 응답으로 덮는다. 취소 신호를
   * 그대로 실어 보내고, 실패는 그대로 던진다(문구는 이 훅이 photoFailureMessage로 만든다).
   */
  upload: (blob: Blob, filename: string, signal: AbortSignal) => Promise<void>;
  /**
   * 업로드를 취소하거나 도중에 닫은 뒤 — 서버는 창고에 먼저 쓰고 표를 나중에 갱신하므로 취소는
   * 「기다리지 않겠다」이지 「안 올렸다」가 아니다. 호출부가 me를 다시 읽어 진실을 맞춘다.
   */
  onCanceled?: () => void;
}

export interface ProfilePhotoState {
  step: PhotoStep;
  /** 모달 머리의 「n / 3 · 이름」. 닫혀 있으면 빈 문자열. */
  stepLabel: string;
  /** 크롭에 얹을 원본 (data URL). */
  imageSrc: string | null;
  /** 에러 제목 — 실제 파일 크기가 들어간다. */
  errorTitle: string;
  /** 에러 제목에 맞는 해결 안내. 크기·형식·읽기 실패가 각자 다른 말을 해야 한다. */
  errorHint: string;
  /** 업로드 중 파일 표기 `이름 · 크기` — 기본 아바타면 「기본 아바타」. */
  fileLabel: string;
  /** 새로 고른 그림마다 올라간다 — 크롭 위치를 언제 초기화할지 가르는 값. */
  selectionSeq: number;
  /** 드롭존을 한 번 흔드는 중. 연속 실패·동작 줄이기에서는 켜지지 않는다. */
  shaking: boolean;
  /** 에러에서 선택 화면으로 되돌아오는 중 — 페이드 + scale. */
  restoring: boolean;
  /** 업로드 요청이 나가 있는 동안 — 적용이 잠기고 Esc·백드롭으로 닫히지 않는다. */
  uploading: boolean;
  /** 마지막 업로드가 실패한 사유 — 크롭 화면 아래 한 줄. 다시 적용하거나 새로 고르면 걷힌다. */
  uploadError: string | null;
  open: () => void;
  close: () => void;
  /** 파일을 받는다. 형식·크기를 여기서 판정한다. */
  selectFile: (file: File) => void;
  /** 이미 만들어진 그림으로 곧장 크롭에 든다 (기본 아바타). */
  selectImage: (dataUrl: string) => void;
  /** 진행 중인 업로드를 끊고 자르던 자리로 돌아간다. */
  cancelUpload: () => void;
  /**
   * 잘라낸 결과를 확정한다 — 여기서 서버로 올라간다. 저장 시점은 「적용」 1회로 고정(시안).
   * 화면이 자르지 못했으면(`cropToDataUrl`이 `null`) 그대로 `null`을 넘긴다 — 보낼 수 없다는
   * 사유를 이 훅이 같은 자리에 그린다.
   */
  apply: (dataUrl: string | null) => void;
}

export function useProfilePhotoState({
  upload,
  onCanceled,
}: ProfilePhotoUploadOptions): ProfilePhotoState {
  const { toast, dismiss } = useToast();
  const [step, setStep] = useState<PhotoStep>('idle');
  const [imageSrc, setImageSrc] = useState<string | null>(null);
  const [errorTitle, setErrorTitle] = useState('');
  const [errorHint, setErrorHint] = useState('');
  const [fileLabel, setFileLabel] = useState('');
  const [uploadError, setUploadError] = useState<string | null>(null);
  /**
   * 「새로 고른 그림」의 세대. 크롭 위치 초기화가 이것에 걸린다 — imageSrc 문자열에 걸면
   * 같은 기본 아바타·같은 파일을 다시 골랐을 때 값이 안 변해 직전 위치·회전이 남는다.
   * 토스트의 「편집」과 업로드 실패 복귀는 올리지 않는다: 방금 자른 자리를 그대로 잇는 동선이다.
   */
  const [selectionSeq, setSelectionSeq] = useState(0);
  const [shaking, setShaking] = useState(false);
  const [restoring, setRestoring] = useState(false);

  // 타이머는 ref에 모아 둔다 — 복귀 대기 중 재드롭·모달 닫기가 전부 이것들을 취소한다
  const restoreTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const fadeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const shakeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  /**
   * 에러 화면을 붙들고 있는 동안 겹쳐 난 실패 횟수 — 2회째부터는 흔들지 않고 문구만
   * 바꾼다(시안의 멀미 방지). 선택 화면으로 복귀하면 0으로 끊긴다: 「연속」은 쉼 없이
   * 이어진 실패를 말하는 것이지 「첫 실패 이후 전부」가 아니다.
   */
  const errorStreak = useRef(0);
  /**
   * 진행 중인 FileReader의 세대. 모달을 닫거나 다른 것을 고르면 올려서 뒤늦게 도착하는
   * onload를 버린다 — 안 그러면 닫은 모달이 혼자 다시 열리고, 먼저 시작한 읽기가 나중에
   * 끝나 새 선택을 덮어쓴다.
   */
  const readSeq = useRef(0);
  /**
   * 진행 중인 업로드 — 컨트롤러가 있으면 요청이 나가 있다. 세대(uploadSeq)는 readSeq와 같은
   * 이유다: 취소·닫기 뒤에 도착한 응답이 화면을 되돌리거나 토스트를 띄우지 못하게 한다.
   */
  const inflight = useRef<AbortController | null>(null);
  const uploadSeq = useRef(0);
  /** 방금 띄운 성공 토스트 — 화면이 사라지면 함께 걷는다. */
  const toastId = useRef<string | null>(null);
  // 옵션은 ref로 든다 — 호출부가 매 렌더 새 함수를 줘도 아래 콜백들의 정체가 흔들리지 않게.
  // resetPending의 정체가 바뀌면 언마운트 이펙트가 도중에 청소를 돌려 진행 중인 것을 끊는다.
  const uploadFn = useRef(upload);
  const onCanceledFn = useRef(onCanceled);
  uploadFn.current = upload;
  onCanceledFn.current = onCanceled;

  /**
   * 진행 중인 업로드를 끊는다. 서버는 이미 커밋했을 수 있으므로 호출부에 알려 me를 다시 읽게
   * 한다. 진행 중인 것이 없으면 아무 일도 없다.
   */
  const abortUpload = useCallback(() => {
    const controller = inflight.current;
    if (controller === null) return;
    uploadSeq.current += 1; // 늦게 오는 응답을 버린다
    inflight.current = null;
    controller.abort();
    onCanceledFn.current?.();
  }, []);

  /** 예약된 타이머·진행 중인 읽기·업로드를 한꺼번에 버린다 — 단계를 옮기는 모든 자리가 부른다. */
  const resetPending = useCallback(() => {
    for (const t of [restoreTimer, fadeTimer, shakeTimer]) {
      if (t.current !== null) clearTimeout(t.current);
      t.current = null;
    }
    readSeq.current += 1;
    abortUpload();
  }, [abortUpload]);

  useEffect(() => resetPending, [resetPending]);

  // 토스트는 전역 Provider에 살지만 「편집」은 이 훅의 setStep을 닫아 둔다 — 계정 화면이
  // 언마운트되면 눌러도 아무 일이 없는 죽은 버튼이 된다. 화면과 수명을 맞춘다.
  useEffect(
    () => () => {
      if (toastId.current !== null) dismiss(toastId.current);
    },
    [dismiss],
  );

  /** 새 그림으로 크롭에 든다 — 파일이든 기본 아바타든 여기를 지나며 선택 세대가 오른다. */
  const enterCrop = useCallback(
    (dataUrl: string, label: string) => {
      resetPending();
      errorStreak.current = 0;
      setShaking(false);
      setRestoring(false);
      setImageSrc(dataUrl);
      setFileLabel(label);
      setUploadError(null);
      setSelectionSeq((n) => n + 1);
      setStep('crop');
    },
    [resetPending],
  );

  const fail = useCallback(
    (title: string, hint: string) => {
      resetPending();
      errorStreak.current += 1;
      setErrorTitle(title);
      setErrorHint(hint);
      setStep('error');
      // 두 억제는 이유가 달라 범위도 다르다. 동작 줄이기는 흔들림·페이드를 모두 빼고,
      // 연속 실패는 멀미 방지로 흔들림만 뺀다 — 복귀 페이드는 그대로 둔다(시안 복귀 규칙).
      const reducedMotion = prefersReducedMotion();
      const skipShake = reducedMotion || errorStreak.current > 1;
      setShaking(!skipShake);
      if (!skipShake) {
        shakeTimer.current = setTimeout(() => setShaking(false), 340);
      }
      restoreTimer.current = setTimeout(() => {
        // 선택 화면으로 돌아온 순간 「연속」은 끊긴다. 여기서 안 지우면 카운터가 계속
        // 쌓여 첫 실패 뒤의 모든 실패가 조용해지고, 원인을 짚어 주던 흔들림이 사라진다.
        errorStreak.current = 0;
        setStep('empty');
        if (reducedMotion) return; // 동작 줄이기에서만 페이드를 뺀다
        setRestoring(true);
        fadeTimer.current = setTimeout(() => setRestoring(false), RESTORE_MS);
      }, ERROR_HOLD_MS);
    },
    [resetPending],
  );

  const selectFile = useCallback(
    (file: File) => {
      if (!ACCEPTED.includes(file.type)) {
        fail('JPG · PNG · WebP만 올릴 수 있어요', '다른 형식으로 저장한 뒤 다시 올려 주세요');
        return;
      }
      if (file.size > MAX_BYTES) {
        fail(
          `${megabytesCeil(file.size)} 파일은 올릴 수 없어요`,
          '5MB 이하로 줄여 다시 끌어다 놓거나 클릭해 선택해 주세요',
        );
        return;
      }
      // 이 읽기의 세대를 붙들어 둔다 — 도착했을 때 아직 유효한지 이것으로 판별한다
      const seq = (readSeq.current += 1);
      const reader = new FileReader();
      reader.onload = () => {
        if (seq !== readSeq.current) return; // 닫혔거나 다른 것을 고른 뒤 — 버린다
        // 읽기는 브라우저 안의 일이라 화면을 거치지 않는다 — 곧장 크롭이다
        enterCrop(String(reader.result), `${file.name} · ${megabytes(file.size)}`);
      };
      reader.onerror = () => {
        if (seq !== readSeq.current) return;
        fail('파일을 읽지 못했어요', '파일이 손상되지 않았는지 확인한 뒤 다시 시도해 주세요');
      };
      reader.readAsDataURL(file);
    },
    [fail, enterCrop],
  );

  const selectImage = useCallback(
    (dataUrl: string) => enterCrop(dataUrl, '기본 아바타'),
    [enterCrop],
  );

  const open = useCallback(() => {
    resetPending();
    errorStreak.current = 0;
    setShaking(false);
    setRestoring(false);
    setUploadError(null);
    setStep('empty');
  }, [resetPending]);

  const close = useCallback(() => {
    resetPending(); // 업로드 중이면 여기서 끊고 호출부에 알린다
    setShaking(false);
    setRestoring(false);
    setStep('idle');
  }, [resetPending]);

  const cancelUpload = useCallback(() => {
    if (inflight.current === null) return;
    abortUpload();
    setStep('crop'); // 자르던 자리를 지킨다 — 선택 세대는 올리지 않는다
  }, [abortUpload]);

  const apply = useCallback(
    (dataUrl: string | null) => {
      if (inflight.current !== null) return; // 업로드 중 재호출 — 두 번째 요청을 만들지 않는다
      const converted = dataUrl === null ? null : dataUrlToBlob(dataUrl);
      if (converted === null) {
        setUploadError(UNSENDABLE_MESSAGE);
        return;
      }
      const controller = new AbortController();
      const seq = (uploadSeq.current += 1);
      inflight.current = controller;
      setUploadError(null);
      setStep('uploading');
      uploadFn.current(converted.blob, converted.filename, controller.signal).then(
        () => {
          if (seq !== uploadSeq.current) return; // 취소·닫기 뒤 도착 — 화면은 이미 다른 곳이다
          inflight.current = null;
          setStep('idle');
          toastId.current = toast({
            tone: 'success',
            title: '프로필 사진을 변경했습니다',
            description: '헤더·사이드바에 바로 반영',
            // 되돌리기가 아니라 편집이다 — 바꾼 사진을 다시 잘라내러 크롭으로 돌아간다.
            // 서버에 되돌리는 문이 없기도 하다(구글 사진 주소는 올리는 순간 지워진다).
            // 닫혀 있을 때만 되돌아간다 — 토스트가 떠 있는 사이 새 사진을 고르는 중이었다면
            // 그 작업을 방금 적용한 사진의 크롭으로 덮어써 진행 중인 선택을 잃는다
            action: {
              label: '편집',
              onClick: () => setStep((prev) => (prev === 'idle' ? 'crop' : prev)),
            },
          });
        },
        (e: unknown) => {
          // 취소는 세대가 이미 올라가 여기까지 안 온다(AbortError 포함) — 취소가 크롭으로 되돌렸다
          if (seq !== uploadSeq.current) return;
          inflight.current = null;
          setUploadError(photoFailureMessage(e));
          setStep('crop'); // 자른 자리를 지켜 「적용」으로 바로 재시도하게 — 선택 세대는 올리지 않는다
        },
      );
    },
    [toast],
  );

  return {
    step,
    stepLabel: step === 'idle' ? '' : STEP_LABEL[step],
    imageSrc,
    errorTitle,
    errorHint,
    fileLabel,
    selectionSeq,
    shaking,
    restoring,
    uploading: step === 'uploading',
    uploadError,
    open,
    close,
    selectFile,
    selectImage,
    cancelUpload,
    apply,
  };
}
