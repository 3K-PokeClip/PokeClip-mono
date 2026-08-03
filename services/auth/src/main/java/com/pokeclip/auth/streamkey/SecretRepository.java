package com.pokeclip.auth.streamkey;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SecretRepository extends JpaRepository<Secret, String> {
}
