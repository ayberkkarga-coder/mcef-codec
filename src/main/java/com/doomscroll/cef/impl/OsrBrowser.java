package com.doomscroll.cef.impl;

import com.doomscroll.cef.api.CefBrowserView;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.event.CefKeyEvent;
import org.cef.event.CefMouseEvent;
import org.cef.event.CefMouseWheelEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;

import java.awt.Rectangle;
import java.nio.ByteBuffer;

/**
 * Off-screen browser: uploads the BGRA frames CEF delivers into a GPU texture and
 * converts Minecraft input into the fork's CefKeyEvent/CefMouseEvent types.
 */
final class OsrBrowser extends CefBrowserOsr implements CefBrowserView {
	private final CefRuntime runtime;
	@Nullable
	private GpuTexture texture;
	@Nullable
	private GpuTextureView textureView;
	private int buttonMask = 0;
	private volatile boolean closed = false;
	/** __DS__ messages coming from the page (CEF thread). */
	@Nullable
	private volatile java.util.function.Consumer<String> messageListener;

	@Override
	public void setMessageListener(@Nullable java.util.function.Consumer<String> listener) {
		messageListener = listener;
	}

	private volatile java.util.function.Consumer<String> chunkListener;

	@Override
	public void setChunkListener(@Nullable java.util.function.Consumer<String> listener) {
		chunkListener = listener;
	}

	void dispatchChunk(String text) {
		java.util.function.Consumer<String> l = chunkListener;
		if (l != null) {
			try {
				l.accept(text);
			} catch (Exception e) {
				CefNatives.LOGGER.warn("could not process stream chunk", e);
			}
		}
	}

	void dispatchMessage(String text) {
		java.util.function.Consumer<String> l = messageListener;
		if (l != null && !closed) {
			try {
				l.accept(text);
			} catch (Exception e) {
				CefNatives.LOGGER.warn("could not process page message", e);
			}
		}
	}

	// ---- audio: CEF planar float -> mono 16-bit ring buffer (fed to the sound engine) ----
	// The reader (readAudio) is rate-adaptive: it reads slightly faster when the buffer fills up, slightly slower when it drains
	// (linear interpolation); previously anything above 120 ms was dropped abruptly -> a cut in the waveform = "pop" sound.
	// Soft fade to zero when drained, soft fade-in when data returns. The incoming rate is measured continuously; if it
	// deviates from the nominal rate by more than 1.5% (two windows in a row) the nominal rate is corrected; the listener reopens the stream.
	private short[] ring = new short[96000];
	private int ringRead = 0, ringWrite = 0, ringCount = 0;
	private volatile int audioRate = 0;
	private int audioChannels = 0;
	// reader state (synchronized access)
	private double fracPos = 0.0;
	private float lastOut = 0f;
	private float fadeGain = 1f;
	private boolean starving = true;
	private long dropEvents = 0L;
	private long underrunEvents = 0L;
	// incoming rate measurement (CEF audio thread only)
	private long measureStart = 0L;
	private long measuredFrames = 0L;
	private int driftWindows = 0;

	void audioStarted(int sampleRate, int channels) {
		synchronized (this) {
			measureStart = 0L;
			measuredFrames = 0L;
			driftWindows = 0;
			audioRate = sampleRate;
			audioChannels = channels <= 0 ? 2 : channels;
			ring = new short[Math.max(8000, sampleRate) * 2]; // 2 s buffer
			ringRead = ringWrite = ringCount = 0;
			fracPos = 0.0;
			lastOut = 0f;
			fadeGain = 1f;
			starving = true;
		}
	}

	private void setRate(int rate, String how) {
		synchronized (this) {
			ring = new short[Math.max(8000, rate) * 2];
			ringRead = ringWrite = ringCount = 0;
			audioRate = rate;
			fracPos = 0.0;
			starving = true;
		}
		CefNatives.LOGGER.info("audio stream rate: {} Hz ({}, {} channels)", rate, how, audioChannels);
	}

	void audioStopped() {
		synchronized (this) {
			ringRead = ringWrite = ringCount = 0;
			starving = true;
		}
	}

