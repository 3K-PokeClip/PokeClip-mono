package com.pokeclip.render.job;

/**
 * 렌더 계획 검사(PDT 설계 r7 5.3) 실패. 코드는 늘 {@code ENVELOPE_VALIDATION}이고, 어긴 규칙 번호(1–6)를 로그
 * {@code render.plan_rejected rule=}에 싣는다.
 */
final class PlanViolation extends RenderFailure {

    private final int rule;

    PlanViolation(int rule, String message) {
        super(ErrorCode.ENVELOPE_VALIDATION, message, false);
        this.rule = rule;
    }

    int rule() {
        return rule;
    }
}
