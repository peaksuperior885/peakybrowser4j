package com.peak885.peakybrowser4jv2.browser.mixin;

import com.peak885.peakybrowser4jv2.browser.http.HttpManager;
import okhttp3.Request;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.tinylog.Logger;

@Mixin(value = HttpManager.class, remap = false)
public class HttpManagerMixin {

    @Inject(method = "execute", at = @At("HEAD"))
    private static void onExecute(Request request, CallbackInfoReturnable<?> cir) {
        if (request != null && request.url() != null) {
            Logger.info("[MIXIN] Intercepted HTTP request via Mixin for: {}", request.url());
        }
    }
}