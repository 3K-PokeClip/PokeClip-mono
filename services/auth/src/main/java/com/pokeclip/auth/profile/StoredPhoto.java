package com.pokeclip.auth.profile;

/** 꺼낸 그림. 형식은 <b>우리가 판정해 넣어 둔 값</b>이다 — 올린 쪽이 밝힌 값이 아니다. */
public record StoredPhoto(byte[] bytes, String contentType) { }
