package com.smeshariki.mod.net;

import com.smeshariki.mod.sync.FileEntry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Сервер → клиент: список файлов, которые хост готов отдать. */
public record ManifestPayload(int protocol, List<FileEntry> files) implements CustomPacketPayload {
	public static final int PROTOCOL = 1;
	public static final int MAX_FILES = 4096;
	public static final Type<ManifestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("smesharikimod", "manifest"));
	public static final StreamCodec<FriendlyByteBuf, ManifestPayload> CODEC =
		CustomPacketPayload.codec(ManifestPayload::write, ManifestPayload::new);

	private ManifestPayload(FriendlyByteBuf buf) {
		this(buf.readVarInt(), readFiles(buf));
	}

	private static List<FileEntry> readFiles(FriendlyByteBuf buf) {
		int count = buf.readVarInt();
		if (count < 0 || count > MAX_FILES) {
			throw new IllegalArgumentException("Слишком много файлов в манифесте: " + count);
		}
		List<FileEntry> files = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			files.add(FileEntry.read(buf));
		}
		return files;
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(protocol);
		buf.writeVarInt(files.size());
		files.forEach(file -> file.write(buf));
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
