package com.pokeclip.chat.collector.broadcast;

import com.pokeclip.chat.collector.StopReason;
import com.pokeclip.chat.collector.session.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * 포기한 방송을 끝난 방송 메모에 남긴다. 창구(POK-128)가 「중단」을 「없음」과 가르는 근거다.
 *
 * <p>등록부의 포기 알림은 <b>세션의 재연결 스레드</b>(또는 첫 수립 스레드)에서 온다. 여기서 던지면
 * 그 스레드의 뒷정리가 깨지므로 <b>Throwable까지 잡는다</b>.
 *
 * <p><b>부르는 곳이 둘이다.</b> ① 등록부의 포기 알림(세션이 선 뒤 영구 정지) ②
 * {@link LinkedSessionStarter}가 auth 거절로 세션을 <b>열어 보지도 못했을 때</b> 직접.
 * ②는 등록부를 안 지나므로 ①에 얹을 수 없다.
 *
 * <p><b>알려진 한계 — 메모를 못 남기면 그 방송은 영구 {@code unknown}이다.</b> 포기 순간 DB가 죽어
 * 있으면 경고 한 줄만 남고(재시도·메모리 대체 없음), 등록부에서 지워진 뒤로는 창구가 배너를 끄는
 * 값을 답한다 — 스트리머는 수집이 멈춘 것을 화면에서 못 본다. POK-128에서 고치지 않았다.
 */
public class StoppedStreamRecorder {

    private static final Logger log = LoggerFactory.getLogger(StoppedStreamRecorder.class);

    private final EndedStreamStore store;
    private final Supplier<Instant> clock;

    public StoppedStreamRecorder(SessionRegistry registry, EndedStreamStore store, Supplier<Instant> clock) {
        this.store = store;
        this.clock = clock;
        registry.onPermanentStop(this::record);
    }

    /**
     * <b>{@code public}인 이유</b>: 등록부 알림 말고 {@link LinkedSessionStarter}가 직접 부르는
     * 자리가 하나 더 있는데({@code LetterPathConfiguration}이 배선한다) 그 설정이
     * {@code broadcast.intake} 패키지라 package-private으로는 안 닿는다.
     */
    public void record(String streamId, StopReason reason) {
        if (streamId == null) {
            return;   // 옛 경로(설정 토큰)는 방송 번호가 없다. 창구 밖이다
        }
        try {
            store.rememberStopped(streamId, reason.name(), clock.get());
        } catch (Throwable t) {
            // 예외 메시지에 SQL·DB 주소가 들어 있다. 타입 이름만 남기고 throwable은 안 붙인다.
            log.warn("chat.broadcast.stopped_memo_failed stream={} reason={} causeType={}",
                    streamId, reason, t.getClass().getSimpleName());
        }
    }
}
