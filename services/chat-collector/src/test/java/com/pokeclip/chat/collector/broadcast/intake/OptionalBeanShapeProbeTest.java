package com.pokeclip.chat.collector.broadcast.intake;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>{@code Optional}과 스프링 주입의 진짜 경계를 실물 컨텍스트로 재 둔다.</b>
 *
 * <p>{@code LetterPathConfiguration}·{@code IntakeConfiguration}이 「{@code Optional}을 쓰지
 * 마라」는 🔴 경고를 달고 있는데, <b>그 경고의 이유가 한동안 틀리게 적혀 있었다</b> —
 * "받는 쪽 파라미터가 {@code Optional}이면 빈이 있어도 늘 빈손"이라고 되어 있었다.
 * 재 보니 아니다. 위험한 것은 <b>빈의 타입</b>이다.
 *
 * <p>이 구분이 중요한 이유: 틀린 설명을 믿으면 <b>멀쩡한 {@code Optional} 파라미터를 쫓아다니며
 * 고치고, 정작 위험한 {@code @Bean Optional<T>}는 그대로 둔다.</b> 그 모양은 켜도 폴링이 영영
 * 시작되지 않는데(우리 쪽은 {@code SqsIntakeRunner} 생성자가 null을 거부해 부팅이 죽는다),
 * 그 방어가 없는 곳이라면 조용히 아무 일도 안 한다.
 *
 * <p>AWS 타입을 안 쓴다 — 재는 것은 스프링의 주입 규칙이지 SQS가 아니다.
 */
class OptionalBeanShapeProbeTest {

    /** 실어 나르는 값. 무엇이든 상관없다. */
    record Payload(String value) { }

    static class Consumer {

        final Optional<Payload> seen;

        Consumer(Optional<Payload> seen) {
            this.seen = seen;
        }
    }

    @Configuration
    static class BeanReturnsOptional {

        @Bean
        Optional<Payload> payload() {
            return Optional.of(new Payload("있다"));
        }

        @Bean
        Consumer consumer(Optional<Payload> payload) {
            return new Consumer(payload);
        }
    }

    @Configuration
    static class BeanReturnsPayload {

        @Bean
        Payload payload() {
            return new Payload("있다");
        }

        @Bean
        Consumer consumer(Optional<Payload> payload) {
            return new Consumer(payload);
        }
    }

    /**
     * <b>이것이 진짜 함정이다.</b> {@code Optional<Payload>} 주입 지점은 「{@code Payload} 타입
     * 빈을 optional로 찾기」로 해석된다. 그런데 컨테이너에 있는 것은 {@code Optional<Payload>}
     * 타입 빈뿐이라 <b>아무도 그것을 못 만난다</b> — 부팅은 성공하고, 값만 없다.
     */
    @Test
    void 빈이_Optional을_돌려주면_받는_쪽은_영영_빈손이다() {
        new ApplicationContextRunner()
                .withUserConfiguration(BeanReturnsOptional.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(Consumer.class).seen)
                            .as("부팅이 성공하는데 값만 없다 — 그래서 조용하다")
                            .isEmpty();
                });
    }

    /** 양성 대조. <b>{@code Optional} 파라미터 자체는 죄가 없다.</b> */
    @Test
    void 빈이_실물을_돌려주면_Optional_파라미터도_그것을_받는다() {
        new ApplicationContextRunner()
                .withUserConfiguration(BeanReturnsPayload.class)
                .run(context -> assertThat(context.getBean(Consumer.class).seen)
                        .as("이 대조가 없으면 위 갈래를 「Optional 파라미터가 문제」로 오독한다")
                        .contains(new Payload("있다")));
    }
}
