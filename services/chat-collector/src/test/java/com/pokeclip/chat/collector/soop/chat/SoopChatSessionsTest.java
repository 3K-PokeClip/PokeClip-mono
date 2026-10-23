package com.pokeclip.chat.collector.soop.chat;

import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.relay.RelaySink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 붙이기 · 떼기 · 다시 붙기의 줄 세우기(POK-397 PR4, 봇 2판). 브라우저 없이 탭 명령만 적는다.
 */
class SoopChatSessionsTest {

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private SoopChatSessions sessions;

    @AfterEach
    void 끈다() {
        sessions.shutdown();
    }

    /**
     * 🔴 탭 열기 · 닫기는 세션 자물쇠 안에서 넣는다. 다시 붙기 예약이 「아직 그 세션인가」를 본 직후 떼기가 끼면 닫기 뒤에 열기가 들어가
     * 명부에 없는 방송의 탭이 남는다. 다시 붙기(예약 스레드)까지 같은 자물쇠인지 본다
     */
    @Test
    void 탭_명령은_떼기와_같은_자물쇠_안에서_넣는다() throws Exception {
        SoopChatProperties properties = new SoopChatProperties(true, "i", "s", "u", "o", 10, Duration.ofMillis(10),
                Duration.ofMillis(10));
        sessions = new SoopChatSessions(properties, new ChatBuffer(), ChatArchive.NONE, RelaySink.NONE,
                System::currentTimeMillis);
        sessions.attach(new SoopChatSessions.Tabs() {
            @Override
            public void open(String streamId, String token) {
                calls.add("open:" + Thread.holdsLock(sessions));
            }

            @Override
            public void close(String streamId) {
                calls.add("close:" + Thread.holdsLock(sessions));
            }
        });

        sessions.ensure("s-1", "bj-1", "t");
        sessions.onClosed("s-1");
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (calls.size() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        sessions.close("s-1");

        assertThat(calls).containsExactly("open:true", "open:true", "close:true");
    }

    /** 끈 뒤에 지켜보기 틱이 붙이기 · 닫힘 알림을 보내도 탭을 안 열고 던지지도 않는다(닫히는 브라우저 · 멈춘 예약기) */
    @Test
    void 끈_뒤에는_붙이기도_다시_붙기도_안_한다() {
        SoopChatProperties properties = new SoopChatProperties(true, "i", "s", "u", "o", 10, Duration.ofMillis(10),
                Duration.ofMillis(10));
        sessions = new SoopChatSessions(properties, new ChatBuffer(), ChatArchive.NONE, RelaySink.NONE,
                System::currentTimeMillis);
        sessions.attach(new SoopChatSessions.Tabs() {
            @Override
            public void open(String streamId, String token) {
                calls.add("open:" + streamId);
            }

            @Override
            public void close(String streamId) {
                calls.add("close:" + streamId);
            }
        });
        sessions.ensure("s-1", "bj-1", "t");

        sessions.shutdown();
        sessions.ensure("s-2", "bj-2", "t");
        sessions.onClosed("s-1");

        assertThat(calls).containsExactly("open:s-1");
        assertThat(sessions.size()).isZero();
    }
}
