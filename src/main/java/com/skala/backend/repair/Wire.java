package com.skala.backend.repair;

import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import java.util.*;

@RestControllerAdvice
public class Wire implements ResponseBodyAdvice<Object> {
    public static Object ids(Object value) { return convert(value,""); }
    private static Object convert(Object value,String key) {
        if(Set.of("userInput","user_input","information").contains(key)) return value;
        boolean identifier=key.equals("id") || key.endsWith("_id") || key.endsWith("Id") || key.endsWith("Ids");
        if(value instanceof Number && identifier) return value.toString();
        if(value instanceof Map<?,?> map) {
            var result=new LinkedHashMap<String,Object>();map.forEach((k,v)->result.put(k.toString(),convert(v,k.toString())));return result;
        }
        if(value instanceof List<?> list) return list.stream().map(v->convert(v,key)).toList();
        return value;
    }
    public boolean supports(MethodParameter p,Class<? extends HttpMessageConverter<?>> type) {return true;}
    public Object beforeBodyWrite(Object body,MethodParameter p,MediaType media,Class<? extends HttpMessageConverter<?>> type,org.springframework.http.server.ServerHttpRequest req,org.springframework.http.server.ServerHttpResponse res) {return ids(body);}
}
