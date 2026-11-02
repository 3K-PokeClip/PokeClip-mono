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
 * 🔴 <b>빈 파괴가 라이프사이클 정지를 앞지르는 순서가 있는가</b> (POK-219 감사 필수 항목).
 *
 * <p><b>답: 없다.</b> 「찾아봤는데 못 찾았다」가 아니라 실물 컨텍스트로 재서 확인했고,
 * 근거가 둘이다.
 *
 * <p>🔴 <b>이 검사가 답하지 <u>않는</u> 것</b>: 「폴링이 살아 있는 채 실행기가 닫히는 순서」는
 * <b>실재한다.</b> {@code SqsIntakeLoop.stop()}이 {@code JOIN_WAIT}(2초)를 넘겨도 폴링 스레드를
 * 죽이지 않고 경고만 남기기 때문이고, 롱폴링은 최대 20초다. 원래 이 javadoc이 「그런 순서는
 * 존재하지 않는다」라고 적어 <b>같은 파일 안에서 앞뒤가 어긋났다</b>(감사 재판정 ①).
 * 그 순서에서 <b>유실은 없다</b> — 갈래 둘의 근거는 {@code SqsIntakeLoop}의 {@code JOIN_WAIT} 절에 있다.
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
 *
 * <p><b>안 재는 것 하나</b>: 대역({@code OrderProbeConfig})을 쓰므로 <b>실물 빈 조립</b>에서
 * 그 순서가 서는지는 안 잰다. 실물에서 계약이 갈릴 이유를 못 찾았고, 배선 자체는
 * 「빈이 없으면 부팅이 죽는다」로 기존 {@code @SpringBootTest} 검사들이 지킨다.
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
