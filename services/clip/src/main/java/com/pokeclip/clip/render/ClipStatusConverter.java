package com.pokeclip.clip.render;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** 표의 소문자와 enum을 잇는다({@code BroadcastStatusConverter}와 같은 모양). */
@Converter
public class ClipStatusConverter implements AttributeConverter<ClipStatus, String> {

    @Override
    public String convertToDatabaseColumn(ClipStatus attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public ClipStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : ClipStatus.fromDbValue(dbData);
    }
}
