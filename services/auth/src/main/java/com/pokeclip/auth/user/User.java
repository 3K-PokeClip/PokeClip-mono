package com.pokeclip.auth.user;

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
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "google_sub", nullable = false, unique = true)
    private String googleSub;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String name;

    @Column(name = "profile_image_url")
    private String profileImageUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private User(String googleSub, String email, String name, String profileImageUrl, Instant now) {
        this.googleSub = googleSub;
        this.email = email;
        this.name = name;
        this.profileImageUrl = profileImageUrl;
        this.createdAt = now;
        this.updatedAt = now;
    }

    static User of(String googleSub, String email, String name, String profileImageUrl, Instant now) {
        return new User(googleSub, email, name, profileImageUrl, now);
    }

    /**
     * 표시 이름을 바꾼다. <b>트림·길이 판정은 부르는 쪽이 끝낸 뒤 넘긴다</b> — 엔티티가 형식 규칙을
     * 들고 있으면 같은 규칙이 서비스 검증과 두 벌이 되고, 한쪽만 고쳐지는 날이 온다.
     *
     * <p>updated_at을 여기서 같이 움직인다. 가입 이후 이 칸이 한 번도 안 바뀌던 것이
     * "프로필 갱신 안 함"의 결과였고(UserService.findOrCreate), 이름 수정이 그 첫 갱신 경로다.
     */
    public void changeName(String trimmedName, Instant now) {
        this.name = trimmedName;
        this.updatedAt = now;
    }
}
