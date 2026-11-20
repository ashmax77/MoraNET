package campusnet.client.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetInfoTest {

    @Test
    @DisplayName("formatMac returns n/a when hardware address is null or empty")
    void testFormatMacNullOrEmpty() {
        assertEquals("n/a", NetInfo.formatMac(null));
        assertEquals("n/a", NetInfo.formatMac(new byte[0]));
    }

    @Test
    @DisplayName("formatMac formats standard 6-byte MAC address into uppercase colon-separated string")
    void testFormatMacStandard6Bytes() {
        byte[] mac = new byte[]{(byte) 0x00, (byte) 0x1A, (byte) 0x2B, (byte) 0x3C, (byte) 0x4D, (byte) 0x5E};
        assertEquals("00:1A:2B:3C:4D:5E", NetInfo.formatMac(mac));
    }

    @Test
    @DisplayName("formatMac handles signed negative bytes properly (e.g. 0xFF, 0xAA)")
    void testFormatMacNegativeBytes() {
        byte[] mac = new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD, (byte) 0xEE, (byte) 0xFF};
        assertEquals("AA:BB:CC:DD:EE:FF", NetInfo.formatMac(mac));
    }

    @Test
    @DisplayName("print runs without throwing exception and produces output to PrintStream")
    void testPrintRunsSuccessfully() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);

        NetInfo netInfo = new NetInfo();
        netInfo.print(ps);

        String output = baos.toString(StandardCharsets.UTF_8);
        assertNotNull(output);
        assertFalse(output.isBlank(), "NetInfo should print output");
    }
}
