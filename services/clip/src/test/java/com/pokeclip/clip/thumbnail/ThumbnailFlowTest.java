package com.pokeclip.clip.thumbnail;

import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.segment.TimelineOriginReader;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.thumbnail.api.ThumbnailReportController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 썸네일(POK-277)의 clip 절반: 어느 조각의 몇 ms를 찍으라고 주문하는가, 보고를 어떻게 적는가, 목록에 어느 사진을 싣는가.
 * 순회기는 손으로 만들어 한 번씩 돌린다(시험 설정은 꺼짐이라 일정이 안 돈다). 줄 대신 주문서를 모으는 가짜를 쓴다.
 */
class ThumbnailFlowTest extends IntegrationTestSupport {

    private static final Instant 시작 = Instant.parse("2026-10-04T10:00:00Z");
    private static final String 조각_창고 = "segments-test";
    private static final String 사진_창고 = "clips-test";

    private final JdbcTemplate jdbc;
    private final ThumbnailTargets targets;
    private final ThumbnailRepository thumbnails;
    private final BroadcastRepository broadcasts;
    private final TimelineOriginReader origins;
    private final ObjectMapper mapper;
    private final ThumbnailReportController controller;

    private final List<JsonNode> sent = new ArrayList<>();
    private Instant now = 시작.plus(Duration.ofHours(1));

    ThumbnailFlowTest(JdbcTemplate jdbc, ThumbnailTargets targets, ThumbnailRepository thumbnails,
                      BroadcastRepository broadcasts, TimelineOriginReader origins, ObjectMapper mapper,
                      ThumbnailReportController controller) {
        this.controller = controller;
        this.jdbc = jdbc;
        this.targets = targets;
        this.thumbnails = thumbnails;
        this.broadcasts = broadcasts;
        this.origins = origins;
        this.mapper = mapper;
    }

    @BeforeEach
    void 비운다() {
        방송과_카드를_비운다(jdbc);
        sent.clear();
    }

    private ThumbnailSweeper sweeper() {
        ThumbnailQueueClient queue = new ThumbnailQueueClient(null, "unused") {
            @Override
            public void send(String body) {
                sent.add(mapper.readTree(body));
            }
        };
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        return new ThumbnailSweeper(targets, thumbnails, queue, broadcasts, origins, mapper, 조각_창고, 사진_창고, clock);
    }

    // ── 라이브 ──────────────────────────────────────────────────────────────

    /**
     * 방송 중이면 <b>올라간 조각 중 가장 최근 것</b>의 가운데를 찍는다. 같은 물리 키를 쓴 앞 방송 조각(시작 2분보다 앞)과 아직 안 올라간
     * 조각은 안 쓴다. 조각은 방송 번호가 아니라 물리 키로 찾는다(POK-233).
     */
    @Test
    void 라이브는_올라간_최신_조각의_가운데를_찍는다() {
        방송("S-live", "key-a", "live", 시작, null);
        조각("key-a", 1, 0, 시작.minus(Duration.ofMinutes(10)), "uploaded", "seg/old");   // 앞 방송 꼬리
        조각("key-a", 2, 0, 시작, "uploaded", "seg/2");
        조각("key-a", 3, 4_000, 시작.plusSeconds(4), "uploaded", "seg/3");
        조각("key-a", 4, 8_000, 시작.plusSeconds(8), "pending", "seg/4");

        sweeper().sweepLive();

        assertThat(sent).hasSize(1);
        JsonNode job = sent.get(0);
        assertThat(job.path("kind").asString()).isEqualTo("live");
        assertThat(job.path("targetId").asString()).isEqualTo("S-live");
        assertThat(job.path("source").path("bucket").asString()).isEqualTo(조각_창고);
        assertThat(job.path("source").path("s3Key").asString()).isEqualTo("seg/3");
        assertThat(job.path("source").path("offsetMs").asLong()).isEqualTo(2_000);
        assertThat(job.path("output").path("bucket").asString()).isEqualTo(사진_창고);
        assertThat(job.path("output").path("s3Key").asString()).isEqualTo("thumbnails/live/S-live.jpg");
        assertThat(Instant.parse(job.path("capturedAt").asString())).isEqualTo(시작.plusSeconds(6));
    }

