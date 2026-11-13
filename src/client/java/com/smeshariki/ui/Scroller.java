package com.smeshariki.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Вертикальная полоса прокрутки для списков в окнах: рисуется справа от списка, прокручивается колесом, тянется мышью
 * и по клику на дорожку. Окно после любого события спрашивает {@link #takeChanged()} и при {@code true} перестраивает
 * строки списка по {@link #scroll}. Окно с одним списком проще строить от {@link ListScreen}: он делает всё это сам.
 */
public final class Scroller {
	public static final int WIDTH = 6;

	/** Номер первой видимой строки. */
	public int scroll;
	private int x, y, h, total, visible;
	private boolean dragging, changed;
	private int grab;

	/** Задаёт положение полосы и размеры списка (всего строк / видимых) и приводит прокрутку в допустимые пределы. */
	public void layout(int x, int y, int h, int total, int visible) {
		this.x = x;
		this.y = y;
		this.h = h;
		this.total = total;
		this.visible = visible;
		scroll = Math.max(0, Math.min(scroll, max()));
	}

	/** Список длиннее окна: полоса нужна. */
	public boolean active() {
		return total > visible;
	}

	private int max() {
		return Math.max(0, total - visible);
	}

	private int thumbHeight() {
		return Math.max(12, h * visible / Math.max(1, total));
	}

	private int thumbY() {
		int range = h - thumbHeight();
		return y + (max() == 0 ? 0 : range * scroll / max());
	}

	/** Колесо: true, если событие забрала эта полоса (список длиннее окна). */
	public boolean wheel(double delta) {
		if (!active()) return false;
		int next = Math.max(0, Math.min(max(), scroll - (int) Math.signum(delta)));
		if (next != scroll) {
			scroll = next;
			changed = true;
		}
		return true;
	}

	/** Мышь над полосой. */
	public boolean over(double mx, double my) {
		return active() && mx >= x - 2 && mx <= x + WIDTH + 2 && my >= y && my <= y + h;
	}

	/** Нажатие: на ползунок — начинает перетаскивание, на дорожку — переносит ползунок под курсор. */
	public boolean click(double mx, double my) {
		if (!over(mx, my)) return false;
		int ty = thumbY();
		grab = my >= ty && my <= ty + thumbHeight() ? (int) my - ty : thumbHeight() / 2;
		dragging = true;
		moveTo(my);
		return true;
	}

	public boolean drag(double my) {
		if (!dragging) return false;
		moveTo(my);
		return true;
	}

	public boolean release() {
		boolean was = dragging;
		dragging = false;
		return was;
	}

	private void moveTo(double my) {
		int range = h - thumbHeight();
		if (range <= 0) return;
		int pos = Math.max(0, Math.min(range, (int) my - grab - y));
		int next = Math.round((float) pos * max() / range);
		if (next != scroll) {
			scroll = next;
			changed = true;
		}
	}

	/** Менялась ли прокрутка с прошлого вызова. */
	public boolean takeChanged() {
		boolean was = changed;
		changed = false;
		return was;
	}

	public void draw(GuiGraphicsExtractor g) {
		if (!active()) return;
		g.fill(x, y, x + WIDTH, y + h, 0x66000000);
		int ty = thumbY();
		g.fill(x, ty, x + WIDTH, ty + thumbHeight(), dragging ? 0xFFE0E0E0 : Ui.HINT);
	}
}
