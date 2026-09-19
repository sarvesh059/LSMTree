package core.key;

import core.key.KeyCodecImpl.StringKeyCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class StringKeyCodecTest {
    private KeyCodec<String> codec;
    private File dataFile;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.codec = new StringKeyCodec();
        this.dataFile = tempDir.resolve("dataFile").toFile();
    }

    @ParameterizedTest(name = "encode/decode -> \"{0}\"")
    @ValueSource(strings = {"", "a", "ab", "abc", "hello world", "special chars !@#$%^&*()"})
    void correctlyEncodesAndDecodesString(String input) throws IOException {
        try (RandomAccessFile outputStream = new RandomAccessFile(this.dataFile, "rw")) {
            this.codec.encode(input, outputStream);
        }

        String output;
        try (RandomAccessFile inputStream = new RandomAccessFile(this.dataFile, "r")) {
            output = this.codec.decode(inputStream);
        }

        assertEquals(input, output, "decoded value should be equal to encoded value");
    }

    @Test
    void roundTripsStringWithEmbeddedNulCharacter() throws IOException {
        String input = "foo" + (char) 0 + "bar";

        try (RandomAccessFile outputStream = new RandomAccessFile(this.dataFile, "rw")) {
            this.codec.encode(input, outputStream);
        }

        // "foo" (3) + escaped NUL (2) + "bar" (3) + terminator (2) = 10 bytes
        assertEquals(10, this.dataFile.length(), "embedded NUL should be escaped to two bytes, not silently dropped or truncated");

        String output;
        try (RandomAccessFile inputStream = new RandomAccessFile(this.dataFile, "r")) {
            output = this.codec.decode(inputStream);
        }

        assertEquals(input, output, "the embedded NUL character should survive the round trip unchanged");
    }

    @Test
    void compareEncodedMatchesStringCompareToAcrossRandomBmpStrings() throws IOException {
        String[] fixedValues = {"", "a", "ab", "abc", "abd", "b", "foo" + (char) 0 + "bar"};
        Random rng = new Random(99);
        String[] values = new String[fixedValues.length + 300];
        System.arraycopy(fixedValues, 0, values, 0, fixedValues.length);
        for (int i = fixedValues.length; i < values.length; i++) {
            values[i] = randomBmpString(rng);
        }

        byte[][] encoded = new byte[values.length][];
        for (int i = 0; i < values.length; i++) {
            encoded[i] = encode(values[i]);
        }

        for (int i = 0; i < values.length; i++) {
            for (int j = 0; j < values.length; j++) {
                int expectedSign = Integer.signum(values[i].compareTo(values[j]));
                int actualSign = Integer.signum(this.codec.compareEncoded(encoded[i], encoded[j]));
                assertEquals(expectedSign, actualSign,
                        "compareEncoded(\"" + values[i] + "\", \"" + values[j] + "\") should have the same sign as String.compareTo");
            }
        }
    }

    @Test
    void shorterPrefixSortsBeforeLongerExtension() throws IOException {
        byte[] ab = encode("ab");
        byte[] abc = encode("abc");
        byte[] abd = encode("abd");

        assertEquals(-1, Integer.signum(this.codec.compareEncoded(ab, abc)), "\"ab\" should sort before \"abc\", its own extension");
        assertEquals(-1, Integer.signum(this.codec.compareEncoded(abc, abd)), "\"abc\" should sort before \"abd\" (differ on the last character)");
        assertEquals(1, Integer.signum(this.codec.compareEncoded(abc, ab)), "\"abc\" should sort after \"ab\"");
    }

    @Test
    void embeddedNulAffectsOrderingCorrectly() throws IOException {
        // "a\0" vs "ab" -- second char NUL (code point 0) vs 'b' (code point 98): NUL is smaller.
        byte[] aNul = encode("a" + (char) 0);
        byte[] ab = encode("ab");

        assertEquals(-1, Integer.signum(this.codec.compareEncoded(aNul, ab)),
                "\"a\\0\" should sort before \"ab\" since the NUL character sorts before 'b'");
    }

    @Test
    void compareEncodedIsReflexive() throws IOException {
        for (String s : new String[]{"", "a", "hello", "foo" + (char) 0 + "bar"}) {
            assertEquals(0, this.codec.compareEncoded(encode(s), encode(s)), "compareEncoded(x, x) should always be 0 for \"" + s + "\"");
        }
    }

    private byte[] encode(String value) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        this.codec.encode(value, new DataOutputStream(bos));
        return bos.toByteArray();
    }

    private String randomBmpString(Random rng) {
        int len = rng.nextInt(8);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (rng.nextInt(10) == 0) {
                sb.append((char) 0); // occasionally inject a literal NUL to stress the escape path
            } else {
                sb.append((char) (0x20 + rng.nextInt(0x7E - 0x20))); // printable ASCII range
            }
        }
        return sb.toString();
    }
}
