package com.smeshariki.mod.client;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.smeshariki.mod.versions.VersionsPayloads;
import com.smeshariki.ui.ListScreen;
import com.smeshariki.ui.Ui;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * Версии мира («упрощённый git»): сохранить снимок мира под названием, откатиться к версии, переименовать, удалить.
 * Данные живут на сервере; откат идёт без остановки мира (если подключён движок LevelMod), иначе закрывает мир и заменяет файлы после остановки.
 */
final class VersionsScreen extends ListScreen {
	private static final int ROW = 22, LIST_W = 320;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

	private final VersionsClient state;
	private EditBox nameBox;
	private int selected = -1; // номер версии
	private boolean renaming, confirmRollback, confirmDelete;
	private java.util.List<?> shown;

	VersionsScreen(VersionsClient state) {
		super(Component.translatable("gui.smesharikimod.versions"));
		this.state = state;
		if (!state.versions.isEmpty()) selected = state.versions.get(state.versions.size() - 1).id();
	}

	/** Меню версий ставит игру на паузу, как и раньше. */
	@Override
	protected boolean pausesGame() {
		return true;
	}

	private VersionsPayloads.Versions.Info current() {
		for (var v : state.versions) if (v.id() == selected) return v;
		return null;
	}

	@Override
	protected void init() {
		shown = state.versions;
		int cx = width / 2;
		int bottom = height - 30;
		int top = 56;
		int visible = Math.max(2, (bottom - 60 - top) / ROW);
		int total = state.versions.size();
		bar.layout(cx + LIST_W / 2 + 3, top, visible * ROW - 2, total, visible);
		if (bar.scroll > Math.max(0, total - visible)) bar.scroll = Math.max(0, total - visible);

		// сверху вниз — от новых к старым
		for (int row = 0; row < visible && bar.scroll + row < total; row++) {
			var v = state.versions.get(total - 1 - (bar.scroll + row));
			String label = (v.id() == selected ? "▶ " : "") + (v.id() == state.head ? "● " : "") + "№" + v.id() + " " + v.name() + " · " + TIME.format(Instant.ofEpochMilli(v.time()));
			addRenderableWidget(Button.builder(Component.literal(label), x -> {
				selected = v.id();
				renaming = confirmRollback = confirmDelete = false;
				rebuildWidgets();
			}).bounds(cx - LIST_W / 2, top + row * ROW, LIST_W, 20).build()).active = !renaming;
		}

		// поле названия новой версии
		nameBox = new EditBox(font, cx - LIST_W / 2, 28, LIST_W - 130, 20, Component.translatable("gui.smesharikimod.versions.name"));
		nameBox.setMaxLength(48);
		nameBox.setHint(Component.translatable(renaming ? "gui.smesharikimod.versions.rename_hint" : "gui.smesharikimod.versions.new_hint"));
		var cur = current();
		if (renaming && cur != null) nameBox.setValue(cur.name());
		addRenderableWidget(nameBox);
		if (renaming) {
			setInitialFocus(nameBox);
			addRenderableWidget(Button.builder(Component.translatable("gui.uilib.ok"), b -> applyRename()).bounds(cx + LIST_W / 2 - 124, 28, 124, 20).build());
		} else {
			addRenderableWidget(Button.builder(Component.translatable("gui.smesharikimod.versions.commit"), b -> commit()).bounds(cx + LIST_W / 2 - 124, 28, 124, 20).build());
		}

		int rowA = bottom - 26;
		boolean has = cur != null && !renaming && !state.busy;
		addAction(cx - 160, rowA, 100, Component.translatable(confirmRollback ? "gui.smesharikimod.versions.rollback_sure" : "gui.smesharikimod.versions.rollback"), has, () -> {
			if (!confirmRollback) {
				confirmRollback = true;
				confirmDelete = false;
				rebuildWidgets();
				return;
			}
			ClientPlayNetworking.send(new VersionsPayloads.VcsAction(VersionsPayloads.VcsAction.ROLLBACK, selected, ""));
			onClose();
		});
		addAction(cx - 50, rowA, 100, Component.translatable("gui.smesharikimod.versions.rename"), has, () -> {
			renaming = true;
			confirmRollback = confirmDelete = false;
			rebuildWidgets();
		});
		addAction(cx + 60, rowA, 100, Component.translatable(confirmDelete ? "gui.smesharikimod.versions.delete_sure" : "gui.uilib.delete"), has, () -> {
			if (!confirmDelete) {
				confirmDelete = true;
				confirmRollback = false;
				rebuildWidgets();
				return;
			}
			ClientPlayNetworking.send(new VersionsPayloads.VcsAction(VersionsPayloads.VcsAction.DELETE, selected, ""));
			confirmDelete = false;
			rebuildWidgets();
		});
		addAction(cx - 160, bottom, 155, Component.translatable("gui.smesharikimod.versions.export"), !renaming && !state.busy, this::exportWorld);
		addAction(cx + 5, bottom, 155, Component.translatable("gui.uilib.close"), true, this::onClose);
	}

