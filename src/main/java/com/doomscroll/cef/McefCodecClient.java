package com.doomscroll.cef;

import com.doomscroll.cef.api.CefService;
import com.doomscroll.cef.impl.CefRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public class McefCodecClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Chromium helper processes left over from a previous crashed session
		CefRuntime.killLingeringHelpers();

		// Download the binaries + start CEF (asynchronous)
		CefService.initialize();

		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			CefRuntime.shutdown();
			CefRuntime.killLingeringHelpers();
		});
		Runtime.getRuntime().addShutdownHook(new Thread(CefRuntime::shutdown, "mcef-codec-shutdown"));
	}
}
