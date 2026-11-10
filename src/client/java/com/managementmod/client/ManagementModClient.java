package com.managementmod.client;

import org.lwjgl.glfw.GLFW;

import com.managementmod.ManagementMod;
import com.managementmod.versions.VersionsPayloads;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class ManagementModClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ManagementMod.ID, "main"));
	/** Окно версий мира. Имя ключа новое (раньше {@code key.buildmod.versions} в строителе); в выживании окно не открывается. */
	private static final KeyMapping VERSIONS = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + ManagementMod.ID + ".versions", GLFW.GLFW_KEY_V, CATEGORY));

	@Override
	public void onInitializeClient() {
		SyncClient.init();
		LanSettings.init();
		ClientTickEvents.END_CLIENT_TICK.register(LanSettings::tick);
		ClientPlayNetworking.registerGlobalReceiver(VersionsPayloads.Versions.TYPE, (payload, ctx) -> {
			VersionsClient.INSTANCE.head = payload.head();
			VersionsClient.INSTANCE.versions = payload.list();
		});
		ClientTickEvents.END_CLIENT_TICK.register(ManagementModClient::keys);
	}

	private static void keys(Minecraft mc) {
		if (mc.player == null || mc.level == null) return;
		while (VERSIONS.consumeClick()) {
			if (mc.gui.screen() != null) continue;
			if (!mc.player.isCreative()) {
				mc.gui.hud.setOverlayMessage(Component.literal("Версии мира — только в творческом режиме"), false);
				continue;
			}
			var srv = mc.getSingleplayerServer();
			// гость сети тоже откроет окно, но сервер ему откажет
			if (srv == null || srv.isSingleplayerOwner(mc.player.nameAndId())) mc.setScreenAndShow(new VersionsScreen(VersionsClient.INSTANCE));
			else mc.gui.hud.setOverlayMessage(Component.literal("Версии мира — только у хоста"), false);
		}
	}
}
