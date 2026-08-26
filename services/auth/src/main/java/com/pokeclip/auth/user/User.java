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

/**
 * 🔴 <b>{@code @DynamicUpdate}가 있어야 이름 수정과 사진 수정이 서로 안 덮는다</b>
 * (PR #133 codex P2, 재현함).
 *
 * <p>Hibernate 기본값은 <b>매핑된 칸을 전부</b> UPDATE에 싣는다. 두 창구가 같은 회원 행을
 * 각자 읽어 각자 커밋하면, <b>나중에 커밋한 쪽이 상대가 방금 넣은 값을 옛 스냅샷으로 되돌린다</b> —
 * 실측에서 사진을 붙이는 트랜잭션이 방금 바뀐 이름을 되돌렸다. 사진 쪽이 지워지는 방향이면
 * <b>S3 파일이 주인 없이 남는다.</b>
 *
 * <p>이 애너테이션이 붙으면 <b>그 트랜잭션에서 실제로 바뀐 칸만</b> 나간다. 이름 수정은
 * {@code name}·{@code updated_at}만, 사진 붙이기는 사진 칸들과 {@code updated_at}만 쓴다 —
 * 겹치는 것은 {@code updated_at} 하나이고 그것은 마지막이 이기면 그만이다.
 *
 * <p>락이나 버전 칸 대신 이것을 쓴 이유: 락은 토큰 회전·스트림키 재발급이 쓰는 같은 행을
 * 붙들어 그 경로의 대기를 늘리고, 버전 칸은 마이그레이션이다. <b>이 방법은 둘 다 없다.</b>
 */
@org.hibernate.annotations.DynamicUpdate
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

    /** 창고의 파일 이름. 비면 구글이 준 profileImageUrl을 쓴다. */
    @Column(name = "profile_photo_key")
    private String profilePhotoKey;

    @Column(name = "profile_photo_updated_at")
    private Instant profilePhotoUpdatedAt;

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
     * 올린 사진이 있는가. {@code profilePhotoKey}가 사진 유무의 유일한 근거다 —
     * V110의 컬럼 주석과 같은 말이고, {@code attachPhoto}가 두 칸을 한 번에 채우므로
     * 한쪽만 차 있는 상태는 코드로 만들어지지 않는다.
     */
    public boolean hasUploadedPhoto() {
        return profilePhotoKey != null;
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

    /**
     * 올린 사진을 가리키게 한다. <b>구글 주소를 비운다</b> — 되돌리기가 비목표라 영영 안 읽히고,
     * 안 쓰는 외부 주소를 계속 보관할 명분이 없다(PRD). 영구 손실은 아니다: 구글은 로그인할 때마다
     * 이름·사진을 다시 보내온다(지금은 받아서 버린다).
     *
     * <p>파일 이름을 여기서 짓지 않고 받아 온다 — 이름 짓는 법은 창고의 규칙이고(PhotoStorage.keyOf),
     * 엔티티가 그것을 알면 창고를 갈아탈 때 두 곳이 갈린다.
     */
    public void attachPhoto(String key, Instant now) {
        this.profilePhotoKey = key;
        this.profilePhotoUpdatedAt = now;
        this.profileImageUrl = null;
        this.updatedAt = now;
    }
}
