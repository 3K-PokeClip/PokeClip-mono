'use client';

import { useEffect, useRef, useState, type DragEvent } from 'react';
import { AlertCircle, Upload, X } from 'lucide-react';
import { Button, Dialog, IconButton, Progress } from '@/ui';
import { cropToDataUrl, INITIAL_CROP, MASK_PX, type CropTransform } from './cropImage';
import { PhotoCropStage, type CropStatus } from './PhotoCropStage';
import { cssColor, PRESET_AVATARS, presetAvatarDataUrl } from './profilePresets';
import type { ProfilePhotoState } from './useProfilePhotoState';
import styles from './AccountSettingsScreen.module.css';

// 디자인 1p 「프로필 사진」 모달 — 선택 → 크롭 → 업로드 3단계 (순서가 시안과 다른 이유는
// useProfilePhotoState 머리말). 단계 판정과 요청은 useProfilePhotoState가 갖고, 여기서는 그리기와
// 캔버스만 맡는다.
//
// 업로드 중에도 크롭 화면을 그대로 둔다 — 로컬·CI는 사진 창고가 꺼져 있어(503) 「실패 → 다시
// 적용」이 가장 흔한 동선인데, 전용 업로드 화면으로 갈아끼우면 복귀마다 원본을 다시 디코드해야
// 하고 자르던 자리도 잃는다. 머리의 「3 / 3 · 업로드」·진행 표시·취소는 살아 있다.
// 포커스는 화면을 유지하는 것만으로는 지켜지지 않는다(적용 버튼이 loading→disabled가 되면
// 브라우저가 body로 떨어뜨린다) — 아래 이펙트가 실패 복귀에서 되돌린다.

