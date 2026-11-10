package com.installermod.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.Locale;

/** Экран подтверждения, прогресса и итога установки. */
public class SyncScreen extends Screen {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFAAAAAA;
	private static final int YELLOW = 0xFFFFFF55;
	private static final int RED = 0xFFFF5555;

	private final SyncSession session;
	/** Экран подключения, поверх которого мы показаны: он же «тикает» соединение. */
	private final Screen previous;
	private SyncSession.State shown;

	public SyncScreen(SyncSession session, Screen previous) {
		super(Component.translatable("installermod.title"));
		this.session = session;
		this.previous = previous;
	}

	@Override
	protected void init() {
		shown = session.state();
		int cx = width / 2;
		int y = height - 28;
		switch (shown) {
			case OFFER -> {
				addRenderableWidget(Button.builder(Component.translatable("installermod.accept"), b -> session.start())
					.bounds(cx - 155, y, 150, 20).build());
				addRenderableWidget(Button.builder(Component.translatable("installermod.decline"), b -> onClose())
					.bounds(cx + 5, y, 150, 20).build());
			}
			case DOWNLOADING -> addRenderableWidget(Button.builder(Component.translatable("installermod.cancel"),
				b -> session.cancel()).bounds(cx - 75, y, 150, 20).build());
			case DONE -> addRenderableWidget(Button.builder(Component.translatable("installermod.close"),
				b -> onClose()).bounds(cx - 75, y, 150, 20).build());
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (previous != null) {
			previous.tick(); // без этого соединение в фазе конфигурации не обрабатывалось бы
		}
		session.tick();
		if (session.state() != shown) {
			rebuildWidgets();
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return session.state() != SyncSession.State.DOWNLOADING;
	}

	@Override
	public void onClose() {
		SyncClient.finish(minecraft);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(font, title, width / 2, 12, WHITE);
		switch (session.state()) {
			case OFFER -> renderOffer(g);
			case DOWNLOADING -> renderProgress(g);
			case DONE -> renderResult(g);
		}
	}

	private int wrapped(GuiGraphicsExtractor g, Component text, int y, int color) {
		for (FormattedCharSequence line : font.split(text, width - 40)) {
			g.centeredText(font, line, width / 2, y, color);
			y += font.lineHeight + 1;
		}
		return y;
	}

	private void renderOffer(GuiGraphicsExtractor g) {
		int y = wrapped(g, Component.translatable("installermod.warning"), 28, YELLOW) + 2;
		g.centeredText(font, Component.translatable("installermod.summary",
			session.items().size(), formatSize(session.totalBytes())), width / 2, y, GRAY);
		y += font.lineHeight + 8;

		int maxLines = Math.max(1, (height - 40 - y) / (font.lineHeight + 2));
		List<SyncSession.Item> items = session.items();
		int shownCount = items.size() > maxLines ? maxLines - 1 : items.size();
		int x = Math.max(20, width / 2 - 160);
		for (int i = 0; i < shownCount; i++) {
			SyncSession.Item item = items.get(i);
			String line = "[" + Component.translatable(item.entry().category().translationKey).getString() + "] "
				+ item.entry().name() + " — " + formatSize(item.entry().size())
				+ (item.isUpdate() ? " (" + Component.translatable("installermod.update").getString() + ")" : "");
			g.text(font, line, x, y, WHITE);
			y += font.lineHeight + 2;
		}
		if (shownCount < items.size()) {
			g.text(font, Component.translatable("installermod.more", items.size() - shownCount), x, y, GRAY);
		}
	}

	private void renderProgress(GuiGraphicsExtractor g) {
		int cx = width / 2;
		int cy = height / 2;
		g.centeredText(font, Component.translatable("installermod.downloading",
			session.currentName(), session.currentNumber(), session.items().size()), cx, cy - 20, WHITE);
		int barLeft = cx - 150;
		int barRight = cx + 150;
		g.fill(barLeft - 1, cy - 1, barRight + 1, cy + 11, 0xFF000000);
		g.fill(barLeft, cy, barRight, cy + 10, 0xFF444444);
		double ratio = session.totalBytes() == 0 ? 0 : (double) session.doneBytes() / session.totalBytes();
		g.fill(barLeft, cy, barLeft + (int) (300 * Math.min(1, ratio)), cy + 10, 0xFF55FF55);
		g.centeredText(font, formatSize(session.doneBytes()) + " / " + formatSize(session.totalBytes()),
			cx, cy + 16, GRAY);
	}

	private void renderResult(GuiGraphicsExtractor g) {
		int y = 40;
		if (session.cancelled()) {
			g.centeredText(font, Component.translatable("installermod.cancelled"), width / 2, y, YELLOW);
			y += font.lineHeight + 4;
		}
		g.centeredText(font, Component.translatable("installermod.done", session.installed(), session.items().size()),
			width / 2, y, WHITE);
		y += font.lineHeight + 6;
		if (session.modsInstalled()) {
			y = wrapped(g, Component.translatable("installermod.restart"), y, YELLOW) + 4;
		}
		int maxLines = Math.max(1, (height - 40 - y) / (font.lineHeight + 2));
		List<String> errors = session.errors();
		for (int i = 0; i < Math.min(errors.size(), maxLines); i++) {
			g.centeredText(font, errors.get(i), width / 2, y, RED);
			y += font.lineHeight + 2;
		}
	}

	private static String formatSize(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		if (bytes < 1024 * 1024) {
			return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
		}
		return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
	}
}
