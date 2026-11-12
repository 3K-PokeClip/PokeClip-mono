package com.pokeclip.clip.segment;

import com.pokeclip.clip.segment.RoundRows.Row;
import com.pokeclip.clip.segment.ServerClockPosition.Position;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버 시계 마크의 섞인 floor 를 공유 시험 표({@code services/ledger-timeline/src/test/resources/ledger-timeline-cases.json})
 * 의 F 사례 {@code axis = wall · pdt} 로 잰다(설계 r7 8.2 · 계획 7.1). 채팅 수집기 · 웹과 같은 사례다.
 *
 * <p>판정 꼴: 사례의 F 기대 결과가 정하는 floor 행 k 와 저장 위치가 맞는다 — 조각 안이면 {@code (k, start_pts(k) + 거리)}, 이어진
 * 틈 · 끊긴 틈이면 k 끝으로 접고, 첫 행 앞이면 첫 행 시작, 마지막 행 뒤면 라이브 머리로 늘린다(설계 r7 8.2). 행의 위치
 * ({@code start_pts})는 서버 시계 축과 <b>행마다 다른 값</b>으로 둔다 — 같게 두면 어느 행을 골라도 숫자가 같아(위치 = T) floor
 * 고르기가 틀려도 초록이다.
 *
 * <p>표를 읽는 쪽은 fail-closed 다 — 판이 다르거나 모르는 축이거나 쓸 사례가 없으면 실패한다.
 */
class ServerClockSharedCasesTest {

    /** 표 뿌리 판. {@code LedgerTimelineCases.VERSION} 과 같아야 한다 — 표가 바뀌면 여기서 먼저 멈춘다. */
    private static final int VERSION = 2;

    private static final Path TABLE = Path.of("../ledger-timeline/src/test/resources/ledger-timeline-cases.json");

    /** 행 위치 = 축 시작 + seq × 이 값. 사례의 시각보다 훨씬 커 seq 순서와 위치 순서가 같다. */
    private static final long PTS_SHIFT = 1_000_000;

    @Test
    void 공유_표_F의_벽시각_PDT_사례에서_마크가_F의_floor_행에_선다() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(TABLE));
        assertThat(root.path("version").asInt(-1)).as("공유 표 판").isEqualTo(VERSION);

        int wall = 0;
        int pdt = 0;
        for (JsonNode c : root.path("cases")) {
            if (!"F".equals(c.path("kind").asString("")) || !"locate".equals(c.path("op").asString(""))) {
                continue;
            }
            String axis = c.path("axis").asString("");
            switch (axis) {
                case "wall" -> {
                    if (!allVersion(c, null)) {
                        continue; // 서버 시계에서 판 2 행은 PDT 축이라 판이 섞인 벽시각 사례는 이 시험의 사례가 아니다
                    }
                    wall++;
                }
                case "pdt" -> {
                    assertThat(allVersion(c, 2)).as(c.path("id").asString() + " — pdt 사례에 판 NULL 행").isTrue();
                    pdt++;
                }
                case "startPts" -> {
                    continue;
                }
                default -> throw new IllegalStateException("모르는 axis: " + axis);
            }
            check(c, "pdt".equals(axis));
        }
        assertThat(wall).as("벽시각 사례가 하나도 안 돌았다").isPositive();
        assertThat(pdt).as("PDT 사례가 하나도 안 돌았다").isPositive();
    }

    private static void check(JsonNode c, boolean v2) {
        List<Row> rows = new ArrayList<>();
        for (JsonNode r : c.path("rows")) {
            long seq = r.path("seq").asLong();
            long start = r.path("startAtMs").asLong();
            rows.add(new Row(seq, start + PTS_SHIFT * seq, r.path("durationMs").asInt(), start, start, v2 ? 2 : null,
                    "uploaded", null));
        }
        long t = c.path("t").asLong();

        Position position = ServerClockPosition.fromServerClock(rows, t).orElseThrow();

        assertThat(position).as(c.path("id").asString() + " t=" + t)
                .isEqualTo(expected(c.path("expect"), rows, t, v2 ? "pdt" : "wall"));
    }

    /** 사례의 기대 F 결과 → 마크가 설 자리. 축 시작은 위치에서 seq 몫을 뺀 값이다. */
    private static Position expected(JsonNode expect, List<Row> rows, long t, String axis) {
        Row first = rows.get(0);
        Row last = rows.get(rows.size() - 1);
        return switch (expect.path("place").asString("")) {
            case "media" -> {
                Row s = rowOf(rows, expect.path("seq").asLong());
                long start = s.startPtsMs() - PTS_SHIFT * s.seq();
                if (t >= start) {
                    yield new Position(s.startPtsMs() + expect.path("offsetMs").asLong(), s.seq(), axis, false, false);
                }
                // 이어진 틈(F 3항 — 뒤 행 시작으로 낸다): floor 는 뒤 행 바로 앞 행이고 그 끝으로 접는다
                Row k = rows.get(rows.indexOf(s) - 1);
                yield new Position(k.startPtsMs() + k.durationMs(), k.seq(), axis, false, true);
            }
            case "gap" -> {
                Row k = rowOf(rows, expect.path("beforeSeq").asLong());
                yield new Position(k.startPtsMs() + k.durationMs(), k.seq(), axis, false, true);
            }
            case "beforeFirst" -> new Position(first.startPtsMs(), first.seq(), axis, false, true);
            case "afterLast" -> new Position(last.startPtsMs() + (t - last.wallMs()), last.seq(), axis, true, false);
            default -> throw new IllegalStateException("모르는 place: " + expect.path("place"));
        };
    }

    private static Row rowOf(List<Row> rows, long seq) {
        return rows.stream().filter(r -> r.seq() == seq).findFirst().orElseThrow();
    }

    private static boolean allVersion(JsonNode c, Integer version) {
        for (JsonNode r : c.path("rows")) {
            JsonNode v = r.path("timingVersion");
            Integer actual = v.isNull() || v.isMissingNode() ? null : v.asInt();
            if (!Objects.equals(actual, version)) {
                return false;
            }
        }
        return true;
    }
}
