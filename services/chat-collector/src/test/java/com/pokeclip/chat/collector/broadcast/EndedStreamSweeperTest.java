package com.pokeclip.chat.collector.broadcast;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 하루 지난 메모를 치우는 예약 실행을 잰다 — <b>계산</b>(둘)과 <b>배선</b>(둘)을 갈라서.
 *
 * <p><b>왜 스프링을 띄우는가.</b> 생성자를 직접 부르는 검사는 <b>스프링이 이 부품을 만들 수
 * 있는지를 안 잰다.</b> 계획 초안({@code @Component} + 생성자 {@code (store, Duration,
 * Supplier)})은 스프링에 {@code Duration} 빈도 {@code Supplier<Instant>} 빈도 없어 부팅이
 * 죽는데, 계산 검사 둘은 <b>둘 다 초록</b>이었다(계획 검증 C2 — 2026-08-19에 실물로 재현했다:
 * {@code APPLICATION FAILED TO START — Parameter 1 of constructor ... required a bean of type
 * 'java.time.Duration'}). 그 결함은 전체 실행을 하는 태스크 8에 가서야 드러난다.
 *
 * <p><b>다중 세션 문항(multi-session-test-reality)</b> — 이 부품에는 세션도 스레드도 없다.
 * 문항 1(세션 하나로 돌려도 통과하는가)·문항 3(의도한 동시성이 환경에 막히는가)은
 * <b>잴 대상이 없어</b> 해당하지 않는다. 재 보지 않은 것이 아니다.
 * 문항 2·4·5는 검사마다 주석으로 답을 남겼다.
 */
@SpringBootTest
@ActiveProfiles("test")
class EndedStreamSweeperTest extends IntegrationTestSupport {

    private final ApplicationContext context;

    EndedStreamSweeperTest(ApplicationContext context) {
        this.context = context;
    }

    /**
     * 문항 2: {@code verify}가 인자까지 요구하므로 {@code sweep()}이 저장소를 아예 안 불러도
     * 빨간불이다(주입 E: 몸통을 비우면 빨간불, 확인함).
     * <p>문항 5: {@code clock.get().minus(retention)}에서 {@code minus}를 빼면 빨간불(주입 F, 확인함).
     */
    @Test
    void 보관_기간이_지난_메모를_지운다() {
        EndedStreamStore store = mock(EndedStreamStore.class);
        when(store.sweepOlderThan(any())).thenReturn(3);
        Instant now = Instant.parse("2026-08-18T00:00:00Z");
        new EndedStreamSweeper(store, Duration.ofHours(24), () -> now).sweep();
        verify(store).sweepOlderThan(Instant.parse("2026-08-17T00:00:00Z"));
    }

    /**
     * 예약 실행은 한 번 던지면 다음 주기가 통째로 멈춘다({@code ChatArchiver.safeTick}과 같은 함정).
     *
     * <p>문항 2: {@code assertThatNoException}만 두면 <b>sweep()이 아무것도 안 해도 통과한다</b> —
     * 그래서 저장소를 실제로 불렀는지를 {@code verify}로 같이 요구한다(주입 E: 몸통을 비우면
     * 빨간불, 확인함 — 단언만 두었을 때는 초록이었다).
     * <p>문항 5: {@code try/catch}를 떼면 빨간불(주입 G, 확인함).
     */
    @Test
    void 지우다_실패해도_예외가_밖으로_나가지_않는다() {
        EndedStreamStore store = mock(EndedStreamStore.class);
        when(store.sweepOlderThan(any())).thenThrow(new DataAccessResourceFailureException("DB down"));
        assertThatNoException().isThrownBy(
                () -> new EndedStreamSweeper(store, Duration.ofHours(24), Instant::now).sweep());
        verify(store).sweepOlderThan(any());
    }

    /**
     * 스프링이 이 부품을 실제로 조립할 수 있는가. {@code Duration}은 빈으로 존재할 수 없는
     * 타입이라 설정에서 {@code @Value}로 받아 넘겨야 한다.
     *
     * <p>문항 4: 빈이 올라와도 <b>스케줄에 안 걸려 있으면</b> 메모는 영영 안 치워진다 —
     * 그 방향은 아래 {@code 예약_실행이_켜져_있다}가 맡는다. 한쪽만 지우지 마라.
     * <p>문항 5: {@code @Component} + 생성자 초안으로 되돌리면 <b>컨텍스트가 통째로 안 떠</b>
     * 이 클래스의 검사 넷이 다 빨간불이다(주입 D, 확인함).
     */
    @Test
    void 컨텍스트가_실제로_뜬다() {
        assertThatNoException().isThrownBy(() -> context.getBean(EndedStreamSweeper.class));
    }

    /**
     * {@code @EnableScheduling}이 없으면 {@code @Scheduled}는 애노테이션만 남고 아무것도 안 돈다.
     * 메모가 영영 안 치워지는데 신호가 없다(계획 검증 S5).
     *
     * <p><b>문항 4가 여기서 실제로 걸렸다.</b> 계획 초안의 단언
     * ({@code getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isNotEmpty()})은
     * 그 빈을 {@code @EnableScheduling}이 만들기 때문에 <b>{@code @Scheduled}를 통째로 떼도
     * 초록이었다</b>(대조 실측, 확인함) — 즉 「메모가 치워진다」를 아무것도 안 지킨다.
     * 그래서 등록된 태스크의 <b>내용</b>까지 본다. 간격 둘은 {@code application-test.yml}의
     * {@code pokeclip.broadcast.ended-sweep-interval}(PT24H)에서 온다.
     *
     * <p>첫 틱을 미루는 것은 검사 편의가 아니다 — {@code fixedDelay}의 기본 initialDelay는
     * 0이라 <b>컨텍스트가 뜨자마자 DELETE가 나간다.</b> 부팅과 겹치고, 검사 JVM에서는
     * 컨텍스트 수십 개가 각자 한 번씩 표를 치워 {@code EndedStreamStoreTest}가 과거로 밀어 넣은
     * 메모를 지운다(auth의 스케줄러도 같은 이유로 initialDelay를 준다).
     *
     * <p>문항 5: {@code @EnableScheduling} 제거(주입 A) · {@code @Scheduled} 제거(주입 B) ·
     * {@code initialDelayString} 제거(주입 C) <b>셋 다 빨간불</b>(확인함).
     */
    @Test
    void 예약_실행이_켜져_있다() {
        var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
        assertThat(tasks).hasSize(1);
        FixedDelayTask task = (FixedDelayTask) tasks.iterator().next().getTask();
        assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofHours(24));
        assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofHours(24));
    }
}
