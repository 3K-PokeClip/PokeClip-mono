package com.pokeclip.auth.streamkey.secret;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * 스트림키 암호 저장소를 설정으로 고른다(POK-272, 1번 설계 8-A 1)).
 *
 * <ul>
 *   <li>{@code @StreamKeySecrets SecretStore}: {@code type}이 postgres면 {@link PostgresSecretStore} <b>그 인스턴스</b>
 *       (트랜잭션 프록시를 그대로 쓴다), aws면 {@link SecretsManagerSecretStore}</li>
 *   <li>{@link StreamKeySecretRetirer}: {@code retire-from}에 든 저장소 전부</li>
 *   <li>{@link SecretsManagerSecretStore}: {@code type} 또는 {@code retire-from}에 aws가 있을 때만 만든다. 로컬·CI
 *       기본(postgres만)에서는 AWS 클라이언트가 0개다</li>
 * </ul>
 *
 * <p>표지 없는 {@code SecretStore} 주입(유튜브·치지직 토큰 넷)은 {@code @Primary}인 PG를 받는다.
 */
@Configuration
class StreamKeySecretStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(StreamKeySecretStoreConfig.class);

    /**
     * 이름으로 집는다. aws 상태에서는 {@code streamKeySecretStore}가 돌려준 것도 같은 인스턴스라 타입으로 찾으면
     * 후보가 둘이 되고 스프링이 못 고른다(컨텍스트 기동 실패로 확인).
     */
    private static final String SM_BEAN = "secretsManagerSecretStore";

    @Bean(name = "streamKeySecretStore")
    @StreamKeySecrets
    SecretStore streamKeySecretStore(StreamKeySecretStoreProperties p, PostgresSecretStore pg,
                                     @Qualifier(SM_BEAN) ObjectProvider<SecretsManagerSecretStore> sm) {
        // 어느 저장소로 떴는지 한 줄 남긴다. 이행 판정 ④에서 「기동 뒤 설정값 확인」이 이 줄이다. 접두는 비밀이 아니다
        log.info("auth.streamkey.secret_store type={} retireFrom={} refPrefix={}",
                p.type(), p.retireFrom(), p.refPrefix());
        return p.type() == StreamKeySecretStoreProperties.Type.AWS ? sm.getObject() : pg;
    }

    @Bean
    StreamKeySecretRetirer streamKeySecretRetirer(StreamKeySecretStoreProperties p, PostgresSecretStore pg,
                                                  @Qualifier(SM_BEAN) ObjectProvider<SecretsManagerSecretStore> sm) {
        List<SecretStore> stores = new ArrayList<>();
        if (p.retireFrom().contains(StreamKeySecretStoreProperties.Type.POSTGRES)) {
            stores.add(pg);
        }
        if (p.retireFrom().contains(StreamKeySecretStoreProperties.Type.AWS)) {
            stores.add(sm.getObject());
        }
        return new StreamKeySecretRetirer(stores, p.refPrefix());
    }

    @Bean(name = SM_BEAN)
    @Conditional(UsesAws.class)
    SecretsManagerSecretStore secretsManagerSecretStore(StreamKeySecretStoreProperties p) {
        return new SecretsManagerSecretStore(SecretsManagerClients.forRequests(p), p.refPrefix(), Clock.systemUTC());
    }

    /**
     * {@code @ConditionalOnProperty}로는 못 가른다. 「type이 aws <b>또는</b> retire-from 목록에 aws가 있음」이다.
     * 바인더로 설정 레코드를 그대로 읽어 같은 판정({@code usesAws})을 쓴다. 검사 실패는 여기서 던지지 않는다.
     * 레코드 빈을 만들 때 같은 메시지로 선다.
     */
    static class UsesAws implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            try {
                return Binder.get(context.getEnvironment())
                        .bind("pokeclip.stream-key-secret-store", StreamKeySecretStoreProperties.class)
                        .map(StreamKeySecretStoreProperties::usesAws)
                        .orElse(false);
            } catch (RuntimeException invalid) {
                return false;
            }
        }
    }
}
