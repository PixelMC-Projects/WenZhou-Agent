package com.pixelmc.wenzhouai.client.mixin;

import com.pixelmc.hub.client.ClientHubSession;
import com.pixelmc.hub.client.ui.HubView;
import com.pixelmc.wenzhouai.client.WzHubAccess;
import com.pixelmc.wenzhouai.client.WzWorldPicker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Uses the same view prepared by Hub on login, including before its screen is ever opened. */
@Mixin(value = ClientHubSession.class, remap = false)
abstract class ClientHubSessionMixin {
    @Inject(method = "open", at = @At("RETURN"))
    private static void wenzhouai$prefetch(HubView view, CallbackInfo ci) {
        WzWorldPicker.liveView(view);
        ((WzHubAccess) (Object) view).wenzhouPage().refresh();
    }
}
