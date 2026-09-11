package com.doomscroll.cef.api;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.cef.browser.CefBrowser;
import org.jetbrains.annotations.Nullable;

/**
 * Ekran-disi (OSR) Chromium tarayicisi. Goruntusu bir GPU dokusunda; girdiler Minecraft olaylariyla iletilir.
 * Isi bitince mutlaka {@link #close()} cagrilmali.
 */
public interface CefBrowserView extends AutoCloseable {
	void resize(int width, int height);

	void setFocus(boolean focused);

	void onMouseClicked(MouseButtonEvent event, boolean doubled);

	void onMouseReleased(MouseButtonEvent event);

	void onMouseScrolled(int x, int y, double amount);

	void onMouseMoved(int x, int y);

	void onKeyPressed(KeyEvent event);

	void onKeyReleased(KeyEvent event);

	void onCharTyped(CharacterEvent event);

	/** Tek bir tusa basip birakir (GLFW tus kodu; ok tuslari icin sayfa guvenilir olay alir). */
	void pressKey(int glfwKey);

	@Nullable
	GpuTexture getTexture();

	@Nullable
	GpuTextureView getTextureView();

	CefBrowser getCefBrowser();

	/**
	 * Sayfadan Java'ya mesaj kanali: sayfa {@code console.log('__DS__' + metin)} yazinca dinleyici
	 * {@code metin} ile cagrilir. CEF is parcaciginda cagrilir; oyun is parcacigina gecmek dinleyicinin isidir.
	 */
	void setMessageListener(@Nullable java.util.function.Consumer<String> listener);

	/** Sayfanin console.log('__DSB__...') ile yolladigi buyuk (base64) yayin parcalari; JSON degil. */
	void setChunkListener(@Nullable java.util.function.Consumer<String> listener);

	/** Boyama kare hizi (1..60). Bakilmayan ekranlarda dusurulur; goruntu donmaz, sadece seyrek yenilenir. */
	void setFrameRate(int fps);

	/** Son cagridan bu yana: kare hizi ayari, boya/sn, yuklenen MB/sn, yukleme suresi ms/sn. Sayaclari sifirlar. */
	String perfInfo();

	/** CEF ses akisi basladi mi (ornekleme hizi biliniyor mu)? */
	boolean hasAudioStream();

	/** Reklam listelerinin kozmetik (##) kurallarini bu tarayicinin tum cercevelerine stil olarak uygular (idempotent, ucuz). */
	void applyCosmetics();

	/** Ekran isigi (ambilight) icin kaba renk haritasi: LIGHT_ROWS x LIGHT_COLS kutucuk, satir satir, RGB 0..1; null = henuz kare yok. */
	int LIGHT_COLS = 8;
	int LIGHT_ROWS = 5;
	float[] lightTiles();

	/** Halka tamponda bekleyen ses (ms); gecikme tahmini icin. */
	int audioBacklogMs();

	/** Ses akisinin ornekleme hizi (Hz); akis yoksa 0. */
	int audioSampleRate();

	/**
	 * Mono 16-bit signed little-endian PCM okur. Eksik kisim sessizlikle doldurulur;
	 * her zaman tam olarak bytes kadar yazar. Ses motoru (OpenAL) beslemesi icin.
	 */
	void readAudio(java.nio.ByteBuffer dst, int bytes);

	@Override
	void close();
}
