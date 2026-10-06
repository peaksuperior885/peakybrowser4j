package com.peak885.peakybrowser4jv2.browser.mixin;

import org.jcodec.codecs.aac.AACDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.tinylog.Logger;

import java.nio.ByteBuffer;

@Mixin(value = AACDecoder.class, remap = false)
public class MixinAACDecoder {

    @Inject(
            method = "decodeFrame",
            at = @At("HEAD")
    )
    private void onDecodeFrame(ByteBuffer frame, ByteBuffer dst, CallbackInfoReturnable<?> cir) {
        if (frame != null) {
            Logger.info("[MIXIN-AAC] Intercepted decodeFrame! Input remaining: {}", frame.remaining());
        } else {
            Logger.warn("[MIXIN-AAC] Intercepted decodeFrame with NULL frame!");
        }
    }
}