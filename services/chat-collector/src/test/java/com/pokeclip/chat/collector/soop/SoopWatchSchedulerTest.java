package com.pokeclip.chat.collector.soop;

import com.pokeclip.chat.collector.relay.RelaySink;
import com.pokeclip.chat.collector.platforms.PlatformNotifier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지켜보기 전용 스레드(POK-456). 첫 주기 틱을 1시간 뒤로 미뤄 두고, 새 방송을 넣는 순간 그 스레드가 바로 연동을 묻는지 진짜 스레드로 잰다.
 */
class SoopWatchSchedulerTest {

    @Test
    void 넣는_순간_다음_주기를_안_기다리고_묻는다() throws Exception {
        List<String> threads = new CopyOnWriteArrayList<>();
        SoopBroadcastWatcher watcher = new SoopBroadcastWatcher(id -> {
            threads.add(Thread.currentThread().getName());
            return SoopLink.refused(true);
        }, token -> null, info -> { }, RelaySink.NONE, new PlatformNotifier() {
            @Override
            public void seen(String streamId, com.pokeclip.chat.collector.Platform platform, Instant seenAt) {
            }

            @Override
            public void retain(com.pokeclip.chat.collector.Platform platform, Set<String> streamIds) {
            }
        }, Set::of, 10, Clock.systemUTC());
        SoopWatchProperties properties = new SoopWatchProperties(true, false, "http://localhost:1", Duration.ofMinutes(1),
                Duration.ofSeconds(10), Duration.ofMinutes(3), Duration.ofHours(1), 10);

        try (SoopWatchConfiguration.Scheduler ignored = new SoopWatchConfiguration.Scheduler(watcher, properties)) {
            watcher.watch("s-1", 42L, Instant.now());

            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (threads.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
        }

        assertThat(threads).as("첫 주기 틱(1시간 뒤)을 기다리지 않고 지켜보기 스레드에서 물었다").first().isEqualTo("soop-watch");
    }
}
