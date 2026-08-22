package com.pokeclip.clip.jumpcard;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** DB 값이 소문자라 @Enumerated(STRING)을 못 쓴다(BroadcastStatusConverter와 같은 이유). */
@Converter
class JumpCardSourceConverter implements AttributeConverter<JumpCardSource, String> {

    @Override
    public String convertToDatabaseColumn(JumpCardSource attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public JumpCardSource convertToEntityAttribute(String dbData) {
        return dbData == null ? null : JumpCardSource.fromDbValue(dbData);
    }
}
