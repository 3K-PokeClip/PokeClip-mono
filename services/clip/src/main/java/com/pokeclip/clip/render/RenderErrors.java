package com.pokeclip.clip.render;

/** 주문·보고 문의 예외. 매핑은 {@code JumpCardExceptionHandler}(전역 조언) 하나가 한다. */
public final class RenderErrors {

    /** 503. 주문줄이 꺼져 있다(설정 없음). 404로 접지 않는다 — 설정을 채운 뒤 다시 누르게 안내가 달라야 한다. */
    public static class RenderUnavailableException extends RuntimeException {
        public RenderUnavailableException() {
            super("주문줄이 꺼져 있다");
        }
    }

    /** 400. 이 편집본은 주문할 수 없는 모양이다 — 구간이 없는 템플릿. {@code field}는 {@code cut}. */
    public static class RecipeNotRenderableException extends RuntimeException {
        private final String field;

        public RecipeNotRenderableException(String field) {
            super("주문할 수 없는 편집본: " + field);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    /**
     * 409. 그 구간의 조각이 아직 다 안 올라와 있다. 잘라서 주문하지 않는다 — 짧아진 영상이 조용히 나가는 것이
     * 안 나가는 것보다 나쁘다(계약1 SOURCE_RANGE의 「클램프 금지」와 같은 자세). 잠시 뒤 다시 누르면 된다.
     */
    public static class SourceNotReadyException extends RuntimeException {
        public SourceNotReadyException() {
            super("조각이 아직 준비되지 않았다");
        }
    }

    /** 422. 주문서가 큐 상한(200KB)을 넘는다 — 자막이 비정상적으로 많은 편집본이다. */
    public static class MessageTooLargeException extends RuntimeException {
        public MessageTooLargeException() {
            super("주문서가 너무 크다");
        }
    }

    /** 404. 그 방송에 그 번호의 영상이 없다(다른 방송의 번호여도 같다). */
    public static class ClipNotFoundException extends RuntimeException {
        public ClipNotFoundException(long id) {
            super("없는 영상이다: " + id);
        }
    }

    /** 404(내부 문). 모르는 jobId. 일꾼은 중단하고 메시지를 지운다(계약1). */
    public static class JobNotFoundException extends RuntimeException {
        public JobNotFoundException(String jobId) {
            super("없는 주문이다: " + jobId);
        }
    }

    /** 400(내부 문). 보고 본문이 계약 모양이 아니다. */
    public static class InvalidJobEventException extends RuntimeException {
        private final String field;

        public InvalidJobEventException(String field) {
            super("보고가 잘못됐다: " + field);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    private RenderErrors() {
    }
}
