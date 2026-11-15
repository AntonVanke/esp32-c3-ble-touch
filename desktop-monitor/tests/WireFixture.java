import io.github.antonvanke.assistlab.WirePackets;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class WireFixture {
    public static void main(String[] args) throws Exception {
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(Path.of(args[4])))) {
            for (byte[] packet : WirePackets.encode(UUID.fromString(args[0]), UUID.fromString(args[1]),
                    Long.parseLong(args[2]), Files.readAllBytes(Path.of(args[3])))) {
                out.writeInt(packet.length);
                out.write(packet);
            }
        }
    }
}
