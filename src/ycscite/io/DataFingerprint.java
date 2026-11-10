package ycscite.io;

import ycscite.model.Project;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** Identity of parsed source records, before selection/filtering. Ordered, length-framed UTF-8 JSON. */
public final class DataFingerprint {
    public static final String SCHEMA = "ycscite-records-v1";
    private DataFingerprint() {}
    public static String of(Project project) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((SCHEMA+"\0").getBytes(StandardCharsets.UTF_8));
            digest.update(ByteBuffer.allocate(4).putInt(project.records.size()).array());
            for (var r : project.records) {
                var fields = Arrays.asList(r.pt,r.authors,r.authorsFull,r.title,r.source,r.keywords,r.keywordsPlus,
                    r.abstractText,r.addresses,r.citedRefs,r.year,r.volume,r.beginPage,r.doi,r.ut,r.timesCited,r.categories);
                byte[] bytes = MiniJson.write(fields).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable",error); }
    }
}
