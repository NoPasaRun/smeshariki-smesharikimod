package com.smeshariki.ui;

import java.util.function.IntConsumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Основа окон модов: то, что каждое окно раньше писало заново. Мир за окном идёт дальше ({@link #pausesGame()} ложно), заголовок и подсказка
 * рисуются одинаково, закрытие возвращает в окно, откуда открыли, а кнопки «Назад», «✕», «↑ ↓» строятся одним вызовом.
 */
public abstract class UiScreen extends Screen {
	/** Ширина узкой кнопки-значка (✕, ↑, ↓). */
	public static final int TOOL_W = 20;
	/** Ширина двух кнопок ↑ ↓ вместе с зазором. */
	public static final int MOVE_W = 2 * TOOL_W + 1;

	/** Окно, куда вернёт закрытие; {@code null} — закрыть совсем. */
	protected final Screen parent;

	protected UiScreen(Component title) {
		this(title, null);
	}

	protected UiScreen(Component title, Screen parent) {
		super(title);
		this.parent = parent;
	}

	/** Ставит ли окно игру на паузу в одиночной игре. По умолчанию нет: мир идёт дальше. */
	protected boolean pausesGame() {
		return false;
	}

	@Override
	public boolean isPauseScreen() {
		return pausesGame();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	/**
	 * Идёт ли ввод в поле: фокус на {@link EditBox}. Окно, которое перестраивается по новому списку от сервера, пока игрок печатает, теряет фокус и
	 * набранное, поэтому в {@code tick} пишут {@code if (changed && !isEditing()) rebuildWidgets();}: изменение остаётся замеченным и применится, когда ввод кончится.
	 */
	protected final boolean isEditing() {
		return getFocused() instanceof EditBox;
	}

	/** Ширина колонки окна: не больше {@code max} и не шире экрана с полями. */
	protected final int panelWidth(int max) {
		return Math.min(max, width - 8);
	}

	/** Ширина списка: то же, но справа остаётся место под полосу прокрутки ({@link Scroller}). */
	protected final int listWidth(int max) {
		return Math.min(max, width - 8 - Scroller.WIDTH - 4);
	}

	/** Левый край колонки ширины {@code w}, поставленной по центру экрана. */
	protected final int centered(int w) {
		return (width - w) / 2;
	}

	/** Заголовок окна по центру сверху и (если есть) серая подсказка под ним. */
	protected final void drawHeader(GuiGraphicsExtractor g, Component hint) {
		g.centeredText(font, title, width / 2, 8, Ui.TEXT);
		if (hint == null) return;
		// подсказка длиннее окна переносится (не больше двух строк: ниже обычно уже начинаются поля)
		int y = 22, lines = 0;
		for (var line : font.split(hint, width - 16)) {
			if (lines++ == 2) break;
			g.centeredText(font, line, width / 2, y, Ui.HINT);
			y += 9;
		}
	}

	/** Серая строка по центру («список пуст», пояснение). */
	protected final void drawNote(GuiGraphicsExtractor g, Component text, int y) {
		g.centeredText(font, text, width / 2, y, Ui.HINT);
	}

	protected final Button addButton(Component label, int x, int y, int w, Runnable action) {
		return addRenderableWidget(Button.builder(label, b -> action.run()).bounds(x, y, w, Ui.BUTTON_H).build());
	}

	/** Кнопка «Назад»: закрывает окно и возвращает в то, откуда открыли. */
	protected final Button addBack(int x, int y, int w) {
		return addButton(Component.translatable("gui.uilib.back"), x, y, w, this::onClose);
	}

	/** Узкая кнопка ✕ с подсказкой «Удалить». */
	protected final Button addDelete(int x, int y, Runnable action) {
		return addDelete(x, y, Component.translatable("gui.uilib.delete"), action);
	}

	protected final Button addDelete(int x, int y, Component tip, Runnable action) {
		return addRenderableWidget(Button.builder(Component.literal("✕"), b -> action.run()).bounds(x, y, TOOL_W, Ui.BUTTON_H).tooltip(Tooltip.create(tip)).build());
	}

	/** Кнопки ↑ ↓ строки списка (занимают {@link #MOVE_W}); {@code move} получает −1 или +1, крайние кнопки неактивны. */
	protected final void addMoveButtons(int x, int y, int index, int size, IntConsumer move) {
		addMoveButtons(x, y, index, size, move, Component.translatable("gui.uilib.up"), Component.translatable("gui.uilib.down"));
	}

	protected final void addMoveButtons(int x, int y, int index, int size, IntConsumer move, Component upTip, Component downTip) {
		Button up = addRenderableWidget(Button.builder(Component.literal("↑"), b -> move.accept(-1)).bounds(x, y, TOOL_W, Ui.BUTTON_H).tooltip(Tooltip.create(upTip)).build());
		up.active = index > 0;
		Button down = addRenderableWidget(Button.builder(Component.literal("↓"), b -> move.accept(1)).bounds(x + TOOL_W + 1, y, TOOL_W, Ui.BUTTON_H).tooltip(Tooltip.create(downTip)).build());
		down.active = index < size - 1;
	}
}
