package com.skala.backend.repair;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.support.*;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.util.*;

@Component
public class LocalFiles {
    private final Path root;
    public LocalFiles(@Value("${app.files}") String root) throws IOException {
        this.root=Path.of(root).toAbsolutePath().normalize(); Files.createDirectories(this.root);
        if(Files.isSymbolicLink(this.root)) throw new IOException("Storage root must not be a symlink");
    }
    public record Saved(String key,String mime,long size,String hash) {}
    public Saved save(MultipartFile file) {
        if(file.isEmpty() || file.getSize()>10485760) throw ApiError.bad("FILE_SIZE");
        try {
            byte[] bytes=file.getBytes(); String mime=detect(bytes);
            if(!Objects.equals(file.getContentType(),mime)) throw ApiError.bad("FILE_TYPE_MISMATCH");
            String key=UUID.randomUUID().toString(); Path path=path(key);
            Files.write(path,bytes,StandardOpenOption.CREATE_NEW);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { if(status!=STATUS_COMMITTED) { try {Files.deleteIfExists(path);} catch(IOException e) { /* Storage failure: an unreferenced object may remain; see storage operations guide. */ } } }
            });
            return new Saved(key,mime,bytes.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch(IOException|NoSuchAlgorithmException e) { throw new ApiError(500,"FILE_STORAGE_ERROR"); }
    }
    static String detect(byte[] b) {
        if(b.length>=8 && Arrays.equals(Arrays.copyOf(b,8),new byte[]{(byte)137,80,78,71,13,10,26,10})) return "image/png";
        if(b.length>=3 && b[0]==(byte)255 && b[1]==(byte)216 && b[2]==(byte)255) return "image/jpeg";
        if(b.length>=5 && new String(b,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) return "application/pdf";
        try {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b));
            for(byte c:b) if(c>=0 && c<32 && c!=9 && c!=10 && c!=13) throw ApiError.bad("FILE_TYPE");
            return "text/plain";
        } catch(java.nio.charset.CharacterCodingException e) { throw ApiError.bad("FILE_TYPE"); }
    }
    public Path path(String key) {
        if(!key.matches("[0-9a-fA-F-]{36}")) throw ApiError.bad("INVALID_OBJECT_KEY");
        Path p=root.resolve(key).normalize(); if(!p.getParent().equals(root) || Files.isSymbolicLink(p)) throw ApiError.denied(); return p;
    }
}