    /** 새 방송 조각이 아직 안 올라왔으면 같은 물리 키의 앞 방송 화면을 찍지 않는다(시작 2분 전보다 앞 조각은 안 본다). */
    @Test
    void 라이브는_앞_방송_조각을_찍지_않는다() {
        방송("S-live", "key-a", "live", 시작, null);
        조각("key-a", 1, 0, 시작.minus(Duration.ofMinutes(10)), "uploaded", "seg/old");

        sweeper().sweepLive();

        assertThat(sent).isEmpty();
    }

    /** 송출이 멈춰 새 조각이 없으면 같은 장면을 다시 주문하지 않는다. 새 조각이 오면 다시 낸다. */
    @Test
    void 라이브는_새_조각이_있을_때만_다시_찍는다() {
        방송("S-live", "S-live", "live", 시작, null);
        조각("S-live", 1, 0, 시작, "uploaded", "seg/1");
        sweeper().sweepLive();
        보고(sent.get(0));

        sent.clear();
        sweeper().sweepLive();
        assertThat(sent).isEmpty();

        조각("S-live", 2, 4_000, 시작.plusSeconds(4), "uploaded", "seg/2");
        sweeper().sweepLive();
        assertThat(sent).extracting(job -> job.path("source").path("s3Key").asString()).containsExactly("seg/2");
    }

    /** 늦게 도착한 옛 보고가 새 사진의 시각을 되돌리지 않는다(1분마다 주문이 나가고 일꾼이 늦을 수 있다). */
    @Test
    void 옛_장면_보고는_새_장면을_덮지_않는다() {
        assertThat(thumbnails.saveCaptured(ThumbnailKind.LIVE, "S-x", 시작.plusSeconds(60), now)).isTrue();
        assertThat(thumbnails.saveCaptured(ThumbnailKind.LIVE, "S-x", 시작, now)).isFalse();

        assertThat(thumbnails.liveCapturedAt(List.of("S-x"))).containsEntry("S-x", 시작.plusSeconds(60));
        assertThat(thumbnails.keysOf(ThumbnailKind.LIVE, List.of("S-x"))).containsEntry("S-x", "thumbnails/live/S-x.jpg");
    }

    // ── 카드 ────────────────────────────────────────────────────────────────

    /** 카드 시각은 조각의 pts와 같은 축이다. 그 시각이 든 조각에서 「카드 시각 − 조각 시작」 자리를 찍는다. */
    @Test
    void 카드는_그_시점이_든_조각에서_찍는다() {
        방송("S-c", "key-c", "ended", 시작, 시작.plus(Duration.ofMinutes(30)));
        조각("key-c", 1, 100_000, 시작, "uploaded", "seg/1");
        조각("key-c", 2, 104_000, 시작.plusSeconds(4), "uploaded", "seg/2");
        long card = 카드("S-c", 106_500, 80);

        sweeper().sweepCards();

        assertThat(sent).hasSize(1);
        JsonNode job = sent.get(0);
        assertThat(job.path("kind").asString()).isEqualTo("card");
        assertThat(job.path("targetId").asString()).isEqualTo(String.valueOf(card));
        assertThat(job.path("source").path("s3Key").asString()).isEqualTo("seg/2");
        assertThat(job.path("source").path("offsetMs").asLong()).isEqualTo(2_500);
        assertThat(job.path("output").path("s3Key").asString()).isEqualTo("thumbnails/card/" + card + ".jpg");
    }

    /** 조각이 아직 안 올라왔으면 주문하지 않고 횟수도 안 쓴다. 올라온 뒤 순회가 낸다. */
    @Test
    void 카드는_조각이_올라온_뒤에_찍는다() {
        방송("S-c", "S-c", "live", 시작, null);
        조각("S-c", 1, 0, 시작, "pending", "seg/1");
        카드("S-c", 1_000, 50);

        sweeper().sweepCards();
        assertThat(sent).isEmpty();

        jdbc.update("UPDATE stream_segments SET upload_state = 'uploaded'");
        sweeper().sweepCards();
        assertThat(sent).hasSize(1);
    }

