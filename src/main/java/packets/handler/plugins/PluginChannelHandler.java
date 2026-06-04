package packets.handler.plugins;

import config.Config;
import config.Option;
import config.Version;
import packets.DataTypeProvider;
import proxy.voicechat.VoiceProxyManager;

public abstract class PluginChannelHandler {

    private static PluginChannelHandler instance;

    public static PluginChannelHandler getInstance() {
        if (instance == null) {
            instance = Config.versionReporter().select(PluginChannelHandler.class,
                   Option.of(Version.V1_12, PluginChannelHandler1_12::new),
                   Option.of(Version.ANY, DefaultPluginChannelHandler::new)
            );
        }
        return instance;
    }

    /**
     * Handle an incoming CustomPayload packet from the server.
     *
     * @return true  → the original packet should be forwarded to the client unchanged
     *         false → the original packet should be dropped (a replacement may have been injected)
     */
    public abstract boolean handleCustomPayload(DataTypeProvider provider);

    public static void reset() {
        instance = null;
    }
}

class DefaultPluginChannelHandler extends PluginChannelHandler {
    @Override
    public boolean handleCustomPayload(DataTypeProvider provider) {
        String channel = provider.readString();
        return VoiceProxyManager.getInstance().handleChannel(channel, provider);
    }
}
