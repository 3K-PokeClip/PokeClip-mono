package com.pokeclip.auth.streamkey.secret;

/**
 * 비밀 저장소가 「있다/없다」를 답하지 못했다(스로틀·5xx·시간 초과·권한 거부). <b>「없음」과 다르다</b>.
 * 없음은 {@code Optional.empty()}로 돌아오고 이것은 판단 불가다. 둘 다 500으로 올라가지만
 * {@code request.failed}에 남는 예외 종류로 갈린다.
 *
 * <p>메시지에 이름·값을 싣지 않는다. 원인은 SDK 예외 타입 이름만 남긴다.
 */
public class SecretStoreUnavailableException extends RuntimeException {

    public SecretStoreUnavailableException(String operation, Throwable cause) {
        super("비밀 저장소가 답하지 못했다 operation=" + operation
                + " causeType=" + (cause == null ? "deadline" : cause.getClass().getSimpleName()));
    }
}
