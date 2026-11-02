package com.pokeclip.clip.broadcast;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** DB 값이 소문자라 @Enumerated(STRING)을 못 쓴다. */
@Converter
class BroadcastStatusConverter implements AttributeConverter<BroadcastStatus, String> {

    @Override
    public String convertToDatabaseColumn(BroadcastStatus attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public BroadcastStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : BroadcastStatus.fromDbValue(dbData);
    }
}