    /** 사진이 안 오면 5분 뒤 다시 내고, 세 번에서 멈춘다(조각이 지워졌으면 영원히 실패한다). 사진이 오면 다시 안 본다. */
    @Test
    void 카드_주문은_간격을_두고_세_번까지만() {
        방송("S-c", "S-c", "ended", 시작, 시작.plus(Duration.ofMinutes(30)));
        조각("S-c", 1, 0, 시작, "uploaded", "seg/1");
        long card = 카드("S-c", 1_000, 50);

        sweeper().sweepCards();
        now = now.plus(Duration.ofMinutes(4));
        sweeper().sweepCards();              // 5분이 안 지났다: 다시 안 낸다
        assertThat(sent).hasSize(1);
        now = now.plus(Duration.ofMinutes(2));
        sweeper().sweepCards();
        assertThat(sent).hasSize(2);
        for (int i = 0; i < 3; i++) {
            now = now.plus(Duration.ofMinutes(6));
            sweeper().sweepCards();
        }
        assertThat(sent).hasSize(3);         // 세 번에서 멈춘다

        sent.clear();
        jdbc.update("DELETE FROM thumbnails");
        sweeper().sweepCards();
        보고(sent.get(0));
        sent.clear();
        now = now.plus(Duration.ofMinutes(6));
        sweeper().sweepCards();
        assertThat(sent).isEmpty();
        assertThat(thumbnails.keysOf(ThumbnailKind.CARD, List.of(String.valueOf(card)))).hasSize(1);
    }

