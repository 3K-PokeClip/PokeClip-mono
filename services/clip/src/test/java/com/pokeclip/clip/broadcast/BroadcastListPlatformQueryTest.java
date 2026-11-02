package com.pokeclip.clip.broadcast;

import com.pokeclip.clip.collector.BroadcastTitleClient;
import com.pokeclip.clip.delegation.DelegationResolveClient;
import com.pokeclip.clip.delegation.StreamerChannelNameClient;
import com.pokeclip.clip.segment.TimelineOriginReader;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.thumbnail.ThumbnailUrls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 방송 목록 한 장의 플랫폼 묶음(POK-397)을 <b>한 번에</b> 읽는지 잰다. 줄마다 묻는 구현(N+1)도 응답은 같아서 목록 창구 시험으로는
 * 안 갈린다. 그래서 묶음 저장소를 세는 것으로 갈아 끼워 서비스를 직접 만든다(빈 정의는 안 바꾼다: 새 스프링 컨텍스트 없음).
 * 묶음을 못 읽으면 목록은 나가고 편지 플랫폼 하나로 접는 것도 여기서 잰다.
 */
class BroadcastListPlatformQueryTest extends IntegrationTestSupport {

    private static final String ACCESSIBLE = "/internal/editor-delegations/accessible";
    private static final String 요청자 = "4174";

    private final JdbcTemplate jdbc;
    private final BroadcastRepository broadcasts;
    private final DelegationResolveClient delegation;
    private final TimelineOriginReader origins;
    private final ThumbnailUrls thumbnails;
    private final BroadcastTitleClient titles;
    private final ViewerPresence viewers;
    private final StreamerChannelNameClient channelNames;

    BroadcastListPlatformQueryTest(JdbcTemplate jdbc, BroadcastRepository broadcasts, DelegationResolveClient delegation,
                                   TimelineOriginReader origins, ThumbnailUrls thumbnails, BroadcastTitleClient titles,
                                   ViewerPresence viewers, StreamerChannelNameClient channelNames) {
        this.jdbc = jdbc;
        this.broadcasts = broadcasts;
        this.delegation = delegation;
        this.origins = origins;
        this.thumbnails = thumbnails;
        this.titles = titles;
        this.viewers = viewers;
        this.channelNames = channelNames;
    }

    @BeforeEach
    @AfterEach
    void 비운다() {
        방송과_카드를_비운다(jdbc);
        가짜_서버들을_초기화한다();
    }

    @Test
    void 한_장의_묶음을_한_번에_읽는다() {
        AUTH.respondWith(ACCESSIBLE, 200,
                "{\"streamers\":[{\"streamerUserId\":" + TestIds.STREAMER + ",\"relation\":\"OWNER\"}]}");
        for (String id : List.of("s-q1", "s-q2", "s-q3")) {
            jdbc.update("INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, last_sequence) "
                    + "VALUES (?, ?, 'live', ?, 1)", id, TestIds.STREAMER, Timestamp.from(Instant.now()));
            jdbc.update("INSERT INTO broadcast_platforms (stream_id, platform, first_seen_at, last_seen_at) "
                    + "VALUES (?, 'SOOP', now(), now())", id);
        }
        List<List<String>> calls = new ArrayList<>();
        BroadcastPlatformStore counting = new BroadcastPlatformStore(jdbc) {
            @Override
            public Map<String, List<String>> platformsOf(List<String> streamIds) {
                calls.add(List.copyOf(streamIds));
                return super.platformsOf(streamIds);
            }
        };

        BroadcastPage page = service(counting).list(요청자, BroadcastState.LIVE, null, null, null);

        assertThat(page.rows()).hasSize(3);
        assertThat(calls).as("줄마다 묻지 않는다").singleElement()
                .satisfies(ids -> assertThat(ids).containsExactlyInAnyOrder("s-q1", "s-q2", "s-q3"));
        // 수집기 값이 없고 묶음에 SOOP만 있으니 편지 플랫폼(CHZZK)은 안 붙는다(Media가 편지에 플랫폼을 안 싣는다)
        assertThat(page.platforms().get("s-q2")).extracting(PlatformViewers::platform).containsExactly("SOOP");
    }

    /** 묶음은 부가 칸이다. 못 읽으면(표가 아프다) 목록은 나가고 편지 플랫폼 하나로 접는다. 여기서 던지면 홈이 통째로 500이다. */
    @Test
    void 묶음을_못_읽으면_목록은_나가고_편지_플랫폼_하나다() {
        AUTH.respondWith(ACCESSIBLE, 200,
                "{\"streamers\":[{\"streamerUserId\":" + TestIds.STREAMER + ",\"relation\":\"OWNER\"}]}");
        jdbc.update("INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, last_sequence, platform) "
                + "VALUES ('s-broken', ?, 'live', ?, 1, 'SOOP')", TestIds.STREAMER, Timestamp.from(Instant.now()));
        // 아무 데도 못 붙는 데이터 소스: 묶음 조회만 실패한다. 시한(초)을 붙인다: 포트 1에 TCP만 받고 말이 없는 것이 떠 있으면 드라이버
        // 기본값(socketTimeout=0)으로 시험이 멈춘다(감사 1회차)
        BroadcastPlatformStore broken = new BroadcastPlatformStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:1/none?connectTimeout=1&socketTimeout=2")));

        BroadcastPage page = service(broken).list(요청자, BroadcastState.LIVE, null, null, null);

        assertThat(page.rows()).extracting(Broadcast::getStreamId).containsExactly("s-broken");
        assertThat(page.platforms().get("s-broken")).extracting(PlatformViewers::platform).containsExactly("SOOP");
    }

    private BroadcastListService service(BroadcastPlatformStore store) {
        return new BroadcastListService(broadcasts, delegation, origins, thumbnails, titles, viewers, store, channelNames);
    }
}
