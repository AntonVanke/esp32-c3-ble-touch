package io.github.antonvanke.assistlab;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class LanBroadcaster implements Closeable {
    private DatagramSocket socket;
    private boolean closed;
    private final UUID device, run;
    volatile String destination = "等待广播";

    LanBroadcaster(UUID device, UUID run) { this.device = device; this.run = run; }

    void send(long sequence, byte[] json) throws IOException {
        List<byte[]> packets = WirePackets.encode(device, run, sequence, json);
        Set<InetAddress> destinations = new LinkedHashSet<>();
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback() || network.isPointToPoint()) continue;
            for (InterfaceAddress address : network.getInterfaceAddresses()) {
                if (address.getAddress() instanceof Inet4Address && address.getBroadcast() != null
                    && address.getAddress().isSiteLocalAddress()) destinations.add(address.getBroadcast());
            }
        }
        if (destinations.isEmpty()) throw new IOException("未发现可广播的 IPv4 局域网，请连接 Wi-Fi");
        DatagramSocket sender;
        synchronized (this) {
            if (closed) throw new IOException("广播已停止");
            if (socket == null) { socket = new DatagramSocket(); socket.setBroadcast(true); }
            sender = socket;
        }
        destination = destinations.toString() + ":" + WirePackets.PORT;
        for (int i = 0; i < packets.size(); i++) {
            byte[] bytes = packets.get(i);
            for (InetAddress target : destinations) sender.send(new DatagramPacket(bytes, bytes.length, target, WirePackets.PORT));
            if (i % 8 == 7) {
                try { Thread.sleep(2); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("广播中断", e); }
            }
        }
    }

    @Override public synchronized void close() {
        closed = true;
        if (socket != null) socket.close();
    }
}
