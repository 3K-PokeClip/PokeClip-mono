package com.pokeclip.clip.recipe;

/**
 * 레시피 예외 둘. 매핑은 {@code JumpCardExceptionHandler}(전역 조언) 하나가 한다 — 좁힌 조언을 새로
 * 만들면 전역이 이겨 죽은 코드가 된다({@code assignableTypes}는 우선권을 안 준다, 그 클래스 주석).
 */
public final class RecipeErrors {

    /**
     * 400. 본문이 계약6 모양이 아니다. {@code field}는 <b>어느 칸인지</b>만 말한다 — 값은 되돌려주지 않는다.
     * {@code IllegalArgumentException}을 통째로 400으로 잡지 않으려고 좁혀 던지는 타입이다
     * ({@code JumpCardErrors.InvalidHighlightException}과 같은 이유).
     */
    public static class InvalidRecipeException extends RuntimeException {
        private final String field;

        public InvalidRecipeException(String field) {
            super("레시피가 잘못됐다: " + field);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    /**
     * 404. 그 방송에 그 번호의 레시피가 없다. <b>다른 방송의 레시피 번호를 넣어도 이것이다</b> —
     * 「있는데 남의 것」을 따로 말하면 번호를 훑어 남의 방송에 레시피가 몇 개인지 셀 수 있다.
     */
    public static class RecipeNotFoundException extends RuntimeException {
        private final long id;

        public RecipeNotFoundException(long id) {
            super("없는 레시피다: " + id);
            this.id = id;
        }

        public long id() {
            return id;
        }
    }

    private RecipeErrors() {
    }
}
