package com.pokeclip.auth.streamkey.secret;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "secrets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Secret {

    @Id
    @Column(name = "ref")
    private String ref;

    @Column(name = "ciphertext", nullable = false)
    private byte[] ciphertext;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private Secret(String ref, byte[] ciphertext, Instant createdAt) {
        this.ref = ref;
        this.ciphertext = ciphertext;
        this.createdAt = createdAt;
    }

    static Secret of(String ref, byte[] ciphertext, Instant createdAt) {
        return new Secret(ref, ciphertext, createdAt);
    }

    void replaceCiphertext(byte[] ciphertext) {
        this.ciphertext = ciphertext;
    }
}
