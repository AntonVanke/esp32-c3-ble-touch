package io.github.antonvanke.assistlab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;

public final class WirePackets {
    public static final int PORT = 38583;
    public static final int HEADER = 56;
    public static final int PAYLOAD = 1144;
    public static final int MAX_RAW = 16 * 1024 * 1024;
    public static final int MAX_COMPRESSED = 2 * 1024 * 1024;

    public static List<byte[]> encode(UUID device, UUID run, long sequence, byte[] json) throws IOException {
        if (json.length > MAX_RAW) throw new IOException("页面 JSON 超过 16 MiB");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) { gzip.write(json); }
        byte[] compressed = buffer.toByteArray();
        if (compressed.length > MAX_COMPRESSED) throw new IOException("压缩后的页面超过 2 MiB");
        CRC32 crc = new CRC32();
        crc.update(compressed);
        int count = (compressed.length + PAYLOAD - 1) / PAYLOAD;
        List<byte[]> packets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int offset = i * PAYLOAD;
            int size = Math.min(PAYLOAD, compressed.length - offset);
            ByteBuffer packet = ByteBuffer.allocate(HEADER + size);
            packet.putInt(0x41534c31);
            packet.putLong(device.getMostSignificantBits()).putLong(device.getLeastSignificantBits());
            packet.putLong(run.getMostSignificantBits()).putLong(run.getLeastSignificantBits());
            packet.putLong(sequence).putShort((short) i).putShort((short) count);
            packet.putInt(compressed.length).putInt((int) crc.getValue());
            packet.put(compressed, offset, size);
            packets.add(packet.array());
        }
        return packets;
    }
}
