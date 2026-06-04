package proxy.voicechat;

import config.Config;
import packets.DataTypeProvider;
import packets.builder.PacketBuilder;

import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Handles voice-mod plugin channels (Simple Voice Chat, PlasmoVoice).
 *
 * When a voice server info packet is intercepted:
 *  - A UDP proxy is started on localhost:<voicePort> forwarding to realServer:<voicePort>.
 *  - If the packet advertises a non-local host, it is rewritten to advertise an empty host so the
 *    Minecraft client falls back to using the Minecraft server address (= localhost = our proxy).
 *
 * This lets clients behind the world-downloader MITM proxy reach voice servers that would otherwise
 * only be reachable via UDP directly to the remote server's IP.
 */
public class VoiceProxyManager {

    // Simple Voice Chat channel names
    private static final String SVC_PLAYER_STATE   = "voicechat:player_state";
    // PlasmoVoice 2.x main channel
    private static final String PV2_CHANNEL        = "plasmo:voice/v2";
    // PlasmoVoice 2.x sub-packet type for server info
    private static final byte   PV2_SERVER_INFO_ID = 0x00;

    private static VoiceProxyManager instance;

    private final Map<Integer, UdpProxy> proxies = new ConcurrentHashMap<>();

    public static VoiceProxyManager getInstance() {
        if (instance == null) {
            instance = new VoiceProxyManager();
        }
        return instance;
    }

    /**
     * Called for every CustomPayload packet received from the server.
     *
     * @param channel  the plugin channel identifier (already consumed from the provider)
     * @param provider remaining payload bytes
     * @return true  → forward the original packet unchanged
     *         false → drop the original (a replacement has been injected, or it was silently swallowed)
     */
    public boolean handleChannel(String channel, DataTypeProvider provider) {
        if (channel.startsWith("voicechat:")) {
            return handleSimpleVoiceChat(channel, provider);
        }
        if (channel.startsWith("plasmo:voice")) {
            return handlePlasmoVoice(channel, provider);
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Simple Voice Chat
    // -------------------------------------------------------------------------

    /**
     * Simple Voice Chat sends {@code voicechat:player_state} from the server when a player joins.
     *
     * Wire format (after the channel identifier string):
     *   UUID   secret         (2 × long: MSB, LSB)
     *   String host           (empty = use the Minecraft server address)
     *   int    port           (UDP port of the voice server)
     *   …      remaining data (flags, codec settings, etc. — copied verbatim)
     */
    private boolean handleSimpleVoiceChat(String channel, DataTypeProvider provider) {
        if (!channel.equals(SVC_PLAYER_STATE)) {
            return true;
        }

        try {
            long secretMsb  = provider.readLong();
            long secretLsb  = provider.readLong();
            String host     = provider.readString();
            int port        = provider.readInt();
            byte[] tail     = provider.readByteArray(provider.remaining());

            String remoteHost = resolveVoiceHost(host);
            startProxy(remoteHost, port);

            if (!host.isEmpty() && !isLocalhost(host)) {
                // The packet names a specific external host. Rewrite it so the client uses localhost
                // (= our UDP proxy) instead of trying to reach the external host directly.
                PacketBuilder modified = buildCustomPayload(channel, b -> {
                    b.writeLong(secretMsb);
                    b.writeLong(secretLsb);
                    b.writeString("");   // empty → client uses Minecraft connection address = localhost
                    b.writeInt(port);
                    b.writeByteArray(tail);
                });
                Config.getPacketInjector().enqueuePacket(modified);
                return false;
            }

            return true;

        } catch (Exception e) {
            System.err.println("[VoiceProxy] Failed to parse SimpleVoiceChat player_state: " + e.getMessage());
            return true;
        }
    }

    // -------------------------------------------------------------------------
    // PlasmoVoice 2.x
    // -------------------------------------------------------------------------

    /**
     * PlasmoVoice 2.x uses the {@code plasmo:voice/v2} channel.
     *
     * All sub-packets start with a VarInt type identifier.  We only care about the server-info packet
     * (type 0x00) which has the following wire format:
     *   VarInt packetType     (0x00 = server info)
     *   UUID   token          (2 × long: MSB, LSB)
     *   String host           (empty = use the Minecraft server address)
     *   int    port           (UDP port of the voice server)
     *   …      remaining data (codec, flags, etc. — copied verbatim)
     */
    private boolean handlePlasmoVoice(String channel, DataTypeProvider provider) {
        if (!channel.equals(PV2_CHANNEL)) {
            return true;
        }

        try {
            int typeId = provider.readVarInt();

            if (typeId != PV2_SERVER_INFO_ID) {
                return true;
            }

            long tokenMsb = provider.readLong();
            long tokenLsb = provider.readLong();
            String host   = provider.readString();
            int port      = provider.readInt();
            byte[] tail   = provider.readByteArray(provider.remaining());

            String remoteHost = resolveVoiceHost(host);
            startProxy(remoteHost, port);

            if (!host.isEmpty() && !isLocalhost(host)) {
                PacketBuilder modified = buildCustomPayload(channel, b -> {
                    b.writeVarInt(typeId);
                    b.writeLong(tokenMsb);
                    b.writeLong(tokenLsb);
                    b.writeString("");
                    b.writeInt(port);
                    b.writeByteArray(tail);
                });
                Config.getPacketInjector().enqueuePacket(modified);
                return false;
            }

            return true;

        } catch (Exception e) {
            System.err.println("[VoiceProxy] Failed to parse PlasmoVoice v2 packet: " + e.getMessage());
            return true;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String resolveVoiceHost(String host) {
        if (host.isEmpty() || isLocalhost(host)) {
            return Config.getConnectionDetails().getHost();
        }
        return host;
    }

    private void startProxy(String remoteHost, int port) {
        proxies.computeIfAbsent(port, p -> {
            UdpProxy proxy = new UdpProxy(remoteHost, p, p);
            try {
                proxy.start();
            } catch (SocketException e) {
                System.err.println("[VoiceProxy] Cannot start UDP proxy on port " + p + ": " + e.getMessage());
            }
            return proxy;
        });
    }

    private PacketBuilder buildCustomPayload(String channel, Consumer<PacketBuilder> writer) {
        PacketBuilder builder = new PacketBuilder("CustomPayload");
        builder.writeString(channel);
        writer.accept(builder);
        return builder;
    }

    private boolean isLocalhost(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    }

    /** Stop all active UDP proxies (called on disconnect). */
    public void reset() {
        proxies.values().forEach(UdpProxy::stop);
        proxies.clear();
    }
}
