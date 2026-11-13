package com.smeshariki.mod.versions;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Пакеты окна версий мира: действие клиента и список версий от сервера. */
public final class VersionsPayloads {
	private VersionsPayloads() {
	}

	private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String path) {
		return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("smesharikimod", path));
	}

	/** Действие над версиями мира: сохранить (text — название), откатить, удалить, переименовать (id версии). */
	public record VcsAction(int kind, int id, String text) implements CustomPacketPayload {
		public static final int COMMIT = 0, ROLLBACK = 1, DELETE = 2, RENAME = 3;
		public static final Type<VcsAction> TYPE = VersionsPayloads.typeOf("vcs_action");
		public static final StreamCodec<RegistryFriendlyByteBuf, VcsAction> CODEC = StreamCodec.ofMember(
				(VcsAction m, RegistryFriendlyByteBuf buf) -> {
					buf.writeVarInt(m.kind);
					buf.writeVarInt(m.id);
					buf.writeUtf(m.text, 64);
				}, buf -> new VcsAction(buf.readVarInt(), buf.readVarInt(), buf.readUtf(64)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Список версий мира (сервер → клиент): {@code live} — откат идёт без остановки мира, {@code busy} — откат сейчас идёт. */
	public record Versions(int head, boolean live, boolean busy, java.util.List<Info> list) implements CustomPacketPayload {
		public record Info(int id, String name, long time, String author, int changed, int files, long bytes) {
		}

		public static final Type<Versions> TYPE = VersionsPayloads.typeOf("versions");
		public static final StreamCodec<RegistryFriendlyByteBuf, Versions> CODEC = StreamCodec.ofMember((Versions m, RegistryFriendlyByteBuf buf) -> {
			buf.writeVarInt(m.head);
			buf.writeBoolean(m.live);
			buf.writeBoolean(m.busy);
			buf.writeVarInt(m.list.size());
			for (Info i : m.list) {
				buf.writeVarInt(i.id);
				buf.writeUtf(i.name, 64);
				buf.writeLong(i.time);
				buf.writeUtf(i.author, 64);
				buf.writeVarInt(i.changed);
				buf.writeVarInt(i.files);
				buf.writeLong(i.bytes);
			}
		}, buf -> {
			int head = buf.readVarInt();
			boolean live = buf.readBoolean(), busy = buf.readBoolean();
			int n = Math.min(buf.readVarInt(), 4096);
			java.util.List<Info> list = new java.util.ArrayList<>();
			for (int k = 0; k < n; k++) list.add(new Info(buf.readVarInt(), buf.readUtf(64), buf.readLong(), buf.readUtf(64), buf.readVarInt(), buf.readVarInt(), buf.readLong()));
			return new Versions(head, live, busy, list);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(VcsAction.TYPE, VcsAction.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Versions.TYPE, Versions.CODEC);
	}
}
