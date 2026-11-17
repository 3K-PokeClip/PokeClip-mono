package com.pokeclip.render.work;

import com.pokeclip.render.job.EnvelopeParser;
import com.pokeclip.render.job.JobEnvelope;
import com.pokeclip.render.media.MediaProbe;
import com.pokeclip.render.media.ProcessRunner;
import com.pokeclip.render.storage.S3Store;
import com.pokeclip.render.support.Fixtures;
import com.pokeclip.render.support.PlanOrder;
import com.pokeclip.render.support.RecordingRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 새 꼴 · 옛 꼴 주문이 서로의 경로로 새지 않는다(PDT 설계 r7 5.4 · 16.2 P9). 두 경로는 주문서 계획 칸 하나로 갈린다 — 새 꼴은
 * 패킷 판독(설정 바이트 해시를 함께 읽는 ffprobe)으로, 옛 꼴은 예전 판독으로 시작한다. ffmpeg 없이 첫 명령에서 멈춰 가린다.
 */
class ClipRendererRoutingTest {

    @TempDir
    Path dir;

    private final RecordingRunner runner = new RecordingRunner();
    private final S3Store store = mock(S3Store.class);

    @Test
    void 새_꼴_주문은_새_경로로_간다() {
        String body = PlanOrder.of(UUID.randomUUID()).segment(101, false, false).segment(102, false, true)
                .in(101, 0).out(102, 0).body();

        List<String> first = firstCommand(body);

        assertThat(first).contains("-show_data_hash");
        assertThat(downloadedNames()).containsExactly("seg_101.m4s", "seg_102.m4s");
    }

    @Test
    void 옛_꼴_주문은_옛_경로로_간다() {
        String body = Fixtures.MAPPER.writeValueAsString(Fixtures.envelope(UUID.randomUUID(), Fixtures.recipe("s1")));

        List<String> first = firstCommand(body);

        assertThat(first).doesNotContain("-show_data_hash").contains("-show_entries");
        assertThat(downloadedNames()).containsExactly("seg_000000.m4s", "seg_000001.m4s", "seg_000002.m4s");
    }

    private List<String> firstCommand(String body) {
        JobEnvelope job = new EnvelopeParser(Fixtures.MAPPER).parse(body);
        runner.intercept((command, workDir, deadline, r) -> {
            throw new ProcessRunner.Failed(command.getFirst(), 1, "멈춤");
        });
        ClipRenderer renderer = new ClipRenderer(store, new MediaProbe(runner, Fixtures.MAPPER, "ffprobe"), runner,
                Fixtures.MAPPER, "ffmpeg", null);

        assertThatThrownBy(() -> renderer.render(job, dir, Instant.now().plusSeconds(30), (p, s) -> { }))
                .isInstanceOf(ProcessRunner.Failed.class);
        assertThat(runner.commands()).hasSize(1);
        return runner.commands().getFirst();
    }

    private List<String> downloadedNames() {
        ArgumentCaptor<Path> targets = ArgumentCaptor.forClass(Path.class);
        verify(store, atLeastOnce()).download(anyString(), anyString(), targets.capture(), any());
        return targets.getAllValues().stream().map(p -> p.getFileName().toString()).toList();
    }
}
