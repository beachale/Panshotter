package com.panshot.spectatorcam.mixin;

import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("zoom")
    float spectatorcam$getZoom();

    @Mutable
    @Accessor("zoom")
    void spectatorcam$setZoom(float zoom);

    @Accessor("zoomX")
    float spectatorcam$getZoomX();

    @Mutable
    @Accessor("zoomX")
    void spectatorcam$setZoomX(float zoomX);

    @Accessor("zoomY")
    float spectatorcam$getZoomY();

    @Mutable
    @Accessor("zoomY")
    void spectatorcam$setZoomY(float zoomY);

    @Accessor("renderHand")
    boolean spectatorcam$isRenderHand();

    @Mutable
    @Accessor("renderHand")
    void spectatorcam$setRenderHand(boolean renderHand);

    @Accessor("blockOutlineEnabled")
    boolean spectatorcam$isRenderBlockOutline();

    @Mutable
    @Accessor("blockOutlineEnabled")
    void spectatorcam$setRenderBlockOutline(boolean renderBlockOutline);

    @Accessor("fovMultiplier")
    float spectatorcam$getFovScale();

    @Mutable
    @Accessor("fovMultiplier")
    void spectatorcam$setFovScale(float fovScale);

    @Accessor("lastFovMultiplier")
    float spectatorcam$getOldFovScale();

    @Mutable
    @Accessor("lastFovMultiplier")
    void spectatorcam$setOldFovScale(float oldFovScale);
}
