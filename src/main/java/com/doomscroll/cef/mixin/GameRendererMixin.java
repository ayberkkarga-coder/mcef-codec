package com.doomscroll.cef.mixin;

import com.doomscroll.cef.api.CefService;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Her karede CEF mesaj dongusunu pompalar (fork'ta doMessageLoopWork bos; MCEF 2.x de boyle yapar). */
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
