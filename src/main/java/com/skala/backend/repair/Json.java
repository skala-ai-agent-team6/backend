package com.skala.backend.repair;

import tools.jackson.databind.json.JsonMapper;
import java.util.*;

public final class Json {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private Json() {}
    public static String raw(Object value) {return MAPPER.writeValueAsString(value);}
    public static String write(Object value) { return MAPPER.writeValueAsString(Wire.ids(value)); }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> object(String value) { return MAPPER.readValue(value, Map.class); }
    @SuppressWarnings("unchecked")
    public static List<Map<String,Object>> objects(Object value) {
        if (!(value instanceof List<?> list)) throw ApiError.bad("INVALID_ARRAY");
        for (Object item : list) if (!(item instanceof Map<?,?>)) throw ApiError.bad("INVALID_ARRAY_ITEM");
        return (List<Map<String,Object>>) value;
    }
    public static Long id(Object v) { try { long id=Long.parseLong(String.valueOf(v));if(id<1) throw new NumberFormatException();return id;}catch(RuntimeException e){throw ApiError.bad("INVALID_ID");} }
    public static UUID uuid(Object v) { try { return UUID.fromString(String.valueOf(v)); } catch (RuntimeException e) { throw ApiError.bad("INVALID_UUID"); } }
    public static int number(Object v) { try { return new java.math.BigDecimal(String.valueOf(v)).intValueExact(); } catch (RuntimeException e) { throw ApiError.bad("INVALID_INTEGER"); } }
}
