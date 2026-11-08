// java -cp <CoreProtect jar> CompressBench.java <blobs-container.bin> <blobs-item.bin>
// Times the jar's own MetadataCompression per blob (single thread): 3 warm-up passes, then 7 timed passes, median pass.
// Blob files: 4-byte big-endian length, then the blob, repeated.
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;

import net.coreprotect.utility.serialize.MetadataCompression;

public class CompressBench {
    public static void main(String[] args) throws Exception {
        for (String path : args) {
            List<byte[]> blobs = read(path);
            long raw = 0;
            for (byte[] b : blobs) raw += b.length;
            byte[][] packed = new byte[blobs.size()][];
            long stored = 0;
            for (int i = 0; i < packed.length; i++) {
                packed[i] = MetadataCompression.compress(blobs.get(i));
                stored += packed[i].length;
                if (!Arrays.equals(MetadataCompression.decompress(packed[i]), blobs.get(i))) throw new AssertionError("round trip " + i);
            }
            double comp = perBlob(blobs.size(), () -> { for (byte[] b : blobs) MetadataCompression.compress(b); });
            double decomp = perBlob(blobs.size(), () -> { for (byte[] b : packed) MetadataCompression.decompress(b); });
            double plain = perBlob(blobs.size(), () -> {
                byte[] out = new byte[1 << 20];
                for (byte[] b : blobs) { Deflater d = new Deflater(6); d.setInput(b); d.finish(); while (!d.finished()) d.deflate(out); d.end(); }
            });
            String line = String.format(java.util.Locale.ROOT, "%s: %,d blobs, %,d bytes, average %.0f bytes; stored %,d bytes (%.2fx); compress %.1f us, decompress %.1f us, zlib without dictionary %.1f us per blob%n",
                    path.replaceAll(".*[\\\\/]", ""), blobs.size(), raw, (double) raw / blobs.size(), stored, (double) raw / stored, comp, decomp, plain);
            System.out.print(line);
            if (System.getProperty("out") != null) { // a panel start command cannot redirect output
                java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("out")), line, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
        }
    }

    static double perBlob(int n, Runnable pass) {
        for (int i = 0; i < 3; i++) pass.run();
        double[] us = new double[7];
        for (int i = 0; i < us.length; i++) {
            long t = System.nanoTime();
            pass.run();
            us[i] = (System.nanoTime() - t) / 1e3 / n;
        }
        Arrays.sort(us);
        return us[us.length / 2];
    }

    static List<byte[]> read(String path) throws Exception {
        List<byte[]> out = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(path)))) {
            while (in.available() > 0) {
                byte[] b = new byte[in.readInt()];
                in.readFully(b);
                out.add(b);
            }
        }
        return out;
    }
}
