package net.coreprotect.utility.serialize;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Lossless compression of stored item metadata (container, entity container and item rows).
 *
 * <p>Compressed values are {@code 'C' 'Z' version dictionary-id} followed by a zlib stream that uses a
 * preset dictionary shipped in the jar. Decompressing returns the exact bytes that were compressed, so every
 * decoder after it sees what it sees today. Values without the header are returned unchanged, so rows written
 * before compression was turned on stay readable. The header cannot be confused with Java serialization
 * (0xAC 0xED), {@link BlockMetaCodec} ({@code CB}) or {@link EntityDataCodec} ({@code CP}).
 *
 * <p>Dictionary 1 was trained on the metadata of generated vanilla items, so it holds serialization structure
 * and common keys only. A dictionary id is never reused: a new dictionary gets a new id and a new resource, and
 * the old one stays so its rows remain readable.
 */
public final class MetadataCompression {

    private static final int MAGIC_FIRST = 'C';
    private static final int MAGIC_SECOND = 'Z';
    private static final int VERSION = 1;
    private static final int HEADER_LENGTH = 4;
    private static final int CURRENT_DICTIONARY = 1;
    private static final int MAX_DICTIONARY_ID = 1;
    private static final int LEVEL = 6;
    private static final byte[][] DICTIONARIES = new byte[MAX_DICTIONARY_ID + 1][];

    private MetadataCompression() {
        throw new IllegalStateException("Codec class");
    }

    /**
     * Returns true when the value carries the compression header.
     */
    public static boolean isCompressed(byte[] data) {
        return data != null && data.length >= HEADER_LENGTH
                && Byte.toUnsignedInt(data[0]) == MAGIC_FIRST
                && Byte.toUnsignedInt(data[1]) == MAGIC_SECOND;
    }

    /**
     * Compresses a stored value. Returns the value unchanged when it is null, already compressed, or would not
     * get smaller.
     */
    public static byte[] compress(byte[] data) {
        if (data == null || data.length == 0 || isCompressed(data)) {
            return data;
        }
        Deflater deflater = new Deflater(LEVEL);
        try {
            deflater.setDictionary(dictionary(CURRENT_DICTIONARY));
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(data.length, 64 * 1024) + HEADER_LENGTH);
            output.write(MAGIC_FIRST);
            output.write(MAGIC_SECOND);
            output.write(VERSION);
            output.write(CURRENT_DICTIONARY);
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int length = deflater.deflate(buffer);
                output.write(buffer, 0, length);
                if (output.size() >= data.length) {
                    return data;
                }
            }
            return output.toByteArray();
        }
        finally {
            deflater.end();
        }
    }

    /**
     * Returns the original bytes of a compressed value, or the value itself when it has no compression header.
     *
     * @throws IllegalArgumentException
     *             when the value has the header but cannot be decompressed (unknown version or dictionary,
     *             damaged stream, or a result over the codec size limit)
     */
    public static byte[] decompress(byte[] data) {
        if (!isCompressed(data)) {
            return data;
        }
        int version = Byte.toUnsignedInt(data[2]);
        int dictionaryId = Byte.toUnsignedInt(data[3]);
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported compressed metadata version " + version);
        }
        byte[] dictionary = dictionary(dictionaryId);
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data, HEADER_LENGTH, data.length - HEADER_LENGTH);
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(data.length * 8, 256 * 1024));
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int length = inflater.inflate(buffer);
                if (length == 0) {
                    if (inflater.needsDictionary()) {
                        inflater.setDictionary(dictionary);
                        continue;
                    }
                    if (inflater.needsInput()) {
                        throw new IllegalArgumentException("Compressed metadata is truncated");
                    }
                }
                output.write(buffer, 0, length);
                if (output.size() > BinaryCodecSupport.MAX_ENCODED_LENGTH) {
                    throw new IllegalArgumentException("Compressed metadata exceeds the maximum decoded size");
                }
            }
            if (inflater.getRemaining() != 0) {
                throw new IllegalArgumentException("Compressed metadata has trailing bytes");
            }
            return output.toByteArray();
        }
        catch (DataFormatException exception) {
            throw new IllegalArgumentException("Compressed metadata is damaged", exception);
        }
        finally {
            inflater.end();
        }
    }

    private static byte[] dictionary(int id) {
        if (id < 1 || id > MAX_DICTIONARY_ID) {
            throw new IllegalArgumentException("Unknown compressed metadata dictionary " + id);
        }
        synchronized (DICTIONARIES) {
            if (DICTIONARIES[id] == null) {
                String resource = "/metadata-dictionary-" + id + ".bin";
                try (InputStream input = MetadataCompression.class.getResourceAsStream(resource)) {
                    if (input == null) {
                        throw new IllegalStateException("Missing resource " + resource);
                    }
                    DICTIONARIES[id] = input.readAllBytes();
                }
                catch (IOException exception) {
                    throw new IllegalStateException("Unable to read " + resource, exception);
                }
            }
            return DICTIONARIES[id]; // never handed out of this class; Deflater/Inflater only read it
        }
    }
}
