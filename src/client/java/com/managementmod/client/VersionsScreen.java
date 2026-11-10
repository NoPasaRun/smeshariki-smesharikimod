package com.managementmod.client;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.managementmod.versions.VersionsPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * Версии мира («упрощённый git»): сохранить снимок мира под названием, откатиться к версии, переименовать, удалить.
 * Данные живут на сервере; откат закрывает мир (файлы заменяются после остановки сервера).
 */
final class VersionsScreen extends net.minecraft.client.gui.screens.Screen {
	private static final int ROW = 22, LIST_W = 320;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

	private final VersionsClient state;
	private final Scroller bar = new Scroller();
	private EditBox nameBox;
	private int selected = -1; // номер версии
	private boolean renaming, confirmRollback, confirmDelete;
	private java.util.List<?> shown;

	VersionsScreen(VersionsClient state) {
		super(Component.literal("Версии мира"));
		this.state = state;
		if (!state.versions.isEmpty()) selected = state.versions.get(state.versions.size() - 1).id();
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
		nameBox = new EditBox(font, cx - LIST_W / 2, 28, LIST_W - 130, 20, Component.literal("Название"));
		nameBox.setMaxLength(48);
		nameBox.setHint(Component.literal(renaming ? "Новое название" : "Название новой версии"));
		var cur = current();
		if (renaming && cur != null) nameBox.setValue(cur.name());
		addRenderableWidget(nameBox);
		if (renaming) {
			setInitialFocus(nameBox);
			addRenderableWidget(Button.builder(Component.literal("OK"), b -> applyRename()).bounds(cx + LIST_W / 2 - 124, 28, 124, 20).build());
		} else {
			addRenderableWidget(Button.builder(Component.literal("Сохранить версию"), b -> commit()).bounds(cx + LIST_W / 2 - 124, 28, 124, 20).build());
		}

		int rowA = bottom - 26;
		boolean has = cur != null && !renaming;
		addButton(cx - 160, rowA, 100, confirmRollback ? "Точно откатить?" : "Откатить", has, () -> {
			if (!confirmRollback) {
				confirmRollback = true;
				confirmDelete = false;
				rebuildWidgets();
				return;
			}
			ClientPlayNetworking.send(new VersionsPayloads.VcsAction(VersionsPayloads.VcsAction.ROLLBACK, selected, ""));
			onClose();
		});
		addButton(cx - 50, rowA, 100, "Переименовать", has, () -> {
			renaming = true;
			confirmRollback = confirmDelete = false;
			rebuildWidgets();
		});
		addButton(cx + 60, rowA, 100, confirmDelete ? "Точно удалить?" : "Удалить", has, () -> {
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
		addButton(cx - 50, bottom, 100, "Закрыть", true, this::onClose);
	}

	private void addButton(int x, int y, int w, String label, boolean active, Runnable action) {
		Button b = addRenderableWidget(Button.builder(Component.literal(label), btn -> action.run()).bounds(x, y, w, 20).build());
		b.active = active;
	}

	private void commit() {
		ClientPlayNetworking.send(new VersionsPayloads.VcsAction(VersionsPayloads.VcsAction.COMMIT, 0, nameBox.getValue()));
		nameBox.setValue("");
		net.minecraft.client.Minecraft.getInstance().gui.hud.setOverlayMessage(Component.literal("Сохраняется версия мира…"), false);
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
		if (shown != state.versions) {
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
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		bar.wheel(scrollY);
		if (bar.takeChanged()) rebuildWidgets();
		return true;
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && bar.click(event.x(), event.y())) return rebuildIfScrolled();
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
		if (bar.drag(event.y())) return rebuildIfScrolled();
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
		return bar.release() || super.mouseReleased(event);
	}

	private boolean rebuildIfScrolled() {
		if (bar.takeChanged()) rebuildWidgets();
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		bar.draw(g);
		g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
		if (state.versions.isEmpty())
			g.centeredText(font, "Версий пока нет: введите название и нажмите «Сохранить версию»", width / 2, 70, 0xFFA0A0A0);
		var v = current();
		if (v != null) {
			String info = "Изменено файлов: " + v.changed() + " из " + v.files() + " · " + String.format("%.1f МБ", v.bytes() / 1048576.0)
					+ (v.author().isEmpty() ? "" : " · " + v.author());
			g.centeredText(font, info + (v.id() == state.head ? " · текущая ●" : ""), width / 2, height - 78, 0xFFC0C0C0);
			if (confirmRollback)
				g.centeredText(font, "Мир закроется, несохранённые изменения пропадут. Откройте мир снова.", width / 2, height - 90, 0xFFFFC040);
		}
	}
}
