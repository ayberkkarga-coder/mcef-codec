package com.doomscroll.cef.mixin;

import com.doomscroll.cef.api.CefService;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pumps the CEF message loop every frame (doMessageLoopWork is a no-op in the fork; MCEF 2.x does the same). */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void mcefcodec$pump(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
		var init = CefService.initialize();
		if (init.isDone()) {
			init.getFuture().join().pump();
		}
	}
}
