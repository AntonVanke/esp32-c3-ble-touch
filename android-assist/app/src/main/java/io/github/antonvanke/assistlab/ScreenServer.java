package io.github.antonvanke.assistlab;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class ScreenServer implements AutoCloseable {
    private final ServerSocket server;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile byte[] latest;

    ScreenServer() throws IOException {
        server = new ServerSocket(ScreenWire.PORT);
        server.setSoTimeout(500);
        new Thread(this::accept, "screen-listener").start();
    }

    int clients() { return clients.size(); }

    void publish(byte[] frame) {
        if (closed) return;
        latest = frame;
        for (Client client : clients) client.offer(frame);
    }

    private void accept() {
        while (!closed) {
            try {
                Socket socket = server.accept();
                if (closed || clients.size() >= 2) { socket.close(); continue; }
                socket.setTcpNoDelay(true);
                socket.setSendBufferSize(65536);
                Client client = new Client(socket);
                clients.add(client);
                client.offer(latest);
                new Thread(client, "screen-client").start();
            } catch (SocketTimeoutException ignored) {
            } catch (IOException e) { if (!closed) close(); }
            for (Client client : clients) {
                if (client.writingSince != 0 && System.nanoTime() - client.writingSince > 1_500_000_000L)
                    client.close();
            }
        }
    }

    @Override public void close() {
        closed = true;
        latest = null;
        try { server.close(); } catch (IOException ignored) { }
        for (Client client : clients) client.close();
    }

    private final class Client implements Runnable {
        final Socket socket;
        byte[] pending;
        boolean ended;
        volatile long writingSince;
        Client(Socket socket) { this.socket = socket; }
        synchronized void offer(byte[] frame) { if (!ended) { pending = frame; notifyAll(); } }
        synchronized void close() {
            ended = true;
            pending = null;
            notifyAll();
            try { socket.close(); } catch (IOException ignored) { }
            clients.remove(this);
        }
        @Override public void run() {
            try {
                while (!closed) {
                    byte[] frame;
                    synchronized (this) {
                        while (pending == null && !ended && !closed) wait();
                        if (ended || closed) break;
                        frame = pending;
                        pending = null;
                    }
                    writingSince = System.nanoTime();
                    socket.getOutputStream().write(frame);
                    writingSince = 0;
                }
            } catch (IOException | InterruptedException ignored) {
            } finally { close(); }
        }
    }
}
