package bloomFilter;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class BloomFilterTest {

    @Test
    void neverProducesAFalseNegative() {
        int n = 5000;
        BloomFilter filter = new BloomFilter(n, 0.01);

        for (int i = 0; i < n; i++) {
            filter.add(key(i));
        }

        for (int i = 0; i < n; i++) {
            assertTrue(filter.mightContain(key(i)),
                    "key " + i + " was inserted but mightContain reported absent -- a bloom filter must never have a false negative");
        }
    }

    @Test
    void falsePositiveRateIsWithinTargetTolerance() {
        int n = 5000;
        double targetRate = 0.01;
        int trials = 20_000;
        BloomFilter filter = new BloomFilter(n, targetRate);

        for (int i = 0; i < n; i++) {
            filter.add(key(i));
        }

        int falsePositives = 0;
        for (int i = n; i < n + trials; i++) {
            if (filter.mightContain(key(i))) falsePositives++;
        }
        double observedRate = (double) falsePositives / trials;

        assertAll(
                () -> assertTrue(observedRate <= targetRate * 2,
                        "observed rate " + observedRate + " should not exceed ~2x the target " + targetRate),
                () -> assertTrue(observedRate >= targetRate / 2,
                        "observed rate " + observedRate + " is suspiciously far below target " + targetRate
                                + " -- may indicate mightContain/hash is broken rather than just lucky")
        );
    }

    @Test
    void emptyFilterNeverReportsMightContain() {
        BloomFilter filter = new BloomFilter(1000, 0.01);

        assertFalse(filter.mightContain(key(1)), "a filter with nothing added should never report a key as present");
    }

    @Test
    void survivesSerializationRoundTrip() throws IOException {
        int n = 1000;
        BloomFilter filter = new BloomFilter(n, 0.01);
        for (int i = 0; i < n; i++) {
            filter.add(key(i));
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        filter.writeTo(new DataOutputStream(bos));

        BloomFilter reloaded = BloomFilter.readFrom(new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));

        for (int i = 0; i < n; i++) {
            assertTrue(reloaded.mightContain(key(i)), "reloaded filter should still report every originally-inserted key as present");
        }
    }

    private byte[] key(int i) {
        return ("key-" + i).getBytes(StandardCharsets.UTF_8);
    }
}
