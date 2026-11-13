package dev.xantha.vss.common.worldgen;

import com.google.gson.*;
import com.google.gson.stream.JsonWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable object order for persisted worldgen identities; array order is semantic. */
public final class WorldgenJson {
    public static byte[] bytes(JsonElement value) {
        var text = new StringWriter();
        try (var writer = new JsonWriter(text)) {
            writer.setSerializeNulls(true);
            write(writer, value);
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** bytes(value).length, counted without building the string or its byte array. */
    public static long length(JsonElement value) {
        var counter = new Utf8Counter();
        try (var writer = new JsonWriter(counter)) {
            writer.setSerializeNulls(true);
            write(writer, value);
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
        return counter.finish();
    }

    /**
     * UTF-8 length as String.getBytes(UTF_8) gives it: a surrogate pair is four bytes, a lone surrogate becomes one '?'.
     * A pair may arrive split over two writes, so a high surrogate waits for the next char.
     */
    private static final class Utf8Counter extends Writer {
        private long bytes;
        private boolean pendingHigh;

        @Override public void write(int c) { count((char) c); }
        @Override public void write(char[] buffer, int offset, int length) { for (int i = 0; i < length; i++) count(buffer[offset + i]); }
        @Override public void write(String text, int offset, int length) { for (int i = 0; i < length; i++) count(text.charAt(offset + i)); }
        @Override public void flush() { }
        @Override public void close() { }

        private void count(char c) {
            if (pendingHigh) {
                pendingHigh = false;
                if (Character.isLowSurrogate(c)) { bytes += 4; return; }
                bytes += 1;
            }
            if (Character.isHighSurrogate(c)) pendingHigh = true;
            else if (Character.isLowSurrogate(c) || c < 0x80) bytes += 1;
            else if (c < 0x800) bytes += 2;
            else bytes += 3;
        }

        long finish() {
            if (pendingHigh) { pendingHigh = false; bytes += 1; }
            return bytes;
        }
    }

    /** Hash the same canonical bytes without retaining another full snapshot string. */
    public static String sha256(JsonElement value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var writer = new JsonWriter(new BufferedWriter(new OutputStreamWriter(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest), StandardCharsets.UTF_8)))) {
                writer.setSerializeNulls(true);
                write(writer, value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void write(JsonWriter writer, JsonElement value) throws IOException {
        if (value.isJsonObject()) {
            writer.beginObject();
            for (String key : value.getAsJsonObject().keySet().stream().sorted().toList()) {
                writer.name(key);write(writer,value.getAsJsonObject().get(key));
            }
            writer.endObject();
        } else if (value.isJsonArray()) {
            writer.beginArray();for(var child:value.getAsJsonArray())write(writer,child);writer.endArray();
        } else if (value.isJsonNull()) writer.nullValue();
        else {
            var primitive=value.getAsJsonPrimitive();
            if(primitive.isBoolean())writer.value(primitive.getAsBoolean());
            else if(primitive.isNumber())writer.value(primitive.getAsNumber());
            else writer.value(primitive.getAsString());
        }
    }
    private WorldgenJson() { }
}
