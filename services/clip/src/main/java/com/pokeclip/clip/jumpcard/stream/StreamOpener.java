package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.broadcast.BroadcastStatus;
import com.pokeclip.clip.jumpcard.JumpCardErrors.BroadcastNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 자물쇠 <b>안</b>에서 도는 조회가 쓸 커넥션을 자물쇠 <b>밖</b>에서 미리 잡아 두는 자리.
 * 하는 일은 그것 하나뿐이고, 그래서 별도 빈이다.
 *
 * <p><b>왜 컨트롤러의 메서드가 아닌가.</b> POK-174부터 통로도 자격 판정({@code BroadcastAccessGuard})을
 * 지나는데, 그 판정은 auth에 HTTP로 묻는다(최대 7초 — {@code connect 2s + read 5s}).
 * 판정과 이 트랜잭션이 <b>한 메서드 안에</b> 있으면 왕복 내내 커넥션을 쥔다.
 * <b>순서를 바꾸거나 {@code readOnly}를 빼는 것으로는 안 풀린다</b> — 커넥션은 첫 질의가 아니라
 * <b>트랜잭션이 열릴 때</b> 잡힌다(POK-174 실측: 무트랜잭션 {@code active=0} ·
 * {@code @Transactional(readOnly=true)} <b>1</b> · {@code @Transactional} <b>1</b>).
 * 그래서 판정을 앞에 두고 <b>트랜잭션은 이 빈에서</b> 연다. 자기 호출은 프록시를 안 타므로
 * 컨트롤러 안의 private 메서드로는 이 분리가 성립하지 않는다.
 *
 * <p><b>{@code @Transactional}이 왜 필요한가.</b> 아래 {@code Supplier}의 조회는
 * {@link CardStreamRegistry#openWithSnapshot}의 자물쇠 <b>안</b>에서 돈다(그래야 「읽은 뒤 ~
 * 명부에 오르기 전」 창이 안 열린다). 트랜잭션이 없으면 그 조회가 자물쇠 안에서 커넥션을 새로
 * 얻어야 하고, 풀이 비어 있으면 <b>자물쇠를 쥔 채</b> {@code connection-timeout}(운영 기본 30초)만큼
 * 기다린다 — 그동안 {@code publish}·{@code open}·{@code broadcastEnded}가 전부 막힌다
 * (실측 {@code publish} <b>3142ms</b> · {@code open} <b>3116ms</b>, 풀 2·시한 3초).
 *
 * <p>🔴 <b>그것이 고갈을 스스로 만든다.</b> {@code afterCommit}은 커넥션 반납 <b>전</b>이라
 * ({@code activeConnections=1}·{@code resourceBound=true} 실측) 막힌 {@code publish}가 커넥션을
 * <b>쥔 채</b> 기다린다. 커넥션이 안 돌아오니 조회는 계속 굶는다.
 *
 * <p>🔴 <b>대가는 사라지지 않고 방향이 뒤집힌다.</b> 전에는 자물쇠를 쥔 채 커넥션을 기다렸고,
 * 이제는 커넥션을 쥔 채 자물쇠를 기다린다. 어느 쪽이 나은지를 재서 골랐다 — 자물쇠 보유가 조회
 * 한 번으로 끝나 기다림이 짧다: 같은 배치에서 발행 최악 막힘 <b>743~2022ms → 0~1ms</b>
 * ({@code OpenDoesNotBlockPublishTest}가 계속 잰다).
 *
 * <p><b>SSE가 오래 살아 있는 것과 무관하다.</b> 이 메서드는 {@code openWithSnapshot}이 돌아오면
 * 끝나고 트랜잭션도 거기서 닫힌다 — 전송은 {@code CardStreamExecutor}의 전용 스레드가 한다.
 */
@Component
class StreamOpener {

    private final BroadcastRepository broadcasts;
    private final CardStreamRegistry registry;

    StreamOpener(BroadcastRepository broadcasts, CardStreamRegistry registry) {
        this.broadcasts = broadcasts;
        this.registry = registry;
    }

    /**
     * @param timeout 자물쇠 <b>안에서</b> 다시 잰다 — 값으로 받으면 자물쇠 대기 동안 만료된
     *                토큰이 그대로 열린다({@code openWithSnapshot} 주석에 재현 기록이 있다)
     */
    @Transactional(readOnly = true)
    public SseEmitter open(String streamId, String userId, Supplier<Duration> timeout) {
        // 🔴 상태를 값으로 읽어 넘기지 않고 <b>읽는 법</b>을 넘긴다. 값으로 읽으면 자물쇠 밖이 되고,
        // 그 창에서 방송이 끝나면 ended=false로 열려 그 연결이 종료를 영영 못 받는다.
        //
        // 🔴 여기서 엔티티를 올리는 것이 안전한 이유는 <b>이 트랜잭션에서 방송을 처음 읽는 것이
        // 이 줄</b>이기 때문이다. 자격 판정은 트랜잭션 밖에서 끝났고 스칼라만 뽑는다
        // (BroadcastAccessGuard.requireViewable). 판정이 여기 안으로 들어와 엔티티를 올리면
        // 이 조회가 1차 캐시의 낡은 인스턴스를 받는다 — 2026-08-23 실측으로 창에서 끝난 방송이
        // LIVE로 보였다. StreamOpenWindowTest가 그 갈래를 잰다.
        return registry.openWithSnapshot(streamId, userId, timeout,
                () -> new CardStreamRegistry.InitialSnapshot(
                        broadcasts.findByStreamId(streamId)
                                .map(b -> b.getStatus() == BroadcastStatus.ENDED)
                                .orElseThrow(() -> new BroadcastNotFoundException(streamId))));
    }
}
