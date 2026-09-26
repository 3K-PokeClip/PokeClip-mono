package com.pokeclip.clip.upload;

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

    /** 404(내부 문). 모르는 업로드 번호. 바닥을 안 탄다: 서버 간 토큰이라 감출 존재가 없다. */
    public static class UploadNotFoundException extends RuntimeException {
        public UploadNotFoundException(long id) {
            super("없는 업로드다: " + id);
        }
    }

    private UploadErrors() {
    }
}
