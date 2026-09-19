package core.key;

import core.key.KeyCodecImpl.IntegerKeyCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class IntegerKeyCodecTest {
    private KeyCodec<Integer> codec;
    private File dataFile;

    @BeforeEach
    void setUp(@TempDir Path tempDir){
        this.codec = new IntegerKeyCodec();
        this.dataFile = tempDir.resolve("dataFile").toFile();
    }

    @ParameterizedTest(name = "encode/decode -> {0}")
    @CsvSource(value = {
            "-1", "0", "1", "-245", "10023", "-2147483648", "2147483647"
    })
    void correctlyEncodesAndDecodesInteger(int input) throws IOException {
        try(RandomAccessFile inputStream = new RandomAccessFile(this.dataFile, "rw")){
            this.codec.encode(input, inputStream);
        }

        assertEquals(4, this.dataFile.length(), "encoded integer should always be exactly 4 bytes");

        int output = 0;
        try(RandomAccessFile outputStream = new RandomAccessFile(this.dataFile, "r")){
            output = this.codec.decode(outputStream);
        }

        assertEquals(input, output, "decoded value should be equal to encode value");
    }

    @Test
    void compareEncodedMatchesIntegerCompareAcrossFullRange() throws IOException {
        int[] boundaryValues = {
                Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -1, 1, -245, 10023
        };
        Random rng = new Random(1234);
        int[] values = new int[boundaryValues.length + 500];
        System.arraycopy(boundaryValues, 0, values, 0, boundaryValues.length);
        for (int i = boundaryValues.length; i < values.length; i++) {
            values[i] = rng.nextInt();
        }

        byte[][] encoded = new byte[values.length][];
        for (int i = 0; i < values.length; i++) {
            encoded[i] = encode(values[i]);
        }

        for (int i = 0; i < values.length; i++) {
            for (int j = 0; j < values.length; j++) {
                int expectedSign = Integer.signum(Integer.compare(values[i], values[j]));
                int actualSign = Integer.signum(this.codec.compareEncoded(encoded[i], encoded[j]));
                assertEquals(expectedSign, actualSign,
                        "compareEncoded(" + values[i] + ", " + values[j] + ") should have the same sign as Integer.compare");
            }
        }
    }

    private byte[] encode(int value) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        this.codec.encode(value, new DataOutputStream(bos));
        return bos.toByteArray();
    }
}
