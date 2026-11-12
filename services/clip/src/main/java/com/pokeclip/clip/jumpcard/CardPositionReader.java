package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.segment.OffsetOrigin;
import com.pokeclip.clip.segment.RoundRows;
import com.pokeclip.clip.segment.RoundRows.Round;
import com.pokeclip.clip.segment.RoundRows.Row;
import com.pokeclip.ledger.timeline.LedgerRow;
import com.pokeclip.ledger.timeline.LedgerTimeline;
import com.pokeclip.ledger.timeline.Place;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 카드 위치(카드 ms — {@code start_pts} 재생 위치 축)를 회차 귀속 행 위 <b>장면 하나</b>로 바꾸는 한 곳(POK-196 · 설계 r7
 * 8.2 「카드 장면 함수」 · kty 2026-11-03 11:27). 카드 썸네일 주문 값 · 편집기용 {@code fileHead}(파일명 시각 축) · 플레이어용
 * {@code playerAtMs}(목록 PDT 축)가 모두 이 장면에서 나온다 — 따로 SQL · 따로 판정을 두면 같은 카드의 썸네일과 이동 장면이
 * 갈린다.
 *
 * <p><b>장면 규칙</b>(공통 모듈 F {@link LedgerTimeline#locate} 한 번 — 시작 ≤ v 인 행 가운데 seq 최대 · 겹침은 나중 조각):
 * <ul>
 *   <li>조각 안 {@code (k, x)} → 그 자리</li>
 *   <li>틈 안 — 이어진 틈(장부 틈)이든 끊긴 틈(재접속)이든 비귀속 행 자리(채팅 카드 · 접힌 마크)든 → 뒤 귀속 행 첫 화면
 *       {@code (k′, 0)}. 두 갈래의 장면이 같아 이어짐 · 끊김을 가르지 않는다</li>
 *   <li>첫 귀속 행 앞 → {@code (첫 귀속 행, 0)}(옛 카드에만 생긴다 — 새 마크는 저장 때 접힌다)</li>
 *   <li>마지막 귀속 행 뒤(라이브 머리 — 다음 행이 아직 없음) → 장면 없음. 셋 모두 없고 웹은 지금 식으로 떨어진다</li>
 * </ul>
 *
 * <p><b>왕복 두 번</b>: 회차 고르기 한 번 + 회차 행 한 번이고 값이 몇 개든 같다(목록 한 장 · 카드 한 장 · 여러 방송에 걸친
 * 썸네일 순회 한 묶음 모두). 회차 행을 한 번에 읽어 값마다 메모리에서 F 를 돈다 — 장부에 {@code start_pts_ms} 색인이 없어
 * 값마다 {@code LATERAL} 로 floor 를 찾으면 값마다 회차 행을 훑는다.
 */
@Component
public class CardPositionReader {

    private static final Logger log = LoggerFactory.getLogger(CardPositionReader.class);

    /** 썸네일 일꾼에 낼 카드 장면 — 조각 {@code seq} 안 {@code offsetMs}, 원점은 행 판. */
    public record ThumbnailOrder(long seq, long offsetMs, OffsetOrigin offsetOrigin) {
    }

    /** 카드 값 하나가 닿은 장면 — 회차 행 {@code row} 안 {@code offsetMs}. */
    public record Scene(Row row, long offsetMs) {

        public long seq() {
            return row.seq();
        }

        /** 편집기 {@code /get} 축(파일명 시각) 절대 시각. 판 2 행은 머리 앞섬 m 만큼 이르다(경계 표 B6 한계). */
        public long fileHeadMs() {
            return row.wallMs() + offsetMs;
        }

        /** 목록 PDT 축 절대 시각(플레이어 카드 클릭 이동). 고른 행의 PDT 가 비면 없다. */
        public Long playerAtMs() {
            return row.pdtMs() == null ? null : row.pdtMs() + offsetMs;
        }

        /** 조각이 올라가 있을 때만 — 아니면 주문 없이 기다린다. */
        public Optional<ThumbnailOrder> thumbnailOrder() {
            if (!row.uploaded()) {
                return Optional.empty();
            }
            OffsetOrigin origin = OffsetOrigin.ofTimingVersion(row.timingVersion());
            return Optional.of(new ThumbnailOrder(row.seq(), offsetMs, origin));
        }
    }

    private final RoundRows rounds;

    public CardPositionReader(RoundRows rounds) {
        this.rounds = rounds;
    }

    /**
     * 한 방송 카드 값들의 장면.
     *
     * @return 값 → 장면. 장면이 없는 값(라이브 머리 · 회차 행 없음)은 맵에 없다. 값이 비면 DB 를 안 탄다
     */
    public Map<Long, Scene> scenesOf(Broadcast broadcast, Collection<Long> values) {
        if (values.isEmpty()) {
            return Map.of();
        }
        Optional<Round> round = rounds.roundOf(broadcast);
        if (round.isEmpty()) {
            return Map.of();
        }
        return scenesOf(rounds.rowsOf(round.get()), values);
    }

    /**
     * 여러 방송 카드 값들의 장면(썸네일 순회 한 묶음). 방송마다 그 방송의 회차 행 위에서 풀고, 방송이 몇이든 왕복 두 번이다(회차
     * 고르기 한 번 + 회차 행 한 번 · 계획 착수 체크리스트 C4). 한 방송의 장부 값이 장면 계산의 전제를 깨면(공통 모듈의 행 열
     * 검사 등) 그 방송만 빼고 {@code jumpcard.scene_unavailable} 을 남긴다 — 묶음 전체가 실패하면 그 카드가 묶음에 남는 동안
     * 다른 방송 카드 사진도 멈춘다. 장부 읽기 실패는 그대로 던진다.
     *
     * @param values 방송 번호 → 카드 값
     * @return 방송 번호 → (값 → 장면). 받은 방송은 모두 든다 — 회차나 회차 행이 없으면 빈 맵이고, 장면이 없는 값은 안쪽 맵에
     *         없다. <b>장면 계산이 깨져 뺀 방송만 없다</b>(부르는 쪽이 「장면 없음」과 「격리」를 가른다). 방송이 비면 DB 를 안 탄다
     */
    public Map<String, Map<Long, Scene>> scenesOf(List<Broadcast> broadcasts,
                                                  Map<String, ? extends Collection<Long>> values) {
        Map<String, List<Row>> rows = rounds.rowsOf(rounds.roundsOf(broadcasts));
        Map<String, Map<Long, Scene>> scenes = new HashMap<>();
        for (Broadcast broadcast : broadcasts) {
            String streamId = broadcast.getStreamId();
            Collection<Long> of = values.get(streamId);
            try {
                scenes.put(streamId, scenesOf(rows.getOrDefault(streamId, List.of()), of == null ? List.of() : of));
            } catch (RuntimeException e) {
                log.warn("jumpcard.scene_unavailable streamId={} reason={}", streamId, e.getClass().getSimpleName());
            }
        }
        return scenes;
    }

    /** @param rows 회차 행, seq 오름차순 */
    static Map<Long, Scene> scenesOf(List<Row> rows, Collection<Long> values) {
        Map<Long, Scene> scenes = new HashMap<>();
        if (rows.isEmpty()) {
            return scenes;
        }
        List<LedgerRow> ledger = rows.stream()
                .map(r -> new LedgerRow(r.seq(), r.startPtsMs(), r.durationMs(), false))
                .toList();
        Map<Long, Row> bySeq = new HashMap<>();
        rows.forEach(r -> bySeq.put(r.seq(), r));
        for (long v : values) {
            sceneOf(ledger, bySeq, rows.get(0), v).ifPresent(scene -> scenes.put(v, scene));
        }
        return scenes;
    }

    /**
     * 이어짐 칸은 거짓으로 둔다 — 틈은 이어졌든 끊겼든 뒤 행 첫 화면이라 F 의 공백(뒤 행 seq)만 쓰면 된다(설계 r7 4절 —
     * 가르는 판정을 쓰는 곳이 없다).
     */
    private static Optional<Scene> sceneOf(List<LedgerRow> ledger, Map<Long, Row> bySeq, Row first, long v) {
        return switch (LedgerTimeline.locate(ledger, v)) {
            case Place.Media m -> Optional.of(new Scene(bySeq.get(m.seq()), m.offsetMs()));
            case Place.Gap g -> Optional.of(new Scene(bySeq.get(g.afterSeq()), 0));
            case Place.BeforeFirst b -> Optional.of(new Scene(first, 0));
            case Place.AfterLast a -> Optional.empty();
        };
    }
}
