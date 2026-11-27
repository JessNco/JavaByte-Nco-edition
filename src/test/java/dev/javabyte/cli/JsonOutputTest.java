package dev.javabyte.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** JSON on standard output is UTF-8 even where the platform's charset is not. */
class JsonOutputTest {

    @Test
    void jsonKeepsCharactersThePlatformCharsetLacks() {
        String json = "{\"password\":\"pässή\"}";
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        // As where no locale is set: text printed here turns every other character into '?'.
        System.setOut(new PrintStream(captured, true, StandardCharsets.US_ASCII));
        try {
            Main.printJson(json);
        } finally {
            System.setOut(original);
        }
        assertArrayEquals((json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8), captured.toByteArray());
    }
}
