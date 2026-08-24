package com.pokeclip.clip.broadcast;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;

/** 방송 명부. 방송 한 회당 한 줄이고 stream_id가 그 방송의 이름이다. */
@Entity
@Table(name = "broadcasts")
public class Broadcast {

    /**
     * 방송이 끝난 뒤 원본을 얼마나 보관하는가(ADR-004 — VOD 60일).
     *
     * <p><b>설정으로 빼지 않는다.</b> 환경(로컬·운영)마다 달라지는 값이 아니라
     * 요금제 축의 값이다 — 바뀌는 날은 상품이 바뀌는 날이고, 그때는 이미 채워진
     * 줄을 어떻게 할지도 함께 정해야 한다. 환경변수로 두면 그 결정이 조용히
     * 배포 설정으로 흘러내린다.
     */
    public static final Duration VOD_RETENTION = Duration.ofDays(60);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stream_id", nullable = false, length = 128)
    private String streamId;

    @Column(name = "streamer_id", nullable = false, length = 128)
    private String streamerId;

    @Convert(converter = BroadcastStatusConverter.class)
    @Column(name = "status", nullable = false, length = 16)
    private BroadcastStatus status;

    /** 종료 선도착 placeholder는 null이다. */
    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    /** NULL이 「아직 안 끝나 기한이 없다」는 뜻이다. 값은 종료 처리 때만 찬다. */
    @Column(name = "vod_expires_at")
    private Instant vodExpiresAt;

    // columnDefinition만으로는 부족하다 — @JdbcTypeCode가 없으면 드라이버가 varchar로
    // 보내고 "column is of type jsonb but expression is of type character varying"로
    // INSERT가 전부 실패한다. 값이 null이어도 터진다(setNull(VARCHAR)).
    // ddl-auto=validate는 이걸 못 잡는다 (plan-critic 실측 2026-08-18).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "track_manifest", columnDefinition = "jsonb")
    private String trackManifest;

    @Column(name = "last_sequence", nullable = false)
    private Long lastSequence;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Broadcast() {
    }

    private Broadcast(String streamId, String streamerId, BroadcastStatus status,
                      Instant startedAt, Instant endedAt, String trackManifest, long lastSequence) {
        this.streamId = streamId;
        this.streamerId = streamerId;
        this.status = status;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.trackManifest = trackManifest;
        this.lastSequence = lastSequence;
        this.updatedAt = Instant.now();
    }

    public static Broadcast startedNow(String streamId, String streamerId, long sequence,
                                       Instant startedAt, String trackManifest) {
        return new Broadcast(streamId, streamerId, BroadcastStatus.LIVE,
                startedAt, null, trackManifest, sequence);
    }

    /** ADR-016의 ended placeholder — 시작을 못 본 채 끝을 먼저 받은 줄. */
    public static Broadcast endedPlaceholder(String streamId, String streamerId,
                                             long sequence, Instant endedAt) {
        Broadcast placeholder = new Broadcast(streamId, streamerId, BroadcastStatus.ENDED,
                null, endedAt, null, sequence);
        // 생성자에 칸을 더하지 않고 여기서 채운다 — startedNow는 endedAt이 null이라
        // 같은 식을 태울 수 없고, 생성자에 기한을 받는 칸을 열면 두 팩토리가 서로
        // 다른 값을 넣을 수 있는 구멍이 생긴다. 기한은 종료 시각에서만 나온다.
        placeholder.vodExpiresAt = endedAt.plus(VOD_RETENTION);
        return placeholder;
    }

    /**
     * 시작 편지를 반영한다. 이미 반영한 순서 번호보다 낮거나 같으면 아무것도 하지
     * 않고 false를 돌려준다 — <b>순서를 바로잡는 것이 아니라 견디는 것</b>이 목표다.
     *
     * <p>이미 ENDED인 줄에 시작이 와도 상태를 되돌리지 않는다. 순서 번호가 더
     * 높다면 시작 시각만 채운다(placeholder였던 줄이 뒤늦게 시작 정보를 얻는 경우).
     */
    boolean applyStarted(long sequence, Instant at, String trackManifest) {
        if (sequence <= this.lastSequence) {
            return false;
        }
        if (this.status != BroadcastStatus.ENDED) {
            this.status = BroadcastStatus.LIVE;
        }
        this.startedAt = at;
        // null로 덮지 않는다. 이 값은 ADR-016이 정한 broadcast.started payload의
        // 스냅샷이고 한 번 지워지면 복구 경로가 없다 — 뒤에 온 시작에 트랙 정보가
        // 없다고 지우면 sequence가 올라간 뒤라 낡은 편지 가드에도 안 걸려 조용히
        // 사라진다. 빈 placeholder를 뒤늦게 채우는 경로는 그대로 산다.
        if (trackManifest != null) {
            this.trackManifest = trackManifest;
        }
        this.lastSequence = sequence;
        this.updatedAt = Instant.now();
        return true;
    }

    boolean applyEnded(long sequence, Instant at) {
        if (sequence <= this.lastSequence) {
            return false;
        }
        this.status = BroadcastStatus.ENDED;
        this.endedAt = at;
        this.vodExpiresAt = at.plus(VOD_RETENTION);
        this.lastSequence = sequence;
        this.updatedAt = Instant.now();
        return true;
    }

    public Long getId() {
        return id;
    }

    public String getStreamId() {
        return streamId;
    }

    public String getStreamerId() {
        return streamerId;
    }

    public BroadcastStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public Instant getVodExpiresAt() {
        return vodExpiresAt;
    }

    public String getTrackManifest() {
        return trackManifest;
    }

    public Long getLastSequence() {
        return lastSequence;
    }
}
