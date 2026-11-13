package com.smeshariki.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Выбор из списка: вместо перебора по нажатию — все варианты сразу, с поиском по подписи; выбранный отмечен. Выбор возвращает в окно, откуда открыли.
 * Варианты могут быть {@code null} («нет», «начало миссии»): подпись для них даёт {@code label}.
 */
public final class SelectScreen<T> extends ListScreen {
	private static final int W = 300, ROW = 22, TOP = 50;

	private final List<T> options;
	private final Function<T, Component> label;
	private final T current;
	private final Consumer<T> apply;
	private String filter = "";

	private SelectScreen(Screen parent, Component title, List<T> options, Function<T, Component> label, T current, Consumer<T> apply) {
		super(title, parent);
		this.options = options;
		this.label = label;
		this.current = current;
		this.apply = apply;
	}

	/** Открыть выбор поверх окна {@code parent}. */
	public static <T> void open(Screen parent, Component title, List<T> options, Function<T, Component> label, T current, Consumer<T> apply) {
		Minecraft.getInstance().gui.setScreen(new SelectScreen<>(parent, title, options, label, current, apply));
	}

	private List<T> shown() {
		String f = filter.trim().toLowerCase(Locale.ROOT);
		if (f.isEmpty()) return options;
		List<T> out = new ArrayList<>();
		for (T option : options) if (label.apply(option).getString().toLowerCase(Locale.ROOT).contains(f)) out.add(option);
		return out;
	}

	/** Кнопки вариантов: при поиске и прокрутке меняются они одни, поле поиска остаётся (курсор и выделение целы). */
	private final List<AbstractWidget> rows = new ArrayList<>();

	@Override
	protected void init() {
		rows.clear();
		int w = listWidth(W), x = centered(w);
		EditBox search = new EditBox(font, x, 24, w, Ui.BUTTON_H, Component.translatable("gui.uilib.search"));
		search.setHint(Component.translatable("gui.uilib.search"));
		search.setValue(filter);
		search.setResponder(v -> {
			filter = v;
			bar.scroll = 0;
			fillRows();
		});
		addRenderableWidget(search);
		setInitialFocus(search);
		addBack(x + w / 4, height - 26, w / 2);
		fillRows();
	}

	private void fillRows() {
		for (AbstractWidget row : rows) removeWidget(row);
		rows.clear();
		int w = listWidth(W), x = centered(w);
		List<T> list = shown();
		int visible = rowsFit(TOP, 32, ROW);
		bar.layout(x + w + 3, TOP, visible * ROW - 2, list.size(), visible);
		for (int i = 0; i < visible && bar.scroll + i < list.size(); i++) {
			T option = list.get(bar.scroll + i);
			Component text = label.apply(option);
			if (Objects.equals(option, current)) text = Component.literal("✔ ").append(text);
			rows.add(addRenderableWidget(Button.builder(text, b -> {
				apply.accept(option);
				minecraft.gui.setScreen(parent);
			}).bounds(x, TOP + i * ROW, w, Ui.BUTTON_H).build()));
		}
	}

	@Override
	protected void onScrolled() {
		fillRows();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		drawHeader(g, null);
		if (shown().isEmpty()) drawNote(g, Component.translatable("gui.uilib.empty"), TOP + 6);
	}
}
