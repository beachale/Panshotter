package com.panshot.spectatorcam.mixin;

import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
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