	/** Snaps the measured rate to a standard one if within 2.5%, otherwise uses it as is. */
	private static int snapRate(double hz) {
		int best = -1;
		for (int cand : new int[]{22050, 32000, 44100, 48000, 88200, 96000}) {
			if (Math.abs(cand - hz) / cand <= 0.025 && (best < 0 || Math.abs(cand - hz) < Math.abs(best - hz))) {
				best = cand;
			}
		}
		return best > 0 ? best : (int) Math.round(hz);
	}

	/** CEF 126 JNI: data = the left channel's float samples (frames of them); used as mono. */
	void audioPacket(float[] data, int frames) {
		if (audioChannels <= 0 || frames <= 0 || data == null) {
			return;
		}
		int n = Math.min(frames, data.length);
		long now = System.nanoTime();
		if (measureStart == 0L) {
			measureStart = now;
			measuredFrames = 0L;
		}
		measuredFrames += frames;
		long elapsed = now - measureStart;
		int forced = com.doomscroll.cef.api.CefAudioDefaults.sampleRate;
		if (audioRate <= 0) {
			if (forced > 0) {
				setRate(forced, "forced");
			} else if (elapsed >= 1_500_000_000L) {
				double hz = measuredFrames * 1e9 / elapsed;
				setRate(snapRate(hz), String.format("measured %.0f Hz", hz));
				measureStart = now;
				measuredFrames = 0L;
			}
			if (audioRate <= 0) {
				return;
			}
		} else if (elapsed >= 5_000_000_000L) {
			double hz = measuredFrames * 1e9 / elapsed;
			measureStart = now;
			measuredFrames = 0L;
			if (forced <= 0 && Math.abs(hz - audioRate) / audioRate > 0.015) {
				if (++driftWindows >= 2) {
					driftWindows = 0;
					setRate(snapRate(hz), String.format("corrected; measured %.0f Hz", hz));
				}
			} else {
				driftWindows = 0;
			}
		}
		synchronized (this) {
			for (int i = 0; i < n; i++) {
				float v = data[i];
				if (v > 1f) v = 1f; else if (v < -1f) v = -1f;
				if (ringCount == ring.length) {
					ringRead = (ringRead + 1) % ring.length;
					ringCount--;
				}
				ring[ringWrite] = (short) (v * 32767f);
				ringWrite = (ringWrite + 1) % ring.length;
				ringCount++;
			}
		}
	}

	@Override
	public boolean hasAudioStream() {
		return audioRate > 0;
	}

	@Override
	public int audioSampleRate() {
		return audioRate;
	}

	@Override
	public int audioBacklogMs() {
		synchronized (this) {
			return audioRate <= 0 ? 0 : (int) (ringCount * 1000L / audioRate);
		}
	}

	/** Audio buffer statistics (for perfInfo). */
	private String audioInfo() {
		synchronized (this) {
			if (audioRate <= 0) return "no audio";
			String out = String.format(java.util.Locale.ROOT, "audio %d Hz, buffer %d ms, %d drops, %d underruns",
					audioRate, (int) (ringCount * 1000L / audioRate), dropEvents, underrunEvents);
			dropEvents = 0L;
			underrunEvents = 0L;
			return out;
		}
	}

	@Override
	public void readAudio(java.nio.ByteBuffer dst, int bytes) {
		int samples = bytes / 2;
		synchronized (this) {
			int rate = audioRate;
			if (rate <= 0) {
				for (int i = 0; i < samples; i++) dst.putShort((short) 0);
				return;
			}
			int backlogMs = (int) (ringCount * 1000L / rate);
			int target = Math.max(20, Math.min(200, com.doomscroll.cef.api.CefAudioDefaults.targetBacklogMs));
			int hi = target * 3 / 2;
			int lo = target / 2;
			int hard = Math.max(300, target * 4);
			if (backlogMs > hard) {
				// Pathological backlog (freeze, stutter): drop to the target in one go, continue with a soft fade-in
				int keep = rate * target / 1000;
				int drop = ringCount - keep;
				ringRead = (ringRead + drop) % ring.length;
				ringCount = keep;
				dropEvents++;
				fadeGain = 0f;
				backlogMs = target;
			}
			// Rate adaptation: target [lo, hi]; outside it, speed up/slow down by at most 2% (half a semitone)
			double ratio;
			if (backlogMs > hi + 70) ratio = 1.02;
			else if (backlogMs > hi) ratio = 1.006;
			else if (backlogMs < lo / 2) ratio = 0.985;
			else if (backlogMs < lo) ratio = 0.995;
			else ratio = 1.0;
			int prime = rate * Math.max(20, target * 2 / 3) / 1000; // after running dry, do not restart until this much has accumulated
			for (int i = 0; i < samples; i++) {
				float out;
				boolean have = ringCount >= 2 && (!starving || ringCount >= prime);
				if (have) {
					if (starving) {
						starving = false;
						fadeGain = 0f;
					}
					short a = ring[ringRead];
					short b = ring[(ringRead + 1) % ring.length];
					out = a + (b - a) * (float) fracPos;
					if (fadeGain < 1f) {
						fadeGain = Math.min(1f, fadeGain + 1f / 96f);
						out *= fadeGain;
					}
					fracPos += ratio;
					while (fracPos >= 1.0 && ringCount > 1) {
						fracPos -= 1.0;
						ringRead = (ringRead + 1) % ring.length;
						ringCount--;
					}
				} else {
					if (!starving) {
						starving = true;
						underrunEvents++;
					}
					out = lastOut * 0.9f; // no click: soft fade to zero
				}
				lastOut = out;
				int v = Math.round(out);
				dst.putShort((short) Math.max(-32768, Math.min(32767, v)));
			}
		}
	}