    /** 줄이 안 되면 횟수를 안 쓴다. 쓰면 줄 장애 15분에 일꾼이 한 번도 못 받은 카드가 세 번을 다 써 영영 안 찍힌다(로컬 리뷰 1라운드). */
    @Test
    void 줄에_못_실으면_횟수를_쓰지_않는다() {
        방송("S-c", "S-c", "ended", 시작, 시작.plus(Duration.ofMinutes(30)));
        조각("S-c", 1, 0, 시작, "uploaded", "seg/1");
        카드("S-c", 1_000, 50);
        ThumbnailQueueClient down = new ThumbnailQueueClient(null, "unused") {
            @Override
            public void send(String body) {
                throw new IllegalStateException("줄 장애");
            }
        };
        ThumbnailSweeper broken = new ThumbnailSweeper(targets, thumbnails, down, broadcasts, origins, mapper,
                조각_창고, 사진_창고, Clock.fixed(now, ZoneOffset.UTC));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(broken::sweepCards).isInstanceOf(IllegalStateException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM thumbnails", Integer.class)).isZero();

        sweeper().sweepCards();
        assertThat(sent).hasSize(1);
    }

    // ── 완성 영상 ───────────────────────────────────────────────────────────

    /**
     * 영상 구간 안 <b>점수가 가장 높은 카드</b>의 장면. 카드 축(pts)을 시각 기준점으로 재생 축에 맞춰 「영상 처음부터 몇 ms」로
     * 바꾼다. 구간 밖 카드는 점수가 더 높아도 안 본다. 원본 조각이 아니라 완성 영상 파일에서 찍는다(보관함은 세로 영상이다).
     */
    @Test
    void 완성_영상은_구간_안_최고_점수_카드_장면을_찍는다() {
        방송("S-v", "S-v", "ended", 시작, 시작.plus(Duration.ofMinutes(30)));
        // 기준점 = 첫 조각 벽시계 − pts = 시작 − 100초
        조각("S-v", 1, 100_000, 시작, "uploaded", "seg/1");
        long cutIn = 시작.toEpochMilli() + 5_000;
        long cutOut = cutIn + 40_000;
        카드("S-v", 100_000 + 20_000, 70);   // 영상 15초 자리
        카드("S-v", 100_000 + 30_000, 90);   // 영상 25초 자리, 가장 높다
        카드("S-v", 100_000 + 60_000, 99);   // 구간 밖
        long clip = 완성_영상("S-v", cutIn, cutOut, "[{\"outputId\":\"o1\",\"kind\":\"video\",\"s3Key\":\"clips/9/t/o1.mp4\"}]");

        sweeper().sweepClips();

        assertThat(sent).hasSize(1);
        JsonNode job = sent.get(0);
        assertThat(job.path("kind").asString()).isEqualTo("clip");
        assertThat(job.path("targetId").asString()).isEqualTo(String.valueOf(clip));
        assertThat(job.path("source").path("bucket").asString()).isEqualTo(사진_창고);
        assertThat(job.path("source").path("s3Key").asString()).isEqualTo("clips/9/t/o1.mp4");
        assertThat(job.path("source").path("offsetMs").asLong()).isEqualTo(25_000);
    }

    @Test
    void 구간_안에_카드가_없으면_가운데를_찍는다() {
        방송("S-v", "S-v", "ended", 시작, 시작.plus(Duration.ofMinutes(30)));
        조각("S-v", 1, 0, 시작, "uploaded", "seg/1");
        long cutIn = 시작.toEpochMilli();
        완성_영상("S-v", cutIn, cutIn + 40_000, "[{\"outputId\":\"o1\",\"kind\":\"video\",\"s3Key\":\"clips/1/t/o1.mp4\"}]");

        sweeper().sweepClips();

        assertThat(sent).extracting(job -> job.path("source").path("offsetMs").asLong()).containsExactly(20_000L);
    }

    @Test
    void 영상_산출물이_없으면_주문하지_않고_다시_안_본다() {
        방송("S-v", "S-v", "ended", 시작, 시작.plus(Duration.ofMinutes(30)));
        long cutIn = 시작.toEpochMilli();
        완성_영상("S-v", cutIn, cutIn + 40_000, "[{\"outputId\":\"o1\",\"kind\":\"subtitle\",\"s3Key\":\"clips/1/t/o1.srt\"}]");

        sweeper().sweepClips();
        now = now.plus(Duration.ofHours(1));
        sweeper().sweepClips();

        assertThat(sent).isEmpty();
    }

    // ── 목록에 싣는 사진 ────────────────────────────────────────────────────

    /**
     * 끝난 방송은 최고 점수 카드 사진, 카드 사진이 없으면 마지막 라이브 사진. 방송 중이면 카드가 있어도 최신 라이브 사진이다.
     * 숨긴 카드는 대표가 아니다.
     */
    @Test
    void 방송_사진은_상태에_따라_고른다() {
        방송("S-end", "S-end", "ended", 시작, 시작.plus(Duration.ofMinutes(30)));
        long low = 카드("S-end", 1_000, 50);
        long high = 카드("S-end", 2_000, 90);
        long hidden = 카드("S-end", 3_000, 99);
        jdbc.update("UPDATE jump_cards SET hidden_at = now(), hidden_by = '1' WHERE id = ?", hidden);
        for (long id : List.of(low, high, hidden)) {
            thumbnails.saveCaptured(ThumbnailKind.CARD, String.valueOf(id), now, now);
        }
        thumbnails.saveCaptured(ThumbnailKind.LIVE, "S-end", now, now);

        방송("S-nocard", "S-nocard", "ended", 시작, 시작.plus(Duration.ofMinutes(30)));
        thumbnails.saveCaptured(ThumbnailKind.LIVE, "S-nocard", now, now);

        방송("S-on", "S-on", "live", 시작, null);
        long onCard = 카드("S-on", 1_000, 90);
        thumbnails.saveCaptured(ThumbnailKind.CARD, String.valueOf(onCard), now, now);
        thumbnails.saveCaptured(ThumbnailKind.LIVE, "S-on", now, now);

        Map<String, String> urls = urls(true).ofBroadcasts(List.of("S-on", "S-none"), List.of("S-end", "S-nocard"));

        assertThat(urls.get("S-end")).contains("thumbnails/card/" + high + ".jpg");
        assertThat(urls.get("S-nocard")).contains("thumbnails/live/S-nocard.jpg");
        assertThat(urls.get("S-on")).contains("thumbnails/live/S-on.jpg");
        assertThat(urls).doesNotContainKey("S-none");
        // 미리서명이다: 서명 없는 맨 주소가 나가면 비공개 창고라 화면이 403을 받는다
        assertThat(urls.get("S-end")).contains("X-Amz-Signature=");
    }

    @Test
    void 꺼져_있으면_사진_주소를_비운다() {
        thumbnails.saveCaptured(ThumbnailKind.LIVE, "S-on", now, now);
        thumbnails.saveCaptured(ThumbnailKind.CARD, "7", now, now);

        assertThat(urls(false).ofBroadcasts(List.of("S-on"), List.of())).isEmpty();
        assertThat(urls(false).ofCards(List.of(7L))).isEmpty();
        assertThat(urls(true).ofCards(List.of(7L))).containsKey(7L);
    }

    // ── 보고 문 ─────────────────────────────────────────────────────────────

    /** 키는 보고에서 안 받으므로 대상 번호의 모양만 본다. 카드·영상 번호는 숫자, 방송 번호는 계약9 글자 집합. */
    @Test
    void 보고_문은_모양이_틀리면_400이고_맞으면_적는다() {
        String at = now.toString();

        assertThat(controller.report(new ThumbnailReportController.ReportBody("poster", "1", at)).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.report(new ThumbnailReportController.ReportBody("card", "../x", at)).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.report(new ThumbnailReportController.ReportBody("live", "a/b", at)).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.report(new ThumbnailReportController.ReportBody("clip", "3", "어제")).getStatusCode().value()).isEqualTo(400);

        방송("S-report", "S-report", "ended", 시작, 시작.plusSeconds(60));
        String clipId = Long.toString(완성_영상("S-report", 0, 10_000, "[]"));
        assertThat(controller.report(new ThumbnailReportController.ReportBody("clip", clipId, at)).getStatusCode().value()).isEqualTo(200);
        assertThat(thumbnails.keysOf(ThumbnailKind.CLIP, List.of(clipId))).containsEntry(clipId, "thumbnails/clip/" + clipId + ".jpg");
    }

    /** 탈퇴로 지운 대상에 늦게 온 보고는 사진 줄을 되살리지 않는다(POK-256). 일꾼은 404를 받고 올린 사진을 지운다. */
    @Test
    void 대상이_없으면_404이고_사진_줄을_만들지_않는다() {
        String at = now.toString();
        방송("S-alive", "S-alive", "live", 시작, null);
        String cardId = Long.toString(카드("S-alive", 1_000, 5));

        assertThat(controller.report(new ThumbnailReportController.ReportBody("live", "S-gone", at)).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.report(new ThumbnailReportController.ReportBody("card", "999999", at)).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.report(new ThumbnailReportController.ReportBody("clip", "999999", at)).getStatusCode().value()).isEqualTo(404);
        // 보고 문은 19자리까지 받는다. bigint 밖이면 500이 아니라 「없음」이다.
        assertThat(controller.report(new ThumbnailReportController.ReportBody("card", "9999999999999999999", at)).getStatusCode().value()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM thumbnails", Integer.class)).isZero();
        // 정리기의 주문 기록도 없는 대상에는 줄을 안 만든다(codex 2판: 고른 직후 탈퇴 정리가 지운 대상).
        thumbnails.markRequested(ThumbnailKind.CARD, "999999", now);
        thumbnails.markGivenUp(ThumbnailKind.CLIP, "999999", 3, now);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM thumbnails", Integer.class)).isZero();

        // 있는 대상은 그대로 받는다: 대상 확인이 종류를 헷갈리면(카드 번호로 영상 표를 보면) 여기서 갈린다.
        assertThat(controller.report(new ThumbnailReportController.ReportBody("live", "S-alive", at)).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.report(new ThumbnailReportController.ReportBody("card", cardId, at)).getStatusCode().value()).isEqualTo(200);
    }

    /**
     * 확인과 적기 사이에 탈퇴 정리가 끼면 대상 없는 사진 줄이 남았다(PR #220 codex P2). 정리처럼 카드 줄을 FOR UPDATE로 잡고
     * 있는 동안 보고를 보내면 보고가 기다렸다가, 정리가 카드를 지우고 커밋한 뒤 「없음」을 봐야 한다.
     */
    @Test
    void 정리가_대상을_잡고_있으면_보고가_기다렸다가_없음을_본다() throws Exception {
        방송("S-race", "S-race", "ended", 시작, 시작.plusSeconds(60));
        String cardId = Long.toString(카드("S-race", 1_000, 5));
        javax.sql.DataSource ds = jdbc.getDataSource();
        try (java.sql.Connection purge = ds.getConnection()) {
            purge.setAutoCommit(false);
            try (var lock = purge.prepareStatement("SELECT id FROM jump_cards WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, Long.parseLong(cardId));
                lock.executeQuery();
            }
            java.util.concurrent.CompletableFuture<Integer> report = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> controller.report(new ThumbnailReportController.ReportBody("card", cardId, now.toString()))
                            .getStatusCode().value());
            Thread.sleep(300);
            assertThat(report).as("보고가 잠금을 안 기다렸다 — 확인과 적기가 갈라져 있다").isNotDone();
            try (var del = purge.prepareStatement("DELETE FROM jump_cards WHERE id = ?")) {
                del.setLong(1, Long.parseLong(cardId));
                del.executeUpdate();
            }
            purge.commit();
            assertThat(report.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(404);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM thumbnails", Integer.class)).isZero();
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private ThumbnailUrls urls(boolean enabled) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (enabled) {
            S3Presigner presigner = S3Presigner.builder()
                    .region(Region.AP_NORTHEAST_2)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .endpointOverride(URI.create("http://localhost:4566"))
                    .build();
            factory.registerSingleton("signer", new ThumbnailSigner(presigner, 사진_창고, Duration.ofMinutes(60)));
        }
        return new ThumbnailUrls(factory.getBeanProvider(ThumbnailSigner.class), thumbnails, jdbc);
    }

    /** 일꾼이 주문서를 받아 올렸다고 보고한 것처럼 적는다. */
    private void 보고(JsonNode job) {
        thumbnails.saveCaptured(ThumbnailKind.fromValue(job.path("kind").asString()).orElseThrow(),
                job.path("targetId").asString(), Instant.parse(job.path("capturedAt").asString()), now);
    }

    private void 방송(String streamId, String ingestKey, String status, Instant startedAt, Instant endedAt) {
        jdbc.update("""
                        INSERT INTO broadcasts (stream_id, ingest_stream_id, streamer_id, status, started_at, ended_at, last_sequence)
                        VALUES (?, ?, ?, ?, ?, ?, 1)""",
                streamId, ingestKey.equals(streamId) ? null : ingestKey, TestIds.STREAMER, status,
                Timestamp.from(startedAt), endedAt == null ? null : Timestamp.from(endedAt));
    }

    private void 조각(String key, long seq, long startPtsMs, Instant wall, String state, String s3Key) {
        jdbc.update("""
                        INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, upload_state)
                        VALUES (?, ?, ?, ?, 4000, ?, ?)""",
                key, seq, startPtsMs, Timestamp.from(wall), s3Key, state);
    }

    private long 카드(String streamId, long ts, int score) {
        return jdbc.queryForObject("""
                        INSERT INTO jump_cards (stream_id, source, event_id, stream_timestamp_ms, window_start_ms, window_end_ms, score, event_seq)
                        VALUES (?, 'auto', ?, ?, ?, ?, ?, 0)
                        RETURNING id""",
                Long.class, streamId, "evt-" + streamId + "-" + ts, ts, ts - 500, ts + 500, score);
    }

    private long 완성_영상(String streamId, long cutIn, long cutOut, String outputs) {
        long recipeId = jdbc.queryForObject("""
                        INSERT INTO recipes (stream_id, creator_id, schema_version, recipe_version, cut_in_at_ms, cut_out_at_ms,
                                             outputs, audio, updated_at)
                        VALUES (?, '4180', 1, 1, ?, ?, '[]'::jsonb, '{}'::jsonb, now())
                        RETURNING id""", Long.class, streamId, cutIn, cutOut);
        long clipId = jdbc.queryForObject("""
                        INSERT INTO clips (stream_id, recipe_id, recipe_version, requested_by, status, outputs, updated_at)
                        VALUES (?, ?, 1, '4180', 'rendered', CAST(? AS jsonb), now()) RETURNING id""",
                Long.class, streamId, recipeId, outputs);
        String payload = "{\"recipe\":{\"cut\":{\"inAtMs\":" + cutIn + ",\"outAtMs\":" + cutOut + "}}}";
        jdbc.update("""
                        INSERT INTO render_jobs (id, clip_id, status, payload, published_at, updated_at)
                        VALUES (gen_random_uuid(), ?, 'succeeded', CAST(? AS jsonb), now(), now())""", clipId, payload);
        return clipId;
    }
}
