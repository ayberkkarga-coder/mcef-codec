package com.doomscroll.cef.api;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.cef.browser.CefBrowser;
import org.jetbrains.annotations.Nullable;

/**
 * Off-screen (OSR) Chromium browser. Its image lives in a GPU texture; input is forwarded as Minecraft events.
 * Always call {@link #close()} when done with it.
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

	/** Presses and releases a single key (GLFW key code; for arrow keys the page receives a trusted event). */
	void pressKey(int glfwKey);

	@Nullable
	GpuTexture getTexture();

	@Nullable
	GpuTextureView getTextureView();

	CefBrowser getCefBrowser();

	/**
	 * Page-to-Java message channel: when the page writes {@code console.log('__DS__' + text)}, the listener is
	 * called with {@code text}. Called on the CEF thread; switching to the game thread is the listener's job.
	 */
	void setMessageListener(@Nullable java.util.function.Consumer<String> listener);

	/** Large (base64) stream chunks the page sends via console.log('__DSB__...'); not JSON. */
	void setChunkListener(@Nullable java.util.function.Consumer<String> listener);

	/** Paint frame rate (1..60). Lowered on screens nobody is looking at; the image does not freeze, it just refreshes less often. */
	void setFrameRate(int fps);

	/** Since the last call: frame rate setting, paints/s, uploaded MB/s, upload time ms/s. Resets the counters. */
	String perfInfo();

	/** Has the CEF audio stream started (is the sample rate known)? */
	boolean hasAudioStream();

	/** Applies the ad lists' cosmetic (##) rules as a style to all frames of this browser (idempotent, cheap). */
	void applyCosmetics();

	/** Coarse color map for the screen light (ambilight): LIGHT_ROWS x LIGHT_COLS tiles, row by row, RGB 0..1; null = no frame yet. */
	int LIGHT_COLS = 8;
	int LIGHT_ROWS = 5;
	float[] lightTiles();

	/** Audio waiting in the ring buffer (ms); for latency estimation. */
	int audioBacklogMs();

	/** Sample rate of the audio stream (Hz); 0 if there is no stream. */
	int audioSampleRate();

	/**
	 * Reads mono 16-bit signed little-endian PCM. Any shortfall is filled with silence;
	 * always writes exactly bytes bytes. For feeding the sound engine (OpenAL).
	 */
	void readAudio(java.nio.ByteBuffer dst, int bytes);

	@Override
	void close();
}
