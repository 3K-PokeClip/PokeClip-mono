package com.pokeclip.clip.paging;

/**
 * 400. 이어받기 표시가 우리가 감싼 모양이 아니다.
 *
 * <p><b>{@code IllegalArgumentException}을 그대로 흘리지 않으려고 있다.</b> 표시를 푸는
 * {@code Base64}가 던지는 것이 바로 그 타입인데, 전역 조언은 그것을 <b>일부러 안 잡는다</b> —
 * 잡으면 내부 버그로 나온 예외가 「요청이 잘못됐다」로 둔갑하고 부르는 쪽이 재시도를 멈춘다
 * ({@code JumpCardErrors.InvalidHighlightException}과 같은 이유).
 *
 * <p>사유를 안 싣는다 — 어느 칸이 틀렸는지({@code cursor})는 조언이 알고, 그 안에서
 * 태그가 틀렸는지 칸 수가 틀렸는지는 <b>웹이 고칠 수 있는 정보가 아니다</b>(표시는 불투명하다).
 *
 * <p><b>그래서 메시지 셋은 응답에도 로그에도 안 나간다</b>(단언도 없다). 그것이 의도다 —
 * 남는 자리는 <b>스택 트레이스</b>뿐이고, 세 갈래 중 어느 것이 터졌는지를 사람이 붙어서
 * 볼 때만 쓴다. 이 값을 응답이나 로그로 내보내려면 <b>위 문단부터 다시 정해야 한다</b>.
 */
public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(String message) {
        super(message);
    }
}
