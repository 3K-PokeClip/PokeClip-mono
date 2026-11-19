package com.pokeclip.auth.streamkey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "stream_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StreamKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "streamid_hash", nullable = false, length = 64)
    private String streamidHash;

    @Column(name = "passphrase_ref", nullable = false)
    private String passphraseRef;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 연동 코드가 마지막으로 쓰인 시각(POK-607). NULL이면 이 키로는 아직 플러그인을 연결한 적이 없다.
     *
     * <p><b>「지금 연결돼 있다」가 아니다</b> — 플러그인이 독에서 연결을 끊어도 서버는 모른다. 웹은 이것으로
     * 「코드만 받았다」와 「코드를 OBS에 넣었다」를 가른다. 키는 첫 코드 발급 때 생기므로
     * {@code createdAt}만으로는 둘이 안 갈린다.
     *
     * <p>재발급(rotate)은 새 행을 만들어 다시 NULL이다. 옛 키를 받은 플러그인은 더는 못 붙으니 맞는 값이다.
     * 값은 {@code StreamKeyRepository.markPaired}의 UPDATE로만 바뀐다 — 그래서 세터가 없다.
     */
    @Column(name = "last_paired_at")
    private Instant lastPairedAt;

    private StreamKey(Long userId, String streamidHash, String passphraseRef, Instant createdAt) {
        this.userId = userId;
        this.streamidHash = streamidHash;
        this.passphraseRef = passphraseRef;
        this.createdAt = createdAt;
    }

    static StreamKey of(Long userId, String streamidHash, String passphraseRef, Instant createdAt) {
        return new StreamKey(userId, streamidHash, passphraseRef, createdAt);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