	OsrBrowser(CefRuntime runtime, CefClient client, String url, boolean transparent,
			org.cef.browser.CefRequestContext context) {
		super(client, url, transparent, context, frameRateSettings());
		this.runtime = runtime;
	}

	// ---- size / focus ----

	@Override
	public void resize(int width, int height) {
		browser_rect_.setBounds(0, 0, Math.max(1, width), Math.max(1, height));
		wasResized(Math.max(1, width), Math.max(1, height));
	}

	/**
	 * Re-synchronises the view surface: shrinks the view by one pixel and restores it straight away.
	 *
	 * <p>In windowless mode CEF 126 leaves a fullscreen change requested from an out-of-process iframe pending until
	 * the surface changes. The browser grants it and reports it, but neither the player's document nor the page
	 * enters (or leaves) fullscreen, and the promise never settles, so a film site's own fullscreen button does
	 * nothing. WasResized() with an unchanged size does not help. Measured with these binaries, the pending change
	 * completes within one frame of this call, for entering and for leaving. Called after the message-loop pump,
	 * never from inside a CEF callback.
	 */
	void nudgeSurface() {
		if (closed) {
			return;
		}
		int w = Math.max(1, browser_rect_.width);
		int h = browser_rect_.height;
		if (h < 2) {
			return;
		}
		browser_rect_.setBounds(0, 0, w, h - 1);
		wasResized(w, h - 1);
		browser_rect_.setBounds(0, 0, w, h);
		wasResized(w, h);
	}

	@Override
	public void setFocus(boolean focused) {
		super.setFocus(focused);
	}

	// ---- mouse ----

	private static int toCefButton(int glfwButton) {
		// MCEF 2.x: middle and right button are swapped in Minecraft
		return switch (glfwButton) {
			case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> 2;
			case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> 1;
			default -> 0;
		};
	}

	private static int maskFor(int cefButton) {
		return switch (cefButton) {
			case 0 -> CefMouseEvent.BUTTON1_MASK;
			case 1 -> CefMouseEvent.BUTTON2_MASK;
			default -> CefMouseEvent.BUTTON3_MASK;
		};
	}

	/**
	 * The native side (CinemaMod java-cef, GetCefModifiersGlfw) reads the same field both as the GLFW keyboard
	 * modifiers (Shift 1, Ctrl 2, Alt 4, Super 8) and as the mouse button mask 0x10/0x20/0x40.
	 * In GLFW 0x10 is Caps Lock and 0x20 is Num Lock: unless filtered out, every key event went out as
	 * "left mouse button held" while Caps Lock was on.
	 */
	private static int keyMods(int glfwModifiers) {
		return glfwModifiers & (GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER);
	}

	@Override
	public void onMouseClicked(MouseButtonEvent event, boolean doubled) {
		int b = toCefButton(event.button());
		buttonMask |= maskFor(b);
		// Let Shift+click / Ctrl+click reach the page: keyboard modifiers travel in the same field as the mouse mask
		sendMouseEvent(new CefMouseEvent(GLFW.GLFW_PRESS, (int) event.x(), (int) event.y(), doubled ? 2 : 1, b,
				buttonMask | keyMods(event.modifiers())));
	}

