package com.pokeclip.clip.render;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class RenderJobStatusConverter implements AttributeConverter<RenderJobStatus, String> {

    @Override
    public String convertToDatabaseColumn(RenderJobStatus attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public RenderJobStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : RenderJobStatus.fromDbValue(dbData);
    }
}
