package io.github.antonvanke.assistlab;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class ScreenFixture {
    public static void main(String[] args) throws Exception {
        UUID device = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID session = UUID.fromString("00000000-0000-0000-0000-000000000003");
        byte[] jpeg = Files.readAllBytes(Path.of(args[1]));
        if (args[0].equals("encode")) {
            Files.write(Path.of(args[2]), ScreenWire.encode(device, session, 7, 1700000000123L, 1080, 2400, 360, 800, jpeg));
        } else {
            try (ScreenServer server = new ScreenServer()) {
                System.out.println("READY");
                System.out.flush();
                for (int i = 1; i <= 160; i++) {
                    server.publish(ScreenWire.encode(device, session, i, System.currentTimeMillis(), 1080, 2400, 360, 800, jpeg));
                    Thread.sleep(50);
                }
            }
        }
    }
}
