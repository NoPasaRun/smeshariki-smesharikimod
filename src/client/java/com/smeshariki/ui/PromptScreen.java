package com.smeshariki.ui;

import java.util.function.Consumer;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Строка ввода: Enter принимает, Esc отменяет; после закрытия возвращает в окно, откуда открыли. Мир за окном идёт дальше. */
public final class PromptScreen extends UiScreen {
	private final Component hint;
	private final String initial;
	private final Consumer<String> accept;
	private EditBox box;

	private PromptScreen(Screen parent, Component title, Component hint, String initial, Consumer<String> accept) {
		super(title, parent);
		this.hint = hint;
		this.initial = initial;
		this.accept = accept;
	}

	/** Открыть строку ввода поверх текущего окна. Пустое значение не принимается. */
	public static void open(Component title, Component hint, String initial, Consumer<String> accept) {
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setScreen(new PromptScreen(mc.gui.screen(), title, hint, initial, accept));
	}

	/** То же с готовыми строками (для окон, у которых подписи собираются на лету). */
	public static void open(String title, String hint, String initial, Consumer<String> accept) {
		open(Component.literal(title), Component.literal(hint), initial, accept);
	}

	@Override
	protected void init() {
		int w = Math.min(360, width - 24);
		box = new EditBox(font, centered(w), height / 2 - 10, w, Ui.BUTTON_H, title);
		box.setMaxLength(256);
		box.setValue(initial);
		addRenderableWidget(box);
		setInitialFocus(box);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
			String value = box.getValue().trim();
			onClose();
			if (!value.isEmpty()) accept.accept(value);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.centeredText(font, title, width / 2, height / 2 - 34, Ui.TEXT);
		g.centeredText(font, hint, width / 2, height / 2 + 18, Ui.HINT);
	}
}
