package io.github.antonvanke.assistlab;

import java.nio.ByteBuffer;
import java.util.UUID;

final class ScreenWire {
    static final int PORT = 38584, HEADER = 68, MAX_FRAME = 2 * 1024 * 1024;

    static byte[] encode(UUID device, UUID session, long sequence, long timestamp,
                         int sourceWidth, int sourceHeight, int width, int height, byte[] jpeg) {
        if (jpeg.length + HEADER > MAX_FRAME) throw new IllegalArgumentException("截图过大");
        return ByteBuffer.allocate(4 + HEADER + jpeg.length).putInt(HEADER + jpeg.length)
            .put(new byte[]{'A', 'S', 'V', '1'})
            .putLong(device.getMostSignificantBits()).putLong(device.getLeastSignificantBits())
            .putLong(session.getMostSignificantBits()).putLong(session.getLeastSignificantBits())
            .putLong(sequence).putLong(timestamp).putInt(sourceWidth).putInt(sourceHeight)
            .putInt(width).putInt(height).put(jpeg).array();
    }
}
