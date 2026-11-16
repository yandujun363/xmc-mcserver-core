package xmc.lib.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The two custom payload channels XMC uses in the configuration state.
 *
 * <p>The data channel carries the tunnel byte stream: exactly the channel Xray
 * writes and reads. The padding channel only carries the startup padding frames
 * and is never answered; vanilla drops both channels when no receiver is
 * registered, which is what keeps an ordinary client on the vanilla path.
 *
 * <p>Both channel ids are configuration values, so the defaults below only
 * apply until {@link #register(String, String)} runs. A fixed channel name is a
 * fingerprint, so deployments are expected to replace both.
 */
public final class XmcPayloads {
    private static final String DEFAULT_DATA_CHANNEL = "xmc:data";
    private static final String DEFAULT_PADDING_CHANNEL = "xmc:pad";

    private static volatile Identifier dataChannel = Identifier.parse(DEFAULT_DATA_CHANNEL);
    private static volatile Identifier paddingChannel = Identifier.parse(DEFAULT_PADDING_CHANNEL);

    private static volatile CustomPacketPayload.Type<Data> dataType =
            new CustomPacketPayload.Type<>(dataChannel);
    private static volatile CustomPacketPayload.Type<Padding> paddingType =
            new CustomPacketPayload.Type<>(paddingChannel);

    public static final StreamCodec<FriendlyByteBuf, Data> DATA_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeBytes(payload.data()),
            XmcPayloads::readData);

    public static final StreamCodec<FriendlyByteBuf, Padding> PADDING_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeBytes(payload.data()),
            XmcPayloads::readPadding);

    private XmcPayloads() {
    }

    /**
     * Registers both payload types under the configured channel ids.
     *
     * <p>Must run once from the mod initializer, before any connection exists:
     * {@code PayloadTypeRegistry} has no unregister and rejects a duplicate id
     * with {@code IllegalArgumentException}. The caller validates the ids and
     * turns a failure here into a disabled XMC instead of a startup crash.
     */
    public static void register(String dataChannelId, String paddingChannelId) {
        Identifier data = Identifier.parse(dataChannelId);
        Identifier padding = Identifier.parse(paddingChannelId);
        CustomPacketPayload.Type<Data> newDataType = new CustomPacketPayload.Type<>(data);
        CustomPacketPayload.Type<Padding> newPaddingType = new CustomPacketPayload.Type<>(padding);

        PayloadTypeRegistry.serverboundConfiguration().register(newDataType, DATA_CODEC);
        PayloadTypeRegistry.clientboundConfiguration().register(newDataType, DATA_CODEC);
        PayloadTypeRegistry.clientboundConfiguration().register(newPaddingType, PADDING_CODEC);

        dataChannel = data;
        paddingChannel = padding;
        dataType = newDataType;
        paddingType = newPaddingType;
    }

    public static Identifier dataChannel() {
        return dataChannel;
    }

    public static Identifier paddingChannel() {
        return paddingChannel;
    }

    public static CustomPacketPayload.Type<Data> dataType() {
        return dataType;
    }

    public static CustomPacketPayload.Type<Padding> paddingType() {
        return paddingType;
    }

    private static Data readData(FriendlyByteBuf buffer) {
        byte[] data = new byte[buffer.readableBytes()];
        buffer.readBytes(data);
        return new Data(data);
    }

    private static Padding readPadding(FriendlyByteBuf buffer) {
        byte[] data = new byte[buffer.readableBytes()];
        buffer.readBytes(data);
        return new Padding(data);
    }

    /** Tunnel bytes, split by the sender so that one payload stays small. */
    public record Data(byte[] data) implements CustomPacketPayload {
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return dataType;
        }
    }

    /** One startup-padding frame. The payload bytes are opaque filler. */
    public record Padding(byte[] data) implements CustomPacketPayload {
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return paddingType;
        }
    }
}
