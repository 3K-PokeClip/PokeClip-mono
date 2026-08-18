package com.pokeclip.auth.delegation;

import lombok.Getter;

@Getter
public class DelegationException extends RuntimeException {

    private final DelegationFailure failure;

    public DelegationException(DelegationFailure failure, String message) {
        super(message);
        this.failure = failure;
    }
}
