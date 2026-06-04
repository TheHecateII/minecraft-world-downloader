package proxy.voicechat;

import java.net.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bidirectional UDP proxy: listens on localPort (localhost), forwards all packets to remoteHost:remotePort,
 * and relays responses back to the originating client address.
 *
 * This is used so that voice mod clients connecting to localhost can reach a remote voice server.
 */
public class UdpProxy {
    private static final int BUFFER_SIZE = 8192;
    // How long a per-client relay socket waits before timing out (and cleaning up) when the server goes quiet.
    private static final int SESSION_TIMEOUT_MS = 60_000;

    private final String remoteHost;
    private final int remotePort;
    private final int localPort;

    private volatile boolean running = false;
    private DatagramSocket listenSocket;

    // One outbound socket per client address, so the server can send responses back to us.
    private final Map<SocketAddress, DatagramSocket> sessions = new ConcurrentHashMap<>();

    public UdpProxy(String remoteHost, int remotePort, int localPort) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.localPort = localPort;
    }

    public void start() throws SocketException {
        listenSocket = new DatagramSocket(null);
        listenSocket.setReuseAddress(true);
        listenSocket.bind(new InetSocketAddress("127.0.0.1", localPort));
        running = true;

        Thread t = new Thread(this::listenFromClient, "voice-udp-proxy-in-" + localPort);
        t.setDaemon(true);
        t.start();

        System.out.println("[VoiceProxy] UDP proxy started: 127.0.0.1:" + localPort + " -> " + remoteHost + ":" + remotePort);
    }

    private void listenFromClient() {
        byte[] buf = new byte[BUFFER_SIZE];
        InetAddress remote;
        try {
            remote = InetAddress.getByName(remoteHost);
        } catch (UnknownHostException e) {
            System.err.println("[VoiceProxy] Cannot resolve voice server host '" + remoteHost + "': " + e.getMessage());
            return;
        }

        while (running) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                listenSocket.receive(pkt);

                SocketAddress clientAddr = pkt.getSocketAddress();
                DatagramSocket session = sessions.computeIfAbsent(clientAddr, addr -> {
                    try {
                        DatagramSocket s = new DatagramSocket();
                        s.setSoTimeout(SESSION_TIMEOUT_MS);
                        startRelayFromServer(s, addr);
                        return s;
                    } catch (SocketException e) {
                        throw new RuntimeException("[VoiceProxy] Cannot create relay socket: " + e.getMessage(), e);
                    }
                });

                // Forward client packet to the remote voice server.
                session.send(new DatagramPacket(pkt.getData(), pkt.getLength(), remote, remotePort));

            } catch (Exception e) {
                if (running) {
                    System.err.println("[VoiceProxy] Error in client listener: " + e.getMessage());
                }
            }
        }
    }

    private void startRelayFromServer(DatagramSocket session, SocketAddress clientAddr) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[BUFFER_SIZE];
            while (running) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    session.receive(pkt);

                    // Relay server response back to the Minecraft client.
                    listenSocket.send(new DatagramPacket(pkt.getData(), pkt.getLength(), clientAddr));

                } catch (SocketTimeoutException e) {
                    // Session idle for SESSION_TIMEOUT_MS — clean up.
                    sessions.remove(clientAddr);
                    session.close();
                    return;
                } catch (Exception e) {
                    if (running) {
                        System.err.println("[VoiceProxy] Error in server relay: " + e.getMessage());
                    }
                    return;
                }
            }
        }, "voice-udp-proxy-out-" + localPort);
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        if (listenSocket != null && !listenSocket.isClosed()) {
            listenSocket.close();
        }
        sessions.values().forEach(DatagramSocket::close);
        sessions.clear();
        System.out.println("[VoiceProxy] UDP proxy stopped: port " + localPort);
    }

    public int getLocalPort() {
        return localPort;
    }
}
