package com.pokeclip.auth.streamkey.secret;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 새 스트림키 암호의 이름(ref)을 짓는 한 곳(POK-272). 이름 = {@code refPrefix + UUID}이고, 그대로
 * Secrets Manager 비밀 이름이 된다.
 *
 * <p><b>이름은 언제나 새 UUID다.</b> Secrets Manager의 강제 삭제는 비동기라 지운 이름으로 곧바로 다시 만들면
 * 실패할 수 있고, Media 조정자의 「한 번 목록에서 빠진 이름은 되살아나지 않는다」(봉인)가 이 규칙에 기댄다.
 * UUID 마지막 묶음이 12자라 ARN 꼬리(「하이픈 + 6자」)와도 헷갈리지 않는다.
 */
@Component
public class PassphraseRefIssuer {

    private final String refPrefix;

    PassphraseRefIssuer(StreamKeySecretStoreProperties properties) {
        this.refPrefix = properties.refPrefix();
    }

    public String issue() {
        return refPrefix + UUID.randomUUID();
    }
}
