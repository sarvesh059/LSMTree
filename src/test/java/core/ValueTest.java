package core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class ValueTest {

    private Value roundTrip(Value value) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        value.writeTo(new DataOutputStream(bos));

        ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
        Value decoded = Value.readFrom(new DataInputStream(bis));

        return decoded;
    }

    @Test
    void tombstoneValueInvariants(){
        Value tomb = Value.tombstone();
        Value empty = Value.of(new byte[0]);

        assertAll(
                () -> assertTrue(tomb.isTombstone(), "tombstone should report isTombstone() == true"),
                () -> assertFalse(empty.isTombstone(), "an empty-byte-array should NOT be a tombstone"),
                () -> assertNotEquals(tomb, empty, "a tombstone must not equal an empty-payload value"),
                () -> assertThrowsExactly(IllegalStateException.class, tomb::getData, "tombstone must not expose a payload"),
                () -> assertDoesNotThrow(empty::getData, "an empty-payload value must still expose its (empty) payload")
        );
    }

    @Test
    @DisplayName("T2.1: a tombstone round-trips through serialization unchanged")
    void tombstoneValuesSurvivesRoundTrip() throws IOException {
        Value tomb = Value.tombstone();

        Value decoded = roundTrip(tomb);
        assertAll(
                () -> assertEquals(tomb, decoded, "round-tripped tombstone should equal the original"),
                () -> assertTrue(decoded.isTombstone(), "round-tripped value should still be a tombstone")
        );
    }

    @Test
    @DisplayName("T2.1: a real value round-trips through serialization unchanged")
    void realValuesSurvivesRoundTrip() throws IOException {
        String str = "test";
        Value val = Value.of(str.getBytes(StandardCharsets.UTF_8));

        Value decoded = roundTrip(val);
        assertAll(
                () -> assertEquals(val, decoded, "round-tripped value should equal the original"),
                () -> assertEquals(str, new String(decoded.getData(), StandardCharsets.UTF_8), "decoded text should match original text"),
                () -> assertArrayEquals(val.getData(), decoded.getData(), "decoded bytes should match original bytes exactly"),
                () -> assertFalse(decoded.isTombstone(), "round-tripped value should not be a tombstone")
        );
    }

    @Test
    @DisplayName("T2.1: an empty-payload value round-trips through serialization unchanged")
    void emptyValueSurvivesRoundTrip() throws IOException {
        Value empty = Value.of(new byte[0]);

        Value decoded = roundTrip(empty);
        assertAll(
                () -> assertEquals(empty, decoded, "round-tripped empty value should equal the original"),
                () -> assertFalse(decoded.isTombstone(), "round-tripped empty value should not be a tombstone"),
                () -> assertArrayEquals(empty.getData(), decoded.getData(), "decode bytes should match the original empty payload")
        );
    }

    @Test
    @DisplayName("T2.1: after round-tripping, an empty value and a tombstone remain distinct")
    void decodedEmptyValueStaysDistinctFromDecodedTombstone() throws IOException {
        Value decodedEmpty = roundTrip(Value.of(new byte[0]));
        Value decodedTombstone = roundTrip(Value.tombstone());

        assertNotEquals(decodedEmpty, decodedTombstone, "an empty value and a tombstone must remain distinguishable after serialization, not just in memory");
    }

    @Test
    @DisplayName("T2.1: mutating the caller's array after construction does not affect the stored value")
    void constructorDefensivelyCopiesInputArray(){
        byte[] source = {1,2,3};
        Value value = Value.of(source);
        source[0] = 99;

        assertArrayEquals(new byte[]{1,2,3}, value.getData(), "stored payload must be unaffected by later mutation of the caller's array");
    }

    @Test
    @DisplayName("T2.1: mutating the array returned by getData() does not affect internal state")
    void getDataReturnsDefensiveCopy(){
        Value value = Value.of(new byte[]{1, 2, 3});

        byte[] leaked = value.getData();
        leaked[0] = 99;

        assertArrayEquals(new byte[]{1, 2, 3}, value.getData(),
                "a second call to getData() must return the original, unmutated bytes");
    }

    @Test
    @DisplayName("T2.1: two real values with different bytes are not equal")
    void realValuesWithDifferentBytesAreNotEqual(){
        Value a = Value.of(new byte[]{1, 2, 3});
        Value b = Value.of(new byte[]{1, 2, 4});

        assertNotEquals(a, b, "values with different payloads must not be equal");
    }
}
