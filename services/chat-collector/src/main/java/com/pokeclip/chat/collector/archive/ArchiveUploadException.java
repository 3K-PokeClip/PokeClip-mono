package com.pokeclip.chat.collector.archive;

/**
 * 우리 메시지를 두지 않는다 — 키·본문이 실릴 자리를 만들지 않는다. 원인은 체인으로 남긴다.
 * 단 {@code Exception(Throwable)} 규약상 {@code getMessage()}는 {@code cause.toString()}이다(실물:
 * {@code NoSuchBucketException: The specified bucket does not exist (Service: S3, Status Code: 404, …)}) —
 * S3 AccessDenied류는 리소스 ARN(=버킷/키=채널 ID)을 실어 돌려줄 수 있으니 <b>이 예외를 로그에 통째로
 * 넘기지 마라</b>({@code log.warn("…", e)} 금지). {@code causeType}만 싣는다.
 */
public class ArchiveUploadException extends Exception {

    public ArchiveUploadException(Throwable cause) {
        super(cause);
    }
}
