package com.pokeclip.chat.collector.support;

import com.pokeclip.chat.collector.ChzzkProperties;
import com.pokeclip.chat.collector.CollectionStatus;
import com.pokeclip.chat.collector.CollectorHealth;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.broadcast.BroadcastEventProcessor;
import com.pokeclip.chat.collector.broadcast.intake.IntakeStatus;
import com.pokeclip.chat.collector.session.SessionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * <b>옛 경로만 켜진 프로세스의 health.</b> 등록부는 세션이 하나도 없고 편지 경로는 꺼져 있다.
 *
 * <p>세션 하나를 설정으로 붙이던 시절의 검사들이 {@code new CollectorHealth(status)}로
 * 쓰던 자리다. 이제 health가 등록부·큐·판정기까지 보므로 인자가 넷인데, <b>그 검사들이
 * 재는 것은 여전히 옛 경로 상태 하나</b>라 나머지 셋을 여기 한 곳에 모아 둔다 —
 * 검사마다 흩어 두면 「아무것도 안 붙어 있다」는 전제가 파일마다 조금씩 달라진다.
 */
public final class TestHealth {

    private TestHealth() {
    }

    public static CollectorHealth legacyOnly(CollectionStatus status) {
        return new CollectorHealth(status, emptyRegistry(), new IntakeStatus(false), noProcessor());
    }

    /**
     * 세션을 <b>하나도 안 여는</b> 등록부. 열지 않으므로 주소·토큰은 쓰이지 않는다.
     *
     * <p>{@code RestClient.create()}가 아니라 빌더다 — 운영에서 그것을 쓰면
     * {@code spring.http.clients.*}의 시한이 통째로 무시되고, 검사가 그 모양을 베껴 두면
     * 다음 사람이 복사한다.
     */
    private static SessionRegistry emptyRegistry() {
        return new SessionRegistry(
                new ChzzkProperties(false, "쓰이지-않는다", "http://localhost:1",
                        Duration.ofSeconds(5), Duration.ofMillis(200), Duration.ofSeconds(60)),
                RestClient.builder(), TestPersistence.unusedBuffer(),
                TestPersistence.disabledPersister(), ChatArchive.NONE);
    }

    /** 편지 경로가 꺼진 프로세스에는 판정기 빈이 아예 없다. */
    private static ObjectProvider<BroadcastEventProcessor> noProcessor() {
        return new ObjectProvider<>() {
            @Override
            public BroadcastEventProcessor getObject() {
                return null;
            }

            @Override
            public BroadcastEventProcessor getObject(Object... args) {
                return null;
            }
        };
    }
}