	private void addAction(int x, int y, int w, Component label, boolean active, Runnable action) {
		addButton(label, x, y, w, action).active = active;
	}

	private void commit() {
		ClientPlayNetworking.send(new VersionsPayloads.VcsAction(VersionsPayloads.VcsAction.COMMIT, 0, nameBox.getValue()));
		nameBox.setValue("");
		setFocused(null);
		net.minecraft.client.Minecraft.getInstance().gui.hud.setOverlayMessage(Component.translatable("gui.smesharikimod.versions.saving"), false);
	}

	/** Выгрузить чистую копию мира в zip для раздачи (сервер сам сохраняет мир и пишет итог в чат). */
	private void exportWorld() {
		ClientPlayNetworking.send(new VersionsPayloads.VcsAction(VersionsPayloads.VcsAction.EXPORT, 0, ""));
		net.minecraft.client.Minecraft.getInstance().gui.hud.setOverlayMessage(Component.translatable("gui.smesharikimod.export.working"), false);
		onClose();
	}

	private void applyRename() {
		if (current() != null && !nameBox.getValue().isBlank())
			ClientPlayNetworking.send(new VersionsPayloads.VcsAction(VersionsPayloads.VcsAction.RENAME, selected, nameBox.getValue()));
		renaming = false;
		rebuildWidgets();
	}

	@Override
	public void tick() {
		super.tick();
		if (shown != state.versions && !isEditing()) {
			var before = shown;
			// после сохранения выделяем новую версию
			if (before != null && state.versions.size() > before.size() && !state.versions.isEmpty()) selected = state.versions.get(state.versions.size() - 1).id();
			if (current() == null) selected = -1;
			bar.scroll = 0;
			rebuildWidgets();
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == 257 || event.key() == 335) { // Enter
			if (renaming) applyRename();
			else commit();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.centeredText(font, title, width / 2, 12, Ui.TEXT);
		if (state.versions.isEmpty()) drawNote(g, Component.translatable("gui.smesharikimod.versions.empty"), 70);
		var v = current();
		if (v != null) {
			Component info = Component.translatable("gui.smesharikimod.versions.info", v.changed(), v.files(), String.format("%.1f", v.bytes() / 1048576.0));
			if (!v.author().isEmpty()) info = info.copy().append(" · " + v.author());
			if (v.id() == state.head) info = info.copy().append(Component.translatable("gui.smesharikimod.versions.current"));
			g.centeredText(font, info, width / 2, height - 78, Ui.MUTED);
			if (confirmRollback) g.centeredText(font, Component.translatable(state.live ? "gui.smesharikimod.versions.rollback_live" : "gui.smesharikimod.versions.rollback_close"), width / 2, height - 90, Ui.ACCENT);
			if (state.busy) g.centeredText(font, Component.translatable("gui.smesharikimod.versions.busy"), width / 2, height - 102, Ui.ACCENT);
		}
	}
}
