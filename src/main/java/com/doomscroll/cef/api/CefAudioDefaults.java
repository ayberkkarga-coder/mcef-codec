package com.doomscroll.cef.api;

/**
 * The fork's JNI bridge does not pass on the audio parameters (sample rate); CEF uses the rate of the system
 * output (usually 48000 on Windows). If audio comes out garbled/too fast on a different device, change it here.
 */
public final class CefAudioDefaults {
	/** 0 = measure automatically (incoming frames/second over 1.5 s). Enter a value in Hz to use a fixed rate. */
	public static volatile int sampleRate = 0;

	private CefAudioDefaults() {}

	/**
	 * Ring buffer target (ms). The reader keeps the backlog around this: small = low latency but a risk of stutter,
	 * large = safe. 20..200. Changes take effect immediately.
	 */
	public static volatile int targetBacklogMs = 60;
}
