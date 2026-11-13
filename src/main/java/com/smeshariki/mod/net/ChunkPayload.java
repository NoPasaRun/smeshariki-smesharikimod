package com.smeshariki.mod.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Сервер → клиент: кусок файла. {@code offset == -1} означает «файл недоступен». */
public record ChunkPayload(int index, long offset, byte[] data) implements CustomPacketPayload {
	/** Размер куска. Лимит пакета сервер→клиент в Minecraft — 1 МиБ, берём с запасом. */
	public static final int CHUNK_SIZE = 256 * 1024;
	public static final Type<ChunkPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("smesharikimod", "chunk"));
	public static final StreamCodec<FriendlyByteBuf, ChunkPayload> CODEC =
		CustomPacketPayload.codec(ChunkPayload::write, ChunkPayload::new);

	private ChunkPayload(FriendlyByteBuf buf) {
		this(buf.readVarInt(), buf.readLong(), buf.readByteArray(CHUNK_SIZE));
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(index);
		buf.writeLong(offset);
		buf.writeByteArray(data);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
