package xmc.lib.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The two custom payload channels XMC uses in the configuration state.
 *
 * <p>{@code xmc:data} carries the tunnel byte stream: exactly the channel Xray
 * writes and reads. {@code xmc:pad} only carries the startup padding frames and
 * is never answered; vanilla drops both channels when no receiver is registered,
 * which is what keeps an ordinary client on the vanilla path.
 */
public final class XmcPayloads {
    public static final Identifier DATA_CHANNEL = Identifier.fromNamespaceAndPath("xmc", "data");
    public static final Identifier PADDING_CHANNEL = Identifier.fromNamespaceAndPath("xmc", "pad");

    public static final CustomPacketPayload.Type<Data> DATA_TYPE = new CustomPacketPayload.Type<>(DATA_CHANNEL);
    public static final CustomPacketPayload.Type<Padding> PADDING_TYPE = new CustomPacketPayload.Type<>(PADDING_CHANNEL);

    public static final StreamCodec<FriendlyByteBuf, Data> DATA_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeBytes(payload.data()),
            XmcPayloads::readData);

    public static final StreamCodec<FriendlyByteBuf, Padding> PADDING_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeBytes(payload.data()),
            XmcPayloads::readPadding);

    private XmcPayloads() {
    }

    public static void register() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundConfiguration()
                .register(DATA_TYPE, DATA_CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundConfiguration()
                .register(DATA_TYPE, DATA_CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundConfiguration()
                .register(PADDING_TYPE, PADDING_CODEC);
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
            return DATA_TYPE;
        }
    }

    /** One startup-padding frame. The payload bytes are opaque filler. */
    public record Padding(byte[] data) implements CustomPacketPayload {
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return PADDING_TYPE;
        }
    }
}