	@Override
	public void onMouseReleased(MouseButtonEvent event) {
		int b = toCefButton(event.button());
		buttonMask &= ~maskFor(b);
		sendMouseEvent(new CefMouseEvent(GLFW.GLFW_RELEASE, (int) event.x(), (int) event.y(), 1, b,
				buttonMask | keyMods(event.modifiers())));
	}

	@Override
	public void onMouseMoved(int x, int y) {
		sendMouseEvent(new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, x, y, 0, 0, buttonMask));
	}

	@Override
	public void onMouseScrolled(int x, int y, double amount) {
		double a = amount < 0 ? Math.floor(amount) : Math.ceil(amount);
		sendMouseWheelEvent(new CefMouseWheelEvent(CefMouseWheelEvent.WHEEL_UNIT_SCROLL, x, y, a * 3, 0));
	}

	// ---- keyboard ----

	@Override
	public void onKeyPressed(KeyEvent event) {
		int mods = keyMods(event.modifiers());
		CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_PRESS, event.key(), (char) event.key(), mods);
		e.scancode = event.scancode(); // input() is the GLFW key code; the native side derives the Windows key from the scancode
		sendKeyEvent(e);
		sendEnterChar(event.key(), mods);
	}

	/**
	 * Character event for Enter. GLFW produces no char callback for Enter, whereas Chromium submits the form on
	 * "keypress \r" only; without this, Enter did nothing in search boxes.
	 */
	private void sendEnterChar(int glfwKey, int modifiers) {
		if (glfwKey == GLFW.GLFW_KEY_ENTER || glfwKey == GLFW.GLFW_KEY_KP_ENTER) {
			sendKeyEvent(new CefKeyEvent(CefKeyEvent.KEY_TYPE, '\r', '\r', modifiers));
		}
	}

	@Override
	public void onKeyReleased(KeyEvent event) {
		CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_RELEASE, event.key(), (char) event.key(), keyMods(event.modifiers()));
		e.scancode = event.scancode(); // input() is the GLFW key code; the native side derives the Windows key from the scancode
		sendKeyEvent(e);
	}

	@Override
	public void pressKey(int glfwKey) {
		int scan = org.lwjgl.glfw.GLFW.glfwGetKeyScancode(glfwKey);
		CefKeyEvent down = new CefKeyEvent(CefKeyEvent.KEY_PRESS, glfwKey, (char) glfwKey, 0);
		down.scancode = scan;
		sendKeyEvent(down);
		sendEnterChar(glfwKey, 0);
		CefKeyEvent up = new CefKeyEvent(CefKeyEvent.KEY_RELEASE, glfwKey, (char) glfwKey, 0);
		up.scancode = scan;
		sendKeyEvent(up);
	}

	@Override
	public void onCharTyped(CharacterEvent event) {
		int cp = event.codepoint();
		if (Character.isSupplementaryCodePoint(cp)) {
			// Characters above U+FFFF such as emoji: (char) used to truncate them; Chromium reassembles the UTF-16 pair from two CHAR events
			char hi = Character.highSurrogate(cp);
			char lo = Character.lowSurrogate(cp);
			sendKeyEvent(new CefKeyEvent(CefKeyEvent.KEY_TYPE, hi, hi, 0));
			sendKeyEvent(new CefKeyEvent(CefKeyEvent.KEY_TYPE, lo, lo, 0));
			return;
		}
		char c = (char) cp;
		sendKeyEvent(new CefKeyEvent(CefKeyEvent.KEY_TYPE, c, c, 0));
	}

	// ---- texture ----

	@Override
	@Nullable
	public GpuTexture getTexture() {
		return texture;
	}

	@Override
	@Nullable
	public GpuTextureView getTextureView() {
		return textureView;
	}

	@Override
	public CefBrowser getCefBrowser() {
		return this;
	}

	// ---- frame rate + performance counters ----
	private volatile int frameRate = clampFps(com.doomscroll.cef.api.CefLaunchOptions.frameRate);
	private long perfPaints = 0L;
	private long perfBytes = 0L;
	private long perfUploadNanos = 0L;
	private long perfStart = System.nanoTime();

	private static int clampFps(int fps) {
		return Math.max(1, Math.min(60, fps));
	}

	/** At browser creation: the CEF default is 30 fps; we use CefLaunchOptions.frameRate (default 60). */
	private static org.cef.CefBrowserSettings frameRateSettings() {
		org.cef.CefBrowserSettings st = new org.cef.CefBrowserSettings();
		st.windowless_frame_rate = clampFps(com.doomscroll.cef.api.CefLaunchOptions.frameRate);
		return st;
	}

	private static volatile boolean frameRateBroken = false;
	private volatile boolean directBroken = false;

	@Override
	public void setFrameRate(int fps) {
		int f = clampFps(fps);
		if (f == frameRate || closed || frameRateBroken) {
			return;
		}
		frameRate = f;
		try {
			setWindowlessFrameRate(f);
		} catch (Throwable e) {
			frameRateBroken = true; // JNI missing/broken: do not try again
			CefNatives.LOGGER.warn("could not set frame rate (dynamic frame rate disabled)", e);
		}
	}

	@Override
	public String perfInfo() {
		long now = System.nanoTime();
		double sec = Math.max(1e-3, (now - perfStart) / 1e9);
		String out = String.format(java.util.Locale.ROOT, "%d fps setting · %.0f paints/s · %.1f MB/s · upload %.1f ms/s · %s",
				frameRate, perfPaints / sec, perfBytes / 1048576.0 / sec, perfUploadNanos / 1e6 / sec, audioInfo());
		perfPaints = 0L;
		perfBytes = 0L;
		perfUploadNanos = 0L;
		perfStart = now;
		return out;
	}

	/**
	 * CEF paint callback. Because the message loop is pumped on the render thread, this is the render thread
	 * too and the GL context is ready: without taking a copy, only the dirty rectangles are uploaded to the texture
	 * straight from CEF's buffer (old path: copy the full frame + upload the full frame on every paint).
	 * If it arrives from another thread, the old full-frame path is used (to be safe).
	 */
	@Override
	public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
		if (closed || popup || dirtyRects.length == 0 || width <= 0 || height <= 0) {
			return;
		}
		perfPaints++;
		sampleLight(buffer, width, height);
		if (!directBroken && Minecraft.getInstance().isSameThread()) {
			try {
				uploadDirect(dirtyRects, buffer, width, height);
				return;
			} catch (Throwable e) {
				directBroken = true; // fall back to the safe path: full-frame copy
				CefNatives.LOGGER.warn("direct texture upload failed, fell back to the full-frame copy path", e);
			}
		}
		ByteBuffer copy = MemoryUtil.memAlloc(buffer.capacity());
		MemoryUtil.memCopy(buffer, copy);
		Minecraft.getInstance().submit(() -> upload(copy, width, height));
	}

	/** Recreates the texture if it is missing or its size changed; true if it was created (a full frame must then be uploaded). */
	private boolean ensureTexture(int width, int height) {
		if (texture != null && texture.getWidth(0) == width && texture.getHeight(0) == height) {
			return false;
		}
		releaseTexture();
		texture = RenderSystem.getDevice().createTexture(
				"mcef-codec browser",
				GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
				GpuFormat.RGBA8_UNORM,
				width, height, 1, 1
		);
		textureView = RenderSystem.getDevice().createTextureView(texture);
		return true;
	}

	private void uploadDirect(Rectangle[] rects, ByteBuffer buffer, int width, int height) {
		long t0 = System.nanoTime();
		boolean full = ensureTexture(width, height);
		if (texture == null) {
			return;
		}
		long need = (long) width * height * 4L;
		if (buffer.capacity() < need) {
			return; // unexpected buffer size
		}
		int[] saved = bindForUpload(((GlTexture) texture).glId());
		try {
		GlStateManager._pixelStore(GlConst.GL_UNPACK_ALIGNMENT, 4);
		GlStateManager._pixelStore(GlConst.GL_UNPACK_ROW_LENGTH, width);
		if (full) {
			GlStateManager._pixelStore(GlConst.GL_UNPACK_SKIP_PIXELS, 0);
			GlStateManager._pixelStore(GlConst.GL_UNPACK_SKIP_ROWS, 0);
			GlStateManager._texSubImage2D(GlConst.GL_TEXTURE_2D, 0, 0, 0, width, height, GL12.GL_BGRA, GlConst.GL_UNSIGNED_BYTE, buffer);
			perfBytes += need;
		} else {
			for (Rectangle r : rects) {
				int x = Math.max(0, Math.min(width, r.x));
				int y = Math.max(0, Math.min(height, r.y));
				int w = Math.min(r.width, width - x);
				int h = Math.min(r.height, height - y);
				if (w <= 0 || h <= 0) {
					continue;
				}
				GlStateManager._pixelStore(GlConst.GL_UNPACK_SKIP_PIXELS, x);
				GlStateManager._pixelStore(GlConst.GL_UNPACK_SKIP_ROWS, y);
				GlStateManager._texSubImage2D(GlConst.GL_TEXTURE_2D, 0, x, y, w, h, GL12.GL_BGRA, GlConst.GL_UNSIGNED_BYTE, buffer);
				perfBytes += (long) w * h * 4L;
			}
		}
		} finally {
			// Must not affect other uploads; restore the previous texture binding and unit
			GlStateManager._pixelStore(GlConst.GL_UNPACK_SKIP_PIXELS, 0);
			GlStateManager._pixelStore(GlConst.GL_UNPACK_SKIP_ROWS, 0);
			GlStateManager._pixelStore(GlConst.GL_UNPACK_ROW_LENGTH, 0);
			restoreAfterUpload(saved);
		}
		perfUploadNanos += System.nanoTime() - t0;
	}

	/**
	 * Binds the texture for upload with RAW GL. GlStateManager._bindTexture is cached: Minecraft 26.x's own
	 * draw path binds textures without going through GlStateManager (GL33C.glBindTexture); while the cache said
	 * "our texture is bound" but the atlas was really bound, _bindTexture was skipped and the frame was written into the
	 * ATLAS (items/blocks became corrupted or invisible). The previous binding and active texture unit are restored.
	 * @return {previous active unit, previous GL_TEXTURE_2D binding}
	 */
	private static int[] bindForUpload(int id) {
		int prevUnit = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
		org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
		int prevTex = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
		org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, id);
		return new int[] {prevUnit, prevTex};
	}

	private static void restoreAfterUpload(int[] saved) {
		org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, saved[1]);
		org.lwjgl.opengl.GL13.glActiveTexture(saved[0]);
	}

	/** Fallback path (paint arriving from outside the render thread): full frame, from a copy. */
	private void upload(ByteBuffer buf, int width, int height) {
		try {
			if (closed) {
				return;
			}
			long t0 = System.nanoTime();
			ensureTexture(width, height);
			int[] saved = bindForUpload(((GlTexture) texture).glId());
			try {
				GlStateManager._pixelStore(GlConst.GL_UNPACK_ROW_LENGTH, width);
				GlStateManager._pixelStore(GlConst.GL_UNPACK_SKIP_PIXELS, 0);
				GlStateManager._pixelStore(GlConst.GL_UNPACK_SKIP_ROWS, 0);
				GlStateManager._pixelStore(GlConst.GL_UNPACK_ALIGNMENT, 4);
				GlStateManager._texSubImage2D(GlConst.GL_TEXTURE_2D, 0, 0, 0, width, height, GL12.GL_BGRA, GlConst.GL_UNSIGNED_BYTE, buf);
			} finally {
				GlStateManager._pixelStore(GlConst.GL_UNPACK_ROW_LENGTH, 0);
				restoreAfterUpload(saved);
			}
			perfBytes += (long) width * height * 4L;
			perfUploadNanos += System.nanoTime() - t0;
		} finally {
			MemoryUtil.memFree(buf);
		}
	}

	private void releaseTexture() {
		if (textureView != null) {
			textureView.close();
			textureView = null;
		}
		if (texture != null) {
			texture.close();
			texture = null;
		}
	}

	@Override
	public boolean onCursorChange(CefBrowser browser, int cursorType) {
		return true;
	}

	// ---- screen light: coarse color map (ambilight) ----

	private volatile float[] lightTiles;
	private long lastLightNanos;

	/** Sparsely samples the frame into LIGHT_ROWS x LIGHT_COLS average colors (~25 Hz, a few thousand pixels per frame). */
	private void sampleLight(ByteBuffer buffer, int width, int height) {
		long now = System.nanoTime();
		if (now - lastLightNanos < 40_000_000L || buffer.capacity() < width * height * 4) {
			return;
		}
		lastLightNanos = now;
		int cols = LIGHT_COLS, rows = LIGHT_ROWS;
		int tw = width / cols, th = height / rows;
		if (tw < 2 || th < 2) {
			return;
		}
		int sx = Math.max(1, tw / 10), sy = Math.max(1, th / 8);
		int stride = width * 4;
		float[] out = new float[cols * rows * 3];
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; c++) {
				long rs = 0, gs = 0, bs = 0;
				int n = 0;
				int x0 = c * tw, y0 = r * th;
				for (int y = y0 + sy / 2; y < y0 + th; y += sy) {
					int rowOff = y * stride;
					for (int x = x0 + sx / 2; x < x0 + tw; x += sx) {
						int i = rowOff + x * 4; // BGRA
						bs += buffer.get(i) & 0xFF;
						gs += buffer.get(i + 1) & 0xFF;
						rs += buffer.get(i + 2) & 0xFF;
						n++;
					}
				}
				if (n > 0) {
					int o = (r * cols + c) * 3;
					out[o] = rs / (255f * n);
					out[o + 1] = gs / (255f * n);
					out[o + 2] = bs / (255f * n);
				}
			}
		}
		lightTiles = out;
	}

	@Override
	public float[] lightTiles() {
		return lightTiles;
	}

	// ---- drag and drop: no system drag in OSR ----

	/**
	 * When the page starts a drag (the cursor moves while holding down on an image/link/selected text), CEF expects the
	 * host application to perform the system drag and finish it. The fork's default returned true without doing anything:
	 * the browser stayed in "dragging" mode forever and every mouse click from then on was swallowed. Here the drag is
	 * ended immediately (CEF docs: a synchronous call is allowed).
	 */
	@Override
	public boolean startDragging(CefBrowser browser, org.cef.callback.CefDragData dragData, int mask, int x, int y) {
		try {
			dragSourceEndedAt(new java.awt.Point(x, y), 0);
			dragSourceSystemDragEnded();
		} catch (Throwable t) {
			CefNatives.LOGGER.warn("could not end drag", t);
		}
		return true;
	}

	// ---- cosmetic ad filters ----

	/** frame id -> URL the CSS was last applied for (onLoadStart resets it on reload). */
	private final java.util.Map<String, String> cssApplied = new java.util.HashMap<>();

	/** A frame started loading a new document: drop that frame's style record (null = all of them). */
	void noteLoadStart(String frameId) {
		synchronized (cssApplied) {
			if (frameId == null) cssApplied.clear(); else cssApplied.remove(frameId);
		}
	}

	private static String jsString(String s) {
		StringBuilder b = new StringBuilder(s.length() + 16);
		b.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> b.append("\\\"");
				case '\\' -> b.append("\\\\");
				case '\n' -> b.append("\\n");
				case '\r' -> b.append("\\r");
				case '\u2028' -> b.append("\\u2028");
				case '\u2029' -> b.append("\\u2029");
				case '<' -> b.append("\\u003c");
				default -> {
					if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
				}
			}
		}
		return b.append('"').toString();
	}

	@Override
	public void applyCosmetics() {
		if (closed || !com.doomscroll.cef.api.CefLaunchOptions.adBlock) {
			return;
		}
		try {
			java.util.Vector<String> ids = getFrameIdentifiers();
			if (ids == null) {
				return;
			}
			synchronized (cssApplied) {
				cssApplied.keySet().retainAll(ids);
				for (String id : ids) {
					org.cef.browser.CefFrame f = getFrameByIdentifier(id);
					if (f == null) continue;
					String url = f.getURL();
					if (url == null || !url.startsWith("http")) continue;
					if (url.equals(cssApplied.get(id))) continue;
					cssApplied.put(id, url);
					String css = AdBlock.cosmeticCss(AdBlock.hostOf(url));
					if (css.isEmpty()) continue;
					String js = "(function(){var c=" + jsString(css)
							+ ";var s=document.getElementById('__dsAbp');if(!s){s=document.createElement('style');s.id='__dsAbp';(document.head||document.documentElement).appendChild(s);}if(s.textContent!==c)s.textContent=c;})();";
					f.executeJavaScript(js, url, 0);
				}
			}
		} catch (Exception e) {
			CefNatives.LOGGER.warn("could not apply cosmetic filters", e);
		}
	}

	// ---- close ----

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		runtime.forget(this);
		Minecraft.getInstance().execute(this::releaseTexture);
		super.close(true);
	}
}