export function ProfilePhotoDialog({ photo, glyph }: { photo: ProfilePhotoState; glyph: string }) {
  const fileInput = useRef<HTMLInputElement>(null);
  const imgRef = useRef<HTMLImageElement>(null);
  const applyRef = useRef<HTMLButtonElement>(null);
  const [transform, setTransform] = useState<CropTransform>(INITIAL_CROP);
  // 크롭 스테이지가 재서 알려 주는 마스크의 실제 지름. 미리보기와 내보내기가 같은 값을
  // 써야 보이는 대로 잘린다 — 시안 기준값(176)은 재기 전까지의 대비값일 뿐이다.
  const [maskPx, setMaskPx] = useState(MASK_PX);
  // 원본이 디코드되기 전에는 잘라낼 수 없다 — 그 상태로 「적용」하면 cropImage가 치수를 몰라
  // null을 주고, 사용자는 이유 없이 「올릴 수 없어요」만 본다. 디코드 자체가 안 되는 파일이면
  // 그 사실을 error로 따로 알린다.
  const [cropStatus, setCropStatus] = useState<CropStatus>('loading');
  // 드래그 중에는 :hover가 서지 않는다 — 파일을 끌어오는 내내 브라우저가 hover를
  // 주지 않아 CSS만으로는 「여기 놓으면 된다」를 보여 줄 수 없다. 직접 든다.
  const [dragOver, setDragOver] = useState(false);

  // 새로 고른 그림이면 잘라내기를 처음부터 — 앞 사진의 위치·확대가 남으면 엉뚱하게 잘린다.
  // imageSrc가 아니라 선택 세대에 거는 이유: 같은 기본 아바타·같은 파일을 다시 고르면
  // 문자열이 그대로라 초기화가 안 돈다. 토스트의 「편집」과 업로드 실패 복귀는 세대를 올리지
  // 않아 자르던 자리를 지킨다.
  useEffect(() => {
    setTransform(INITIAL_CROP);
  }, [photo.selectionSeq]);

  // 단계가 바뀌면 표시를 걷는다 — 드롭 직후 dragleave가 안 오는 경우가 있어
  // 그대로 두면 크롭으로 넘어간 뒤에도 강조가 남는다.
  useEffect(() => {
    setDragOver(false);
  }, [photo.step]);

  // 업로드가 끝나 크롭으로 돌아오면 「적용」에 포커스를 되돌린다. `loading`은 DS Button에서
  // disabled로 이어지고, 브라우저는 포커스된 요소가 disabled가 되면 포커스를 body로 떨어뜨린다 —
  // 그대로 두면 Enter로 적용한 키보드 사용자가 실패(로컬·CI 기본인 503) 뒤 재시도하려면
  // Tab으로 버튼을 다시 찾아야 한다. 실패가 흔한 경로라 그 왕복을 없앤다.
  const wasUploading = useRef(false);
  useEffect(() => {
    if (wasUploading.current && !photo.uploading && photo.step === 'crop') {
      applyRef.current?.focus();
    }
    wasUploading.current = photo.uploading;
  }, [photo.uploading, photo.step]);

  function pick(files: FileList | null) {
    const file = files?.[0];
    if (file) photo.selectFile(file);
  }

  function handleDrop(e: DragEvent<HTMLElement>) {
    e.preventDefault();
    setDragOver(false);
    pick(e.dataTransfer.files);
  }

  /** 드롭을 받는 면이 공유하는 드래그 표시 — 자식은 pointer-events:none이라 헛발이 없다. */
  const dropTargetProps = {
    onDragEnter: (e: DragEvent<HTMLElement>) => {
      e.preventDefault();
      setDragOver(true);
    },
    onDragOver: (e: DragEvent<HTMLElement>) => {
      e.preventDefault();
      setDragOver(true);
    },
    onDragLeave: () => setDragOver(false),
    onDrop: handleDrop,
    'data-dragover': dragOver || undefined,
  };

  function choosePreset(index: number) {
    const preset = PRESET_AVATARS[index];
    if (!preset) return;
    const dataUrl = presetAvatarDataUrl(preset, glyph);
    if (dataUrl !== null) photo.selectImage(dataUrl);
  }

  function applyCrop() {
    const img = imgRef.current;
    if (photo.uploading || img === null || photo.imageSrc === null || cropStatus !== 'ready')
      return;
    // 자르지 못했으면 null이 그대로 넘어가 「올릴 수 없어요」로 끊긴다 — 원본을 대신 보내지 않는다
    photo.apply(cropToDataUrl(img, transform, maskPx));
  }

  const presets = (
    <div className={styles.presetSwatches}>
      {PRESET_AVATARS.map((preset, i) => (
        <button
          key={preset.bg + preset.fg}
          type="button"
          className={styles.presetSwatch}
          style={{ background: cssColor(preset.bg), color: cssColor(preset.fg) }}
          aria-label={`기본 아바타 ${i + 1}`}
          onClick={() => choosePreset(i)}
        >
          {glyph}
        </button>
      ))}
    </div>
  );

  return (
    <Dialog
      open={photo.step !== 'idle'}
      onOpenChange={(next) => {
        // 요청이 나간 뒤에는 Esc·백드롭으로 닫히지 않는다 — 나가는 길은 「취소」 하나다
        // (InviteEditorDialog와 같은 규약). 닫기가 곧 취소인데 취소는 「안 올렸다」가 아니라서다.
        if (!next && !photo.uploading) photo.close();
      }}
    >
      <Dialog.Content className={styles.photoDialog}>
        <div className={styles.photoHead}>
          <Dialog.Title className={styles.photoTitle}>프로필 사진</Dialog.Title>
          <span className={styles.photoStep}>{photo.stepLabel}</span>
          <IconButton
            variant="ghost"
            size="sm"
            aria-label="닫기"
            className={styles.photoClose}
            disabled={photo.uploading}
            onClick={photo.close}
          >
            <X aria-hidden />
          </IconButton>
        </div>

        {photo.step === 'empty' && (
          <div className={styles.photoBody}>
            <button
              type="button"
              className={styles.dropzone}
              data-restoring={photo.restoring || undefined}
              onClick={() => fileInput.current?.click()}
              {...dropTargetProps}
            >
              <Upload aria-hidden className={styles.dropzoneIcon} />
              <span className={styles.dropzoneTitle}>사진을 끌어다 놓거나 클릭해 선택</span>
              <span className={styles.dropzoneHint}>
                JPG · PNG · WebP · 5MB 이하 · 정사각 512px 권장
              </span>
            </button>
            <div className={styles.presetRow}>
              <span className={styles.presetLabel}>사진 대신 기본 아바타</span>
              {presets}
            </div>
          </div>
        )}

        {photo.step === 'error' && (
          <div className={styles.photoBody}>
            {/* 모달 전체가 아니라 드롭존만 흔든다 — 원인이 어디인지 짚어 주려고 (1p) */}
            {/* 안내가 「클릭해 선택」이라고 말하므로 실제로 눌려야 한다. 바깥을 버튼으로 두어
                포인터·키보드 양쪽에서 열리게 하고, 낭독용 alert는 안쪽 span이 든다 —
                버튼에 role="alert"를 얹으면 버튼이라는 사실이 지워진다. */}
            <button
              type="button"
              className={styles.errorZone}
              data-shaking={photo.shaking || undefined}
              onClick={() => fileInput.current?.click()}
              {...dropTargetProps}
            >
              <span role="alert" className={styles.errorMessage}>
                <AlertCircle aria-hidden className={styles.errorIcon} />
                <span className={styles.errorTitle}>{photo.errorTitle}</span>
                <span className={styles.errorHint}>{photo.errorHint}</span>
              </span>
            </button>
            <div className={styles.presetRow}>
              <span className={styles.presetLabel}>잠시 후 선택 화면으로 돌아갑니다</span>
              {presets}
            </div>
          </div>
        )}

        {(photo.step === 'crop' || photo.step === 'uploading') && photo.imageSrc !== null && (
          <>
            <div className={styles.cropBody}>
              {/* 업로드 중에는 조작을 잠근다 — 서버로 가는 것은 「적용」을 누른 시점의 Blob이라,
                  그 사이 위치·확대를 바꾸면 미리보기와 저장 결과가 갈린다 */}
              <PhotoCropStage
                src={photo.imageSrc}
                transform={transform}
                onChange={setTransform}
                imgRef={imgRef}
                maskPx={maskPx}
                onMaskPxChange={setMaskPx}
                onStatusChange={setCropStatus}
                disabled={photo.uploading}
              />
              {cropStatus === 'error' && (
                <p role="alert" className={styles.cropError}>
                  이 파일은 사진으로 읽을 수 없어요. 다른 사진을 골라 주세요.
                </p>
              )}
              {/* 진행률은 없다(fetch는 업로드 진행 이벤트를 주지 않는다) — 부정형 막대와 파일 표기만 */}
              {photo.uploading && (
                <div className={styles.uploadRow}>
                  <span className={styles.uploadBar}>
                    <Progress size="sm" label="업로드 진행률" />
                  </span>
                  <span className={styles.uploadFile}>{photo.fileLabel}</span>
                </div>
              )}
            </div>
            <div className={styles.cropActions}>
              {/* 업로드 실패 사유는 버튼과 같은 줄 왼쪽에 — 재시도 버튼 바로 옆에서 읽힌다 */}
              {photo.uploadError !== null && (
                <p role="alert" className={styles.cropActionMessage}>
                  {photo.uploadError}
                </p>
              )}
              {/* 업로드 중의 취소는 요청을 끊고 자르던 자리로 돌아온다 — 모달을 닫지 않는다 */}
              <Button
                variant="outline"
                size="sm"
                onClick={photo.uploading ? photo.cancelUpload : photo.close}
              >
                취소
              </Button>
              <Button
                ref={applyRef}
                variant="solid"
                size="sm"
                disabled={cropStatus !== 'ready'}
                loading={photo.uploading}
                onClick={applyCrop}
              >
                적용
              </Button>
            </div>
          </>
        )}

        {/* 실제 파일 선택기. 드롭존 버튼이 대신 눌리므로 보조기술에는 감춘다 */}
        <input
          ref={fileInput}
          type="file"
          accept="image/jpeg,image/png,image/webp"
          className={styles.fileInput}
          tabIndex={-1}
          aria-hidden
          onChange={(e) => {
            pick(e.target.files);
            e.target.value = ''; // 같은 파일을 다시 골라도 change가 뜨게
          }}
        />
      </Dialog.Content>
    </Dialog>
  );
}
