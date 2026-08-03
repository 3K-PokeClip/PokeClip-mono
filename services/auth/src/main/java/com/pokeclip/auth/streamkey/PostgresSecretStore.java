package com.pokeclip.auth.streamkey;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class PostgresSecretStore implements SecretStore {

    private final SecretRepository secretRepository;
    private final AesGcmCipher cipher;

    @Override
    @Transactional
    public void put(String ref, String value) {
        byte[] sealed = cipher.encrypt(value);

        secretRepository.findById(ref)
                .ifPresentOrElse(
                        existing -> existing.replaceCiphertext(sealed),
                        () -> secretRepository.save(Secret.of(ref, sealed, Instant.now())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> get(String ref) {
        return secretRepository.findById(ref)
                .map(secret -> cipher.decrypt(secret.getCiphertext()));
    }

    @Override
    @Transactional
    public void delete(String ref) {
        secretRepository.deleteById(ref);
    }
}
