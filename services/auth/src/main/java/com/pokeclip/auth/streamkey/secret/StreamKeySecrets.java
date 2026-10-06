package com.pokeclip.auth.streamkey.secret;

import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 스트림키 암호를 읽고 쓰는 저장소를 고르는 표지(POK-272). 이것이 붙은 자리만 설정
 * {@code pokeclip.stream-key-secret-store.type}에 따라 PG 또는 Secrets Manager를 받는다.
 *
 * <p>표지가 없는 {@link SecretStore} 주입(유튜브·치지직 토큰 넷)은 언제나 {@link PostgresSecretStore}다.
 * 이번 이행 대상이 아니다.
 *
 * <p>lombok 생성자로 주입받는 필드에 달 때는 {@code services/auth/lombok.config}가 이 표지를 생성자 매개변수로
 * 복사해 준다. 그 줄이 빠지면 표지가 조용히 사라지고 PG가 꽂힌다.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
public @interface StreamKeySecrets {
}
