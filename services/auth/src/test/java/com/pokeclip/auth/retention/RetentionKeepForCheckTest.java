package com.pokeclip.auth.retention;

import com.pokeclip.auth.token.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회수 행 보관({@code refresh-tokens-keep-for})이 갱신 토큰 수명({@code refresh-token-ttl})보다 짧으면 부팅에서 죽어야 한다.
 * 짧으면 「아직 살아 있어야 할 옛 토큰」의 행이 먼저 지워져 그 재사용이 봉쇄(REUSED) 대신 UNKNOWN으로 빠진다.
 * 두 값은 record가 서로 다르고({@code RetentionProperties}·{@code JwtProperties}) 한쪽만 고치기 쉬워 관계를 기계가 본다.
 * 컨텍스트 캐시를 안 쓴다(ContextRunner): 실패 갈래마다 컨텍스트를 하나씩 띄우면 여유 0인 캐시가 넘친다.
 */
class RetentionKeepForCheckTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Enable.class, RetentionKeepForCheck.class)
            .withPropertyValues(
                    "pokeclip.jwt.secret=test-only-secret-key-at-least-32-bytes-long!!",
                    "pokeclip.jwt.access-token-ttl=PT30M",
                    "pokeclip.jwt.refresh-token-ttl=P14D",
                    "pokeclip.retention.interval=PT10M",
                    "pokeclip.retention.batch-limit=1000",
                    "pokeclip.retention.refresh-tokens-keep-for=P14D",
                    "pokeclip.retention.pairing-attempts-keep-for=PT1H",
                    "pokeclip.retention.pairing-codes-keep-for=PT1H");

    @Test
    void 보관이_수명과_같으면_부팅한다() {
        runner.run(c -> assertThat(c).hasNotFailed());
    }

    /**
     * 값 둘은 시크릿이 아니라 메시지에 실어도 된다: 운영자가 어느 쪽을 고칠지 바로 보게.
     *
     * <p><b>일수도 같이 싣는다.</b> {@code Duration.toString()}만 쓰면 yml에 {@code P14D}라고 적힌 값이
     * {@code PT336H}로 찍혀 <b>파일에서 검색이 안 된다</b>(리뷰 라운드 2). {@code toDays()}는 내림이라
     * 하루 미만은 {@code 0일}로 보이는데, 그 값은 애초에 부팅이 거부되는 쪽이라 오히려 신호다.
     */
    @Test
    void 보관이_수명보다_짧으면_부팅이_거부되고_메시지에_두_값이_있다() {
        runner.withPropertyValues("pokeclip.retention.refresh-tokens-keep-for=P13D")
                .run(c -> {
                    assertThat(c).hasFailed();
                    assertThat(c.getStartupFailure()).rootCause()
                            .hasMessageContaining(Duration.ofDays(13).toString())
                            .hasMessageContaining(Duration.ofDays(14).toString())
                            .as("PT336H로만 찍히면 yml의 P14D를 찾을 수 없다")
                            .hasMessageContaining("13일")
                            .hasMessageContaining("14일");
                });
    }

    /**
     * 끄기 스위치가 이 검사도 끈다(스케줄러와 같은 조건 애노테이션). {@code enabled=false}면 회수 행을 지우는
     * 코드가 아예 안 돌아 「행이 먼저 지워진다」가 구조적으로 불가능한데, 검사만 남으면 README가 안내하는
     * 끄기를 따라도 부팅이 거부된다(리뷰 라운드 2 재현 R4).
     *
     * <p>반대 방향은 <b>기본 runner가 {@code enabled}를 안 주는 것</b>이 잰다 — 그래야 조건이 통째로 사라지는
     * 변경이 위 「짧으면 거부」 갈래에서 잡힌다. 주입으로 확인했다(라운드 2): {@code matchIfMissing}을 거짓으로
     * 바꾸면 그 갈래와 이 갈래가 <b>둘 다</b> 빨간불, {@code havingValue}를 {@code "false"}로 뒤집으면 이 갈래만.
     */
    @Test
    void 꺼져_있으면_보관이_수명보다_짧아도_부팅한다() {
        runner.withPropertyValues("pokeclip.retention.enabled=false",
                        "pokeclip.retention.refresh-tokens-keep-for=P13D")
                .run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(RetentionKeepForCheck.class));
    }

    @Test
    void 보관이_수명보다_길면_부팅한다() {
        runner.withPropertyValues("pokeclip.retention.refresh-tokens-keep-for=P15D")
                .run(c -> assertThat(c).hasNotFailed());
    }

    @Configuration
    @EnableConfigurationProperties({RetentionProperties.class, JwtProperties.class})
    static class Enable {
    }
}
