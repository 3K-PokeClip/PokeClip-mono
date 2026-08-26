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
}
