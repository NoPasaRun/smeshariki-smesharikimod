package com.smeshariki.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Окно с одним прокручиваемым списком из кнопок. Колесо, перетаскивание ползунка и клик по дорожке обрабатывает {@link #bar}; когда прокрутка
 * сдвинулась, окно перестраивается ({@code rebuildWidgets}), поэтому {@code init} строит строки с {@code bar.scroll}:
 * <pre>
 * int visible = rowsFit(TOP, 34, ROW);
 * bar.layout(x + w + 3, TOP, visible * ROW - 2, list.size(), visible);
 * for (int i = bar.scroll; i &lt; Math.min(list.size(), bar.scroll + visible); i++) { ... }
 * </pre>
 * Окно с двумя списками использует {@link Scroller} напрямую.
 */
public abstract class ListScreen extends UiScreen {
	protected final Scroller bar = new Scroller();

	protected ListScreen(Component title) {
		super(title);
	}

	protected ListScreen(Component title, net.minecraft.client.gui.screens.Screen parent) {
		super(title, parent);
	}

	/** Сколько строк высоты {@code row} помещается ниже {@code top}, если снизу нужно оставить {@code reserve} пикселей; не меньше {@code min}. */
	protected final int rowsFit(int top, int reserve, int row, int min) {
		return Math.max(min, (height - top - reserve) / row);
	}

	protected final int rowsFit(int top, int reserve, int row) {
		return rowsFit(top, reserve, row, 1);
	}

	/** Прокрутка сдвинулась: по умолчанию окно перестраивается; окно с полем ввода над списком (поиск) может обновить только строки. */
	protected void onScrolled() {
		rebuildWidgets();
	}

	private boolean rebuildIfScrolled() {
		if (bar.takeChanged()) onScrolled();
		return true;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		bar.wheel(scrollY);
		return rebuildIfScrolled();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && bar.click(event.x(), event.y())) return rebuildIfScrolled();
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (bar.drag(event.y())) return rebuildIfScrolled();
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return bar.release() || super.mouseReleased(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		bar.draw(g);
	}
}
