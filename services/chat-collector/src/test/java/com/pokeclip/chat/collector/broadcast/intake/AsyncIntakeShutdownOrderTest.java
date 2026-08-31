package com.pokeclip.chat.collector.broadcast.intake;

import com.pokeclip.chat.collector.broadcast.attach.StreamerSerialExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>폴링이 멈추기 전에 줄 실행기가 닫히는 순서가 있는가</b> (POK-219 감사 필수 항목).
 *
 * <p><b>답: 존재하지 않는다.</b> 「찾아봤는데 못 찾았다」가 아니라 실물 컨텍스트로 재서
 * 확인한 것이고, 근거가 둘이다.
 *
 * <ol>
 *   <li><b>실행기를 닫는 자리가 하나뿐이다.</b>
 *       {@code CollectorApplication.streamerSerialExecutor()}가 만든 {@code AutoCloseable}
 *       빈을 스프링이 파괴할 때다. 코드 어디에도 {@code close()}를 부르는 다른 자리가 없다
 *       ({@code grep -rn "\.close()" }로 확인 — 태스크 3 산출물에 출력을 붙였다)</li>
 *   <li><b>스프링은 라이프사이클 정지를 통째로 끝낸 뒤에 빈을 파괴한다.</b>
 *       {@code AbstractApplicationContext.doClose()}가
 *       {@code lifecycleProcessor.onClose()} → {@code destroyBeans()} 순서다.
 *       {@code SqsIntakeLoop}가 {@code SmartLifecycle}이므로 그 {@code stop()}(폴링 정지 +
 *       줄 비우기)이 <b>반드시 먼저</b> 끝난다</li>
 * </ol>
 *
 * <p><b>②를 이 버전의 스프링에서 실제로 잰다.</b> 코드 논증만으로 판정하지 않는다 —
 * 이 프로젝트에서 「원리상 그렇다」가 여러 번 틀렸다.
 */
class AsyncIntakeShutdownOrderTest {

    /**
     * 라이프사이클이 멈추는 시점에 실행기가 <b>아직 살아 있는가.</b>
     *
     * <p>{@code submit}이 true면 살아 있는 것이다 — 닫힌 실행기는
     * {@code RejectedExecutionException}을 잡아 false를 준다(태스크 1).
     *
     * <p>문항 2(자동으로 참): 「닫힌 뒤에는 false」를 짝으로 잰다. 없으면
     * {@code submit}이 <b>늘 true</b>인 구현도 통과한다.
     */
    @Test
    @Timeout(30)
    void 라이프사이클이_멈출_때_줄_실행기는_아직_열려_있다() {
        AtomicReference<Boolean> acceptedWhileStopping = new AtomicReference<>();
        StreamerSerialExecutor executor;
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.registerBean(AtomicReference.class, () -> acceptedWhileStopping);
            context.register(OrderProbeConfig.class);
            context.refresh();
            executor = context.getBean(StreamerSerialExecutor.class);

            assertThat(acceptedWhileStopping.get())
                    .as("아직 안 멈췄다 — 여기서 값이 있으면 이 검사가 다른 것을 재고 있다")
                    .isNull();
        }

        assertThat(acceptedWhileStopping.get())
                .as("라이프사이클 stop()이 돌 때 실행기가 이미 닫혀 있으면 false다")
                .isTrue();
        assertThat(executor.submit("after-close", () -> { }))
                .as("양성 대조 — 컨텍스트가 닫힌 뒤에는 실행기도 닫혀 있어야 한다")
                .isFalse();
    }

    @Configuration
    static class OrderProbeConfig {

        @Bean
        StreamerSerialExecutor streamerSerialExecutor() {
            return new StreamerSerialExecutor(10);
        }

        /**
         * {@code SqsIntakeLoop}의 대역이다. 같은 것을 쓰지 않는 이유는 그 클래스가
         * {@code SqsIntakeRunner}(→ SQS 클라이언트)를 물어야 하기 때문이고, 여기서 재는 것은
         * <b>스프링의 순서 계약</b>이지 러너의 동작이 아니다. 기본 phase도 같다.
         */
        @Bean
        @SuppressWarnings("unchecked")
        SmartLifecycle stopProbe(StreamerSerialExecutor executor,
                                 AtomicReference<Boolean> recorder) {
            return new SmartLifecycle() {
                private volatile boolean running;

                @Override
                public void start() {
                    running = true;
                }

                @Override
                public void stop() {
                    recorder.set(executor.submit("probe", () -> { }));
                    running = false;
                }

                @Override
                public boolean isRunning() {
                    return running;
                }
            };
        }
    }
}
