package com.installermod.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Клиент → сервер: «с файлами закончил (принял, отказался или нечего скачивать), продолжай вход». */
public record DonePayload() implements CustomPacketPayload {
	public static final Type<DonePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("installermod", "done"));
	public static final StreamCodec<FriendlyByteBuf, DonePayload> CODEC = StreamCodec.unit(new DonePayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
