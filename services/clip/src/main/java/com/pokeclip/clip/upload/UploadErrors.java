package com.pokeclip.clip.upload;

import java.time.Instant;

/** 업로드 문의 예외. 매핑은 전역 조언({@code JumpCardExceptionHandler}) 하나가 한다. */
public final class UploadErrors {

    /** 503. 주문줄이 꺼져 있다. */
    public static class UploadUnavailableException extends RuntimeException {
        public UploadUnavailableException() {
            super("업로드 주문줄이 꺼져 있다");
        }
    }

    /** 400. 본문이 규칙에 안 맞는다. {@code field}는 칸 이름이고 값은 안 싣는다. */
    public static class InvalidUploadRequestException extends RuntimeException {
        private final String field;

        public InvalidUploadRequestException(String field) {
            super("업로드 요청이 잘못됐다: " + field);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    /** 415. 썸네일 그림이 JPEG·PNG가 아니다(첫 바이트로 판정한다. 올린 쪽이 밝힌 형식은 안 믿는다). */
    public static class UnsupportedImageException extends RuntimeException {
        public UnsupportedImageException() {
            super("JPEG·PNG가 아닌 그림이다");
        }
    }

    /** 503. 사용자가 올린 썸네일 그림을 창고에 못 뒀다. 렌더를 주문하기 전이라 아무것도 안 남는다. */
    public static class ThumbnailStoreUnavailableException extends RuntimeException {
        public ThumbnailStoreUnavailableException(Throwable cause) {
            super("썸네일 그림을 창고에 못 뒀다", cause);
        }
    }

    /** 409. 방송 스트리머의 유튜브 채널이 연결돼 있지 않다(auth가 확실히 「아니다」라고 답했을 때만). */
    public static class YoutubeNotLinkedException extends RuntimeException {
        private final String reason;

        public YoutubeNotLinkedException(String reason) {
            super("유튜브 채널이 연결돼 있지 않다");
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** 409. 같은 편집본 같은 판이 이미 올라갔거나 올라가는 중이다. 같은 영상이 채널에 둘 뜨는 것을 막는다. */
    public static class AlreadyUploadedException extends RuntimeException {
        private final long uploadId;

        public AlreadyUploadedException(long uploadId) {
            super("이미 올렸거나 올리는 중인 판이다: " + uploadId);
            this.uploadId = uploadId;
        }

        public long uploadId() {
            return uploadId;
        }
    }

    /** 409. 다시 시도할 업로드가 없다(이 영상은 한 번도 안 올렸다). */
    public static class NothingToRetryException extends RuntimeException {
        public NothingToRetryException(long clipId) {
            super("다시 시도할 업로드가 없다: " + clipId);
        }
    }

    /**
     * 409. 「확인 필요」가 아닌 업로드를 풀려고 했다(POK-333). 이미 다른 결과로 풀렸거나 일꾼이 끝냈다. 지금 상태를 싣는다:
     * 화면이 목록을 다시 읽게.
     */
    public static class UploadNotCheckingException extends RuntimeException {
        private final String status;

        public UploadNotCheckingException(String status) {
            super("확인 필요가 아닌 업로드다: " + status);
            this.status = status;
        }

        public String status() {
            return status;
        }
    }

    /** 409. 일꾼이 아직 그 주소로 올리고 있을 수 있어 「안 올라갔다」를 아직 못 받는다(POK-333). 다시 누를 수 있게 되는 시각을 싣는다. */
    public static class ResolutionTooEarlyException extends RuntimeException {
        private final Instant allowedAt;

        public ResolutionTooEarlyException(Instant allowedAt) {
            super("아직 「안 올라갔다」를 받을 수 없다: " + allowedAt);
            this.allowedAt = allowedAt;
        }

        public Instant allowedAt() {
            return allowedAt;
        }
    }

    /** 404. 모르는 업로드 번호. 일꾼 문은 바닥을 안 탄다(서버 간 토큰이라 감출 존재가 없다). 사람 문(POK-333)은 컨트롤러가 바닥을 건다. */
    public static class UploadNotFoundException extends RuntimeException {
        public UploadNotFoundException(long id) {
            super("없는 업로드다: " + id);
        }
    }

    private UploadErrors() {
    }
}
