package com.smeshariki.ui;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Палитра окон модов: цвета ARGB. Окна берут цвета отсюда, а не пишут числа сами, чтобы один и тот же смысл выглядел одинаково во всех модах.
 * Список собран из того, что уже было в окнах; золотых оттенков было четыре, остался один.
 */
public final class Ui {
	/** Основной текст и заголовки. */
	public static final int TEXT = 0xFFFFFFFF;
	/** Подсказки, пояснения, «список пуст». */
	public static final int HINT = 0xFFA0A0A0;
	/** Второстепенный текст светлее подсказки. */
	public static final int MUTED = 0xFFC0C0C0;
	/** Приглушённый текст темнее подсказки. */
	public static final int DIM = 0xFF909090;
	/** Золотой акцент: выбранное, важное, полосы. */
	public static final int ACCENT = 0xFFFFD54A;
	/** Ошибка, опасное действие. */
	public static final int BAD = 0xFFFF6060;
	/** Успех, готово. */
	public static final int GOOD = 0xFF60D060;
	/** Фон карточки списка, выбранной и под курсором. */
	public static final int CARD = 0x60000000, CARD_SELECTED = 0xA0304050, CARD_HOVER = 0x80202020;
	/** Чёрный. */
	public static final int BLACK = 0xFF000000;

	/** Высота обычной кнопки и поля ввода. */
	public static final int BUTTON_H = 20;
	/** Шаг строки списка из кнопок. */
	public static final int ROW = 24;

	private Ui() {
	}

	/** Первая строка текста, если он не помещается в {@code width} (остальное отбрасывается). */
	public static FormattedCharSequence firstLine(Font font, Component text, int width) {
		List<FormattedCharSequence> lines = font.split(text, Math.max(1, width));
		return lines.isEmpty() ? FormattedCharSequence.EMPTY : lines.getFirst();
	}
}
