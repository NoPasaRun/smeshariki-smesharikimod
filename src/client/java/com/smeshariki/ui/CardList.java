package com.smeshariki.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Список карточек слева в окне (уровни, задачи, локации, группы): карточки одной высоты рисует само окно, а положение, прокрутку в пикселях,
 * фон (обычный, под курсором, выбранный), обрезку по краям и выбор карточки по клику берёт на себя этот класс. Окно держит {@code CardList} полем,
 * в {@code init} задаёт область ({@link #layout}), из {@code mouseScrolled} зовёт {@link #wheel}, из {@code mouseClicked} — {@link #rowAt},
 * а в {@code extractRenderState} вызывает {@link #draw} и в замыкании рисует содержимое каждой карточки.
 * <pre>
 * cards.draw(g, rows.size(), mouseX, mouseY, (i, y, hover) -&gt; {
 *     cards.background(g, cards.x(), y, rows.get(i) == chosen, hover);
 *     g.text(font, label(i), cards.x() + 3, y + 3, Ui.TEXT);
 * });
 * </pre>
 */
public final class CardList {
	/** Рисует содержимое одной карточки: номер, верхний край, под курсором ли. */
	@FunctionalInterface
	public interface Painter {
		void paint(int index, int y, boolean hover);
	}

	private final int cardH, gap;
	private int x, top, w, bottom, scroll;

	/** @param cardH шаг карточек по вертикали; @param gap просвет между карточками (фон короче шага на столько) */
	public CardList(int cardH, int gap) {
		this.cardH = cardH;
		this.gap = gap;
	}

	/** Область списка: левый край, верх, ширина, низ. */
	public void layout(int x, int top, int w, int bottom) {
		this.x = x;
		this.top = top;
		this.w = w;
		this.bottom = bottom;
	}

	public int x() {
		return x;
	}

	public int width() {
		return w;
	}

	public int top() {
		return top;
	}

	public int bottom() {
		return bottom;
	}

	public int cardHeight() {
		return cardH;
	}

	private int max(int count) {
		return Math.max(0, count * cardH - (bottom - top));
	}

	/**
	 * Колесо: если мышь левее правого края списка (над ним или над пустым местом слева), сдвигает его на {@code step} пикселей за щелчок.
	 * @return {@code true}, если колесо забрал список; иначе окно может прокрутить своё
	 */
	public boolean wheel(double mouseX, double scrollY, int count, int step) {
		if (mouseX >= x + w) return false;
		scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY) * step, max(count)));
		return true;
	}

	/** Колесо с шагом в одну карточку. */
	public boolean wheel(double mouseX, double scrollY, int count) {
		return wheel(mouseX, scrollY, count, cardH);
	}

	/** Номер карточки под точкой или −1. */
	public int rowAt(double mouseX, double mouseY, int count) {
		if (mouseX < x || mouseX >= x + w || mouseY < top || mouseY >= bottom) return -1;
		int row = ((int) mouseY - top + scroll) / cardH;
		return row >= 0 && row < count ? row : -1;
	}

	/** Фон карточки с верхним краем {@code y} от {@code left} до правого края списка. */
	public void background(GuiGraphicsExtractor g, int left, int y, boolean selected, boolean hover) {
		g.fill(left, y, x + w, y + cardH - gap, selected ? Ui.CARD_SELECTED : hover ? Ui.CARD_HOVER : Ui.CARD);
	}

	/** Рисует видимые карточки; содержимое каждой — в {@code painter}. */
	public void draw(GuiGraphicsExtractor g, int count, int mouseX, int mouseY, Painter painter) {
		scroll = Math.min(scroll, max(count));
		g.enableScissor(x, top, x + w, bottom);
		for (int i = 0; i < count; i++) {
			int y = top + i * cardH - scroll;
			if (y + cardH <= top || y >= bottom) continue;
			boolean hover = mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, top) && mouseY < Math.min(y + cardH - gap, bottom);
			painter.paint(i, y, hover);
		}
		g.disableScissor();
		drawIndicator(g, count);
	}

	/** Тонкая полоса у правого края списка, пока он длиннее окна: показывает, что список прокручивается, и где сейчас окно. */
	private void drawIndicator(GuiGraphicsExtractor g, int count) {
		int max = max(count);
		if (max <= 0) return;
		int track = bottom - top;
		int thumb = Math.max(12, track * track / (track + max));
		int ty = top + (track - thumb) * scroll / max;
		g.fill(x + w - 2, top, x + w, bottom, 0x40000000);
		g.fill(x + w - 2, ty, x + w, ty + thumb, Ui.HINT);
	}
}
