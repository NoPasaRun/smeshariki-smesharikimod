package com.managementmod.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Клиент → сервер: «пришли кусок файла №index начиная со смещения offset». */
public record RequestPayload(int index, long offset) implements CustomPacketPayload {
	public static final Type<RequestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("managementmod", "request"));
	public static final StreamCodec<FriendlyByteBuf, RequestPayload> CODEC =
		CustomPacketPayload.codec(RequestPayload::write, RequestPayload::new);

	private RequestPayload(FriendlyByteBuf buf) {
		this(buf.readVarInt(), buf.readLong());
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(index);
		buf.writeLong(offset);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
