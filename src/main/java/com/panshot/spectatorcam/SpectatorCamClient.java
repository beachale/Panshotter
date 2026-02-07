package com.panshot.spectatorcam;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.panshot.spectatorcam.mixin.GameRendererAccessor;
import com.panshot.spectatorcam.mixin.MinecraftClientAccessor;
import com.panshot.spectatorcam.mixin.WindowAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.Entity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.IntBuffer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class SpectatorCamClient implements ClientModInitializer {
    private static final String MESSAGE_PREFIX = "[PanShot] ";
    private static final double DEFAULT_PANORAMA_INTERVAL_SECONDS = 10.0;
    private static final double DEFAULT_SINGLE_INTERVAL_SECONDS = 1.0;
    private static final UUID CAMERA_PROFILE_ID = UUID.fromString("f0d6643c-af19-4e1e-948d-a5d2d7e2f27b");
    private static final PanoramaWebServer PANORAMA_WEB_SERVER = new PanoramaWebServer();
    private static final SinglePreviewWebServer SINGLE_WEB_SERVER = new SinglePreviewWebServer();
    private static final PanoramaCaptureController PANORAMA_CONTROLLER = new PanoramaCaptureController();
    private static final SingleCaptureController SINGLE_CONTROLLER = new SingleCaptureController();
    private static final SpectatorCameraController CAMERA_CONTROLLER = new SpectatorCameraController();
    private static final ExecutorService READBACK_CONVERT_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "panshot-readback-convert");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            CAMERA_CONTROLLER.tick(client);
            PANORAMA_CONTROLLER.tick(client);
            SINGLE_CONTROLLER.tick(client);
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerCommands(dispatcher));
    }

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(buildRootCommand("panshot"));
    }

    private static void takeScreenshotAsyncFast(MinecraftClient client, Framebuffer framebuffer, Consumer<NativeImage> consumer) {
        GpuTexture colorAttachment = framebuffer.getColorAttachment();
        if (colorAttachment == null) {
            throw new IllegalStateException("Tried to capture screenshot of an incomplete framebuffer");
        }

        int width = framebuffer.textureWidth;
        int height = framebuffer.textureHeight;
        int pixelSize = colorAttachment.getFormat().pixelSize();
        int pixelCount = width * height;
        int requiredBytes = pixelCount * pixelSize;
        GpuBuffer gpuBuffer = RenderSystem.getDevice().createBuffer(() -> "PanShot readback buffer", 9, requiredBytes);
        CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
        commandEncoder.copyTextureToBuffer(colorAttachment, gpuBuffer, 0, () -> {
            GpuBuffer.MappedView mappedView;
            try {
                mappedView = commandEncoder.mapBuffer(gpuBuffer, true, false);
            } catch (RuntimeException exception) {
                gpuBuffer.close();
                throw exception;
            }

            READBACK_CONVERT_EXECUTOR.execute(() -> {
                NativeImage image = null;
                RuntimeException failure = null;
                try {
                    int[] readbackPixels = new int[pixelCount];
                    IntBuffer intBuffer = mappedView.data().asIntBuffer();
                    intBuffer.get(readbackPixels, 0, pixelCount);

                    image = new NativeImage(width, height, false);
                    for (int y = 0; y < height; y++) {
                        int srcRow = y * width;
                        int dstY = height - y - 1;
                        for (int x = 0; x < width; x++) {
                            image.setColor(x, dstY, readbackPixels[srcRow + x] | 0xFF000000);
                        }
                    }
                } catch (RuntimeException exception) {
                    failure = exception;
                }
                NativeImage completedImage = image;
                RuntimeException capturedFailure = failure;
                client.execute(() -> {
                    RuntimeException unmapFailure = null;
                    try {
                        mappedView.close();
                    } catch (RuntimeException exception) {
                        unmapFailure = exception;
                    } finally {
                        gpuBuffer.close();
                    }

                    if (unmapFailure != null) {
                        if (completedImage != null) {
                            completedImage.close();
                        }
                        unmapFailure.printStackTrace();
                        ScreenshotRecorder.takeScreenshot(framebuffer, consumer);
                        return;
                    }

                    if (completedImage != null) {
                        consumer.accept(completedImage);
                    } else {
                        if (capturedFailure != null) {
                            capturedFailure.printStackTrace();
                        }
                        ScreenshotRecorder.takeScreenshot(framebuffer, consumer);
                    }
                });
            });
        }, 0);
    }

    private static OtherClientPlayerEntity createRenderPlayerEntity(
        ClientWorld world,
        UUID profileId,
        int entityId,
        ClientPlayerEntity sourcePlayer
    ) {
        GameProfile sourceProfile = sourcePlayer.getGameProfile();
        GameProfile renderProfile = new GameProfile(profileId, sourceProfile.name(), sourceProfile.properties());
        OtherClientPlayerEntity renderPlayer = new OtherClientPlayerEntity(world, renderProfile);
        renderPlayer.setId(entityId);
        return renderPlayer;
    }

    private static void syncRenderPlayerEntityState(ClientPlayerEntity source, OtherClientPlayerEntity target) {
        target.refreshPositionAndAngles(source.getX(), source.getY(), source.getZ(), source.getYaw(), source.getPitch());
        target.setYaw(source.getYaw());
        target.setPitch(source.getPitch());
        target.lastYaw = source.lastYaw;
        target.lastPitch = source.lastPitch;
        target.lastX = source.lastX;
        target.lastY = source.lastY;
        target.lastZ = source.lastZ;
        target.setVelocity(source.getVelocity());
        target.setOnGround(source.isOnGround());
        target.setSneaking(source.isSneaking());
        target.setSprinting(source.isSprinting());
        target.setSwimming(source.isSwimming());
        target.setPose(source.getPose());
        target.setHeadYaw(source.getHeadYaw());
        target.setBodyYaw(source.getBodyYaw());
        target.lastHeadYaw = source.lastHeadYaw;
        target.lastBodyYaw = source.lastBodyYaw;
        target.setInvisible(source.isInvisible());
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            target.equipStack(slot, source.getEquippedStack(slot));
        }
        if (source.isUsingItem()) {
            target.setCurrentHand(source.getActiveHand());
        } else {
            target.clearActiveItem();
        }
    }

    private static void removeEntityIfPresent(ClientWorld world, int entityId) {
        try {
            world.removeEntity(entityId, Entity.RemovalReason.DISCARDED);
        } catch (RuntimeException ignored) {
            // Best-effort cleanup.
        }
    }

    private static byte[] encodePngBytes(NativeImage image) throws IOException {
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        bufferedImage.setRGB(0, 0, width, height, image.copyPixelsArgb(), 0, width);

        try (ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(1024, width * height / 2))) {
            if (!ImageIO.write(bufferedImage, "png", output)) {
                throw new IOException("No PNG image writer available.");
            }
            return output.toByteArray();
        }
    }

    private static ClickEvent.OpenUrl createOpenUrlClickEvent(String url) {
        return new ClickEvent.OpenUrl(URI.create(url));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildRootCommand(String root) {
        RequiredArgumentBuilder<FabricClientCommandSource, Double> startPitch =
            argument("pitch", DoubleArgumentType.doubleArg(-90.0, 90.0))
                .executes(context -> PANORAMA_CONTROLLER.startAt(
                    context.getSource().getClient(),
                    DEFAULT_PANORAMA_INTERVAL_SECONDS,
                    DoubleArgumentType.getDouble(context, "x"),
                    DoubleArgumentType.getDouble(context, "y"),
                    DoubleArgumentType.getDouble(context, "z"),
                    (float)DoubleArgumentType.getDouble(context, "yaw"),
                    (float)DoubleArgumentType.getDouble(context, "pitch")
                ));
        RequiredArgumentBuilder<FabricClientCommandSource, Double> startYaw =
            argument("yaw", DoubleArgumentType.doubleArg()).then(startPitch);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> startZ =
            argument("z", DoubleArgumentType.doubleArg()).then(startYaw);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> startY =
            argument("y", DoubleArgumentType.doubleArg()).then(startZ);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> startX =
            argument("x", DoubleArgumentType.doubleArg()).then(startY);

        RequiredArgumentBuilder<FabricClientCommandSource, Double> everyPitch =
            argument("pitch", DoubleArgumentType.doubleArg(-90.0, 90.0))
                .executes(context -> PANORAMA_CONTROLLER.startAt(
                    context.getSource().getClient(),
                    DoubleArgumentType.getDouble(context, "intervalSeconds"),
                    DoubleArgumentType.getDouble(context, "x"),
                    DoubleArgumentType.getDouble(context, "y"),
                    DoubleArgumentType.getDouble(context, "z"),
                    (float)DoubleArgumentType.getDouble(context, "yaw"),
                    (float)DoubleArgumentType.getDouble(context, "pitch")
                ));
        RequiredArgumentBuilder<FabricClientCommandSource, Double> everyYaw =
            argument("yaw", DoubleArgumentType.doubleArg()).then(everyPitch);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> everyZ =
            argument("z", DoubleArgumentType.doubleArg()).then(everyYaw);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> everyY =
            argument("y", DoubleArgumentType.doubleArg()).then(everyZ);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> everyX =
            argument("x", DoubleArgumentType.doubleArg()).then(everyY);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> everyInterval =
            argument("intervalSeconds", DoubleArgumentType.doubleArg(0.1))
                .executes(context -> PANORAMA_CONTROLLER.startAtPlayer(
                    context.getSource().getClient(),
                    DoubleArgumentType.getDouble(context, "intervalSeconds")
                ))
                .then(everyX);

        LiteralArgumentBuilder<FabricClientCommandSource> panoramaStart = literal("start")
            .executes(context -> PANORAMA_CONTROLLER.startAtPlayer(
                context.getSource().getClient(),
                DEFAULT_PANORAMA_INTERVAL_SECONDS
            ))
            .then(startX)
            .then(literal("every").then(everyInterval));

        RequiredArgumentBuilder<FabricClientCommandSource, Double> singlePitch =
            argument("pitch", DoubleArgumentType.doubleArg(-90.0, 90.0))
                .executes(context -> SINGLE_CONTROLLER.startAt(
                    context.getSource().getClient(),
                    DEFAULT_SINGLE_INTERVAL_SECONDS,
                    DoubleArgumentType.getDouble(context, "x"),
                    DoubleArgumentType.getDouble(context, "y"),
                    DoubleArgumentType.getDouble(context, "z"),
                    (float)DoubleArgumentType.getDouble(context, "yaw"),
                    (float)DoubleArgumentType.getDouble(context, "pitch")
                ));
        RequiredArgumentBuilder<FabricClientCommandSource, Double> singleYaw =
            argument("yaw", DoubleArgumentType.doubleArg()).then(singlePitch);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> singleZ =
            argument("z", DoubleArgumentType.doubleArg()).then(singleYaw);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> singleY =
            argument("y", DoubleArgumentType.doubleArg()).then(singleZ);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> singleX =
            argument("x", DoubleArgumentType.doubleArg()).then(singleY);

        RequiredArgumentBuilder<FabricClientCommandSource, Double> singleEveryPitch =
            argument("pitch", DoubleArgumentType.doubleArg(-90.0, 90.0))
                .executes(context -> SINGLE_CONTROLLER.startAt(
                    context.getSource().getClient(),
                    DoubleArgumentType.getDouble(context, "intervalSeconds"),
                    DoubleArgumentType.getDouble(context, "x"),
                    DoubleArgumentType.getDouble(context, "y"),
                    DoubleArgumentType.getDouble(context, "z"),
                    (float)DoubleArgumentType.getDouble(context, "yaw"),
                    (float)DoubleArgumentType.getDouble(context, "pitch")
                ));
        RequiredArgumentBuilder<FabricClientCommandSource, Double> singleEveryYaw =
            argument("yaw", DoubleArgumentType.doubleArg()).then(singleEveryPitch);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> singleEveryZ =
            argument("z", DoubleArgumentType.doubleArg()).then(singleEveryYaw);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> singleEveryY =
            argument("y", DoubleArgumentType.doubleArg()).then(singleEveryZ);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> singleEveryX =
            argument("x", DoubleArgumentType.doubleArg()).then(singleEveryY);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> singleEveryInterval =
            argument("intervalSeconds", DoubleArgumentType.doubleArg(0.1))
                .executes(context -> SINGLE_CONTROLLER.startAtPlayer(
                    context.getSource().getClient(),
                    DoubleArgumentType.getDouble(context, "intervalSeconds")
                ))
                .then(singleEveryX);

        LiteralArgumentBuilder<FabricClientCommandSource> singleResolutionCommand = literal("resolution")
            .executes(context -> SINGLE_CONTROLLER.resolutionStatus(context.getSource().getClient()))
            .then(argument("width", IntegerArgumentType.integer(64, 4096))
                .then(argument("height", IntegerArgumentType.integer(64, 4096))
                    .executes(context -> SINGLE_CONTROLLER.setResolution(
                        context.getSource().getClient(),
                        IntegerArgumentType.getInteger(context, "width"),
                        IntegerArgumentType.getInteger(context, "height")
                    ))));

        LiteralArgumentBuilder<FabricClientCommandSource> singleFovCommand = literal("fov")
            .executes(context -> SINGLE_CONTROLLER.fovStatus(context.getSource().getClient()))
            .then(argument("degrees", IntegerArgumentType.integer(1, 179))
                .executes(context -> SINGLE_CONTROLLER.setFov(
                    context.getSource().getClient(),
                    IntegerArgumentType.getInteger(context, "degrees")
                )));

        LiteralArgumentBuilder<FabricClientCommandSource> singleRenderPlayerCommand = literal("renderplayer")
            .executes(context -> SINGLE_CONTROLLER.renderPlayerStatus(context.getSource().getClient()))
            .then(literal("on").executes(context -> SINGLE_CONTROLLER.setRenderPlayerEnabled(context.getSource().getClient(), true)))
            .then(literal("off").executes(context -> SINGLE_CONTROLLER.setRenderPlayerEnabled(context.getSource().getClient(), false)));

        LiteralArgumentBuilder<FabricClientCommandSource> singleCommand = literal("single")
            .executes(context -> SINGLE_CONTROLLER.startAtPlayer(
                context.getSource().getClient(),
                DEFAULT_SINGLE_INTERVAL_SECONDS
            ))
            .then(singleX)
            .then(literal("every").then(singleEveryInterval))
            .then(singleResolutionCommand)
            .then(singleFovCommand)
            .then(singleRenderPlayerCommand)
            .then(literal("stop").executes(context -> SINGLE_CONTROLLER.stop(context.getSource().getClient(), true)))
            .then(literal("status").executes(context -> SINGLE_CONTROLLER.status(context.getSource().getClient())));

        return literal(root)
            .executes(context -> CAMERA_CONTROLLER.toggle(context.getSource().getClient()))
            .then(literal("where").executes(context -> CAMERA_CONTROLLER.printPosition(context.getSource().getClient())))
            .then(singleCommand)
            .then(literal("panorama")
                .then(panoramaStart)
                .then(literal("stop").executes(context -> PANORAMA_CONTROLLER.stop(context.getSource().getClient(), true)))
                .then(literal("status").executes(context -> PANORAMA_CONTROLLER.status(context.getSource().getClient())))
                .then(literal("mode")
                    .executes(context -> PANORAMA_CONTROLLER.modeStatus(context.getSource().getClient()))
                    .then(literal("smooth").executes(context -> PANORAMA_CONTROLLER.setPreciseCaptureMode(context.getSource().getClient(), false)))
                    .then(literal("precise").executes(context -> PANORAMA_CONTROLLER.setPreciseCaptureMode(context.getSource().getClient(), true))))
                .then(literal("renderplayer")
                    .executes(context -> PANORAMA_CONTROLLER.renderPlayerStatus(context.getSource().getClient()))
                    .then(literal("on").executes(context -> PANORAMA_CONTROLLER.setRenderPlayerEnabled(context.getSource().getClient(), true)))
                    .then(literal("off").executes(context -> PANORAMA_CONTROLLER.setRenderPlayerEnabled(context.getSource().getClient(), false))))
                .then(literal("export")
                    .executes(context -> PANORAMA_CONTROLLER.exportStatus(context.getSource().getClient()))
                    .then(literal("on").executes(context -> PANORAMA_CONTROLLER.setExportEnabled(context.getSource().getClient(), true)))
                    .then(literal("off").executes(context -> PANORAMA_CONTROLLER.setExportEnabled(context.getSource().getClient(), false)))))
            .then(literal("tp")
                .then(argument("x", DoubleArgumentType.doubleArg())
                    .then(argument("y", DoubleArgumentType.doubleArg())
                        .then(argument("z", DoubleArgumentType.doubleArg())
                            .executes(context -> CAMERA_CONTROLLER.teleport(
                                context.getSource().getClient(),
                                        DoubleArgumentType.getDouble(context, "x"),
                                        DoubleArgumentType.getDouble(context, "y"),
                                DoubleArgumentType.getDouble(context, "z")
                            ))))));
    }

    private static final class SpectatorCameraController {
        private boolean enabled;
        private OtherClientPlayerEntity cameraEntity;
        private ClientWorld cameraWorld;

        private void tick(MinecraftClient client) {
            if (!enabled) {
                return;
            }

            if (client.player == null || client.world == null) {
                disable(client, false);
                return;
            }

            if (cameraEntity == null || cameraWorld != client.world) {
                createOrResetCamera(client.world, client.player);
            }

            if (client.getCameraEntity() != cameraEntity) {
                client.setCameraEntity(cameraEntity);
            }
        }

        private int toggle(MinecraftClient client) {
            return enabled ? disable(client, true) : enable(client);
        }

        private int enable(MinecraftClient client) {
            if (client.player == null || client.world == null) {
                send(client, "Join a world first.");
                return 0;
            }

            createOrResetCamera(client.world, client.player);
            enabled = true;
            client.setCameraEntity(cameraEntity);
            send(client, "Enabled. Teleport with /panshot tp <x> <y> <z>.");
            return 1;
        }

        private int disable(MinecraftClient client, boolean notify) {
            enabled = false;
            cameraEntity = null;
            cameraWorld = null;

            if (client.player != null) {
                client.setCameraEntity(client.player);
            } else {
                client.setCameraEntity(null);
            }

            if (notify) {
                send(client, "Disabled.");
            }
            return 1;
        }

        private int teleport(MinecraftClient client, double x, double y, double z) {
            if (!ensureActive(client)) {
                return 0;
            }

            teleportInternal(x, y, z, cameraEntity.getYaw(), cameraEntity.getPitch());
            send(client, String.format(Locale.ROOT, "Camera teleported to %.2f %.2f %.2f.", x, y, z));
            return 1;
        }

        private int printPosition(MinecraftClient client) {
            if (!ensureActive(client)) {
                return 0;
            }

            send(client, String.format(
                Locale.ROOT,
                "Camera at %.2f %.2f %.2f (yaw %.1f, pitch %.1f).",
                cameraEntity.getX(),
                cameraEntity.getY(),
                cameraEntity.getZ(),
                cameraEntity.getYaw(),
                cameraEntity.getPitch()
            ));
            return 1;
        }

        private boolean ensureActive(MinecraftClient client) {
            if (!enabled || cameraEntity == null) {
                send(client, "Enable camera first with /panshot.");
                return false;
            }

            if (client.player == null || client.world == null) {
                send(client, "Join a world first.");
                return false;
            }

            if (cameraWorld != client.world) {
                createOrResetCamera(client.world, client.player);
                client.setCameraEntity(cameraEntity);
            }
            return true;
        }

        private void createOrResetCamera(ClientWorld world, ClientPlayerEntity player) {
            if (cameraEntity == null || cameraWorld != world) {
                GameProfile profile = new GameProfile(CAMERA_PROFILE_ID, "spectator_camera");
                cameraEntity = new OtherClientPlayerEntity(world, profile);
                cameraWorld = world;
            }

            cameraEntity.noClip = true;
            cameraEntity.setNoGravity(true);
            teleportInternal(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
        }

        private void teleportInternal(double x, double y, double z, float yaw, float pitch) {
            cameraEntity.refreshPositionAndAngles(x, y, z, yaw, pitch);
            cameraEntity.setVelocity(Vec3d.ZERO);
        }

        private void send(MinecraftClient client, String message) {
            if (client.player != null) {
                client.player.sendMessage(Text.literal(MESSAGE_PREFIX + message), false);
            }
        }
    }

    private static final class PanoramaCaptureController implements PanoramaWebServer.StateProvider {
        private static final UUID PANORAMA_PROFILE_ID = UUID.fromString("4f83f6ac-6349-4f15-9f9b-4a0e5c2623ad");
        private static final UUID PANORAMA_RENDER_PLAYER_PROFILE_ID = UUID.fromString("2a89a050-bf8c-4187-b2c3-f1f008f6422f");
        private static final int PANORAMA_RENDER_PLAYER_ENTITY_ID = Integer.MIN_VALUE + 42;
        private static final int PANORAMA_RESOLUTION = 1024;
        private static final int[] CUBEMAP_LAYOUT = {
            3, 1, 4,
            5, 0, 2
        };
        private static final String CUBEMAP_FILE_NAME = "panorama_cubemap.png";

        private volatile boolean running;
        private long tickCounter;
        private long intervalTicks;
        private long nextCycleTick;
        private long cycleStartTick;
        private int completedCycles;
        private int activeFaceIndex = -1;
        private boolean cycleInProgress;
        private int facesScheduledInCycle;
        private int pendingFaceCaptures;
        private long captureSessionId;
        private Vec3d origin = Vec3d.ZERO;
        private float baseYaw;
        private float basePitch;
        private OtherClientPlayerEntity panoramaEntity;
        private ClientWorld panoramaWorld;
        private OtherClientPlayerEntity panoramaRenderPlayerEntity;
        private ClientWorld panoramaRenderPlayerWorld;
        private Framebuffer vanillaMainFramebuffer;
        private SimpleFramebuffer panoramaRenderFramebuffer;
        private final NativeImage[] capturedFaces = new NativeImage[6];
        private volatile byte[] latestCubemapBytes;
        private volatile long latestCubemapTimestamp;
        private volatile boolean exportToDisk;
        private volatile boolean preciseCaptureMode;
        private volatile boolean renderPlayerEnabled;
        private final ExecutorService stitchExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "panshot-stitch");
            thread.setDaemon(true);
            return thread;
        });
        private final AtomicBoolean stitchInFlight = new AtomicBoolean(false);
        private long lastSkippedStitchMessageTick = Long.MIN_VALUE;

        private void tick(MinecraftClient client) {
            tickCounter++;
            if (!running) {
                return;
            }

            if (client.player == null || client.world == null) {
                stopInternal(client, false, "Panorama capture stopped because no world is loaded.");
                return;
            }

            if (panoramaEntity == null || panoramaWorld != client.world) {
                ensurePanoramaEntity(client.world);
            }

            if (!cycleInProgress && tickCounter < nextCycleTick) {
                return;
            }

            try {
                if (!cycleInProgress) {
                    startCycle();
                }

                // 1.21.10 screenshot readback is async; avoid overlapping reads against the same render target.
                if (pendingFaceCaptures > 0) {
                    return;
                }

                if (facesScheduledInCycle >= 6) {
                    return;
                }

                if (!preciseCaptureMode && tickCounter < faceDueTick(facesScheduledInCycle)) {
                    return;
                }

                schedulePanoramaFace(client, facesScheduledInCycle);
            } catch (Exception exception) {
                stopInternal(client, false, null);
                send(client, "Panorama capture failed: " + exception.getMessage());
            }
        }

        private int startAtPlayer(MinecraftClient client, double intervalSeconds) {
            if (client.player == null) {
                send(client, "Join a world first.");
                return 0;
            }

            Vec3d eyePos = client.player.getEyePos();
            return startAt(client, intervalSeconds, eyePos.x, eyePos.y, eyePos.z, client.player.getYaw(), 0.0f);
        }

        private int startAt(MinecraftClient client, double intervalSeconds, double x, double y, double z, float yaw, float pitch) {
            if (client.player == null || client.world == null) {
                send(client, "Join a world first.");
                return 0;
            }

            SINGLE_CONTROLLER.stop(client, false);
            origin = new Vec3d(x, y, z);
            baseYaw = yaw;
            basePitch = clampPitch(pitch);
            intervalTicks = Math.max(1L, Math.round(intervalSeconds * 20.0));
            captureSessionId++;
            running = true;
            completedCycles = 0;
            cycleInProgress = false;
            facesScheduledInCycle = 0;
            pendingFaceCaptures = 0;
            activeFaceIndex = -1;
            clearCapturedFaces();
            nextCycleTick = tickCounter;
            cycleStartTick = tickCounter;
            ensurePanoramaEntity(client.world);
            ensureFramebuffers(client);
            vanillaMainFramebuffer = ((MinecraftClientAccessor)client).spectatorcam$getFramebuffer();

            try {
                String viewerUrl = PANORAMA_WEB_SERVER.ensureStarted(this);
                sendViewerLink(client, viewerUrl);
            } catch (IOException exception) {
                send(client, "Panorama viewer failed to start: " + exception.getMessage());
            }

            send(client, String.format(
                Locale.ROOT,
                "Panorama capture started at %.3f %.3f %.3f every %.2f seconds (yaw %.1f, pitch %.1f, mode %s).",
                x,
                y,
                z,
                intervalTicks / 20.0,
                baseYaw,
                basePitch,
                preciseCaptureMode ? "precise" : "smooth"
            ));
            return 1;
        }

        private int stop(MinecraftClient client, boolean notify) {
            if (!running) {
                if (notify) {
                    send(client, "Panorama capture is not running.");
                }
                return 0;
            }

            stopInternal(client, notify, null);
            return 1;
        }

        private int status(MinecraftClient client) {
            if (!running) {
                send(client, "Panorama capture is not running.");
                return 1;
            }

            if (!preciseCaptureMode && cycleInProgress && activeFaceIndex >= 0) {
                send(client, String.format(
                    Locale.ROOT,
                    "Running: capturing face %d/6 at %.3f %.3f %.3f (yaw %.1f, pitch %.1f, mode %s).",
                    activeFaceIndex + 1,
                    origin.x,
                    origin.y,
                    origin.z,
                    baseYaw,
                    basePitch,
                    preciseCaptureMode ? "precise" : "smooth"
                ));
                return 1;
            }

            double seconds = Math.max(0.0, (nextCycleTick - tickCounter) / 20.0);
            send(client, String.format(
                Locale.ROOT,
                "Running: next cycle in %.2f seconds from %.3f %.3f %.3f (yaw %.1f, pitch %.1f, mode %s).",
                seconds,
                origin.x,
                origin.y,
                origin.z,
                baseYaw,
                basePitch,
                preciseCaptureMode ? "precise" : "smooth"
            ));
            return 1;
        }

        private void startCycle() {
            cycleInProgress = true;
            cycleStartTick = tickCounter;
            facesScheduledInCycle = 0;
            pendingFaceCaptures = 0;
            activeFaceIndex = preciseCaptureMode ? -1 : 0;
            clearCapturedFaces();
        }

        private long faceDueTick(int faceIndex) {
            return cycleStartTick + (intervalTicks * faceIndex) / 6L;
        }

        private void schedulePanoramaFace(MinecraftClient client, int index) {
            long sessionId = captureSessionId;
            pendingFaceCaptures++;
            facesScheduledInCycle++;
            activeFaceIndex = facesScheduledInCycle >= 6 ? -1 : facesScheduledInCycle;

            try (RenderContext context = beginPanoramaRender(client)) {
                renderPanoramaFace(client, index, sessionId);
            } catch (Exception exception) {
                pendingFaceCaptures = Math.max(0, pendingFaceCaptures - 1);
                throw exception;
            }
        }

        private int exportStatus(MinecraftClient client) {
            send(client, "Panorama export is " + (exportToDisk ? "on" : "off") + ".");
            return 1;
        }

        private int setExportEnabled(MinecraftClient client, boolean enabled) {
            exportToDisk = enabled;
            send(client, "Panorama export " + (enabled ? "enabled" : "disabled") + ".");
            return 1;
        }

        private int modeStatus(MinecraftClient client) {
            send(client, "Panorama mode is " + (preciseCaptureMode ? "precise" : "smooth") + ".");
            return 1;
        }

        private int setPreciseCaptureMode(MinecraftClient client, boolean precise) {
            preciseCaptureMode = precise;
            captureSessionId++;
            cycleInProgress = false;
            facesScheduledInCycle = 0;
            pendingFaceCaptures = 0;
            cycleStartTick = 0L;
            activeFaceIndex = -1;
            clearCapturedFaces();
            send(client, "Panorama mode set to " + (precise ? "precise" : "smooth") + ".");
            return 1;
        }

        private int renderPlayerStatus(MinecraftClient client) {
            send(client, "Panorama renderplayer is " + (renderPlayerEnabled ? "on" : "off") + ".");
            return 1;
        }

        private int setRenderPlayerEnabled(MinecraftClient client, boolean enabled) {
            renderPlayerEnabled = enabled;
            if (client.world != null && !enabled) {
                removeEntityIfPresent(client.world, PANORAMA_RENDER_PLAYER_ENTITY_ID);
            }
            panoramaRenderPlayerEntity = null;
            panoramaRenderPlayerWorld = null;
            send(client, "Panorama renderplayer " + (enabled ? "enabled" : "disabled") + ".");
            return 1;
        }

        private RenderContext beginPanoramaRender(MinecraftClient client) {
            ensureFramebuffers(client);

            MinecraftClientAccessor clientAccessor = (MinecraftClientAccessor)client;
            Framebuffer currentFramebuffer = clientAccessor.spectatorcam$getFramebuffer();
            if (vanillaMainFramebuffer == null || vanillaMainFramebuffer == panoramaRenderFramebuffer) {
                vanillaMainFramebuffer = currentFramebuffer;
            }
            Framebuffer mainFramebuffer = currentFramebuffer == panoramaRenderFramebuffer && vanillaMainFramebuffer != null
                ? vanillaMainFramebuffer
                : currentFramebuffer;
            Entity previousCameraEntity = client.getCameraEntity();
            Perspective previousPerspective = client.options.getPerspective();
            int previousFov = client.options.getFov().getValue();
            boolean previousPanoramaMode = client.gameRenderer.isRenderingPanorama();
            Window window = client.getWindow();
            WindowAccessor windowAccessor = (WindowAccessor)(Object)window;
            int previousWindowWidth = window.getWidth();
            int previousWindowHeight = window.getHeight();
            int previousFramebufferWidth = window.getFramebufferWidth();
            int previousFramebufferHeight = window.getFramebufferHeight();
            ClientWorld renderPlayerWorld = null;
            boolean renderPlayerAdded = false;

            try {
                windowAccessor.spectatorcam$setWidth(PANORAMA_RESOLUTION);
                windowAccessor.spectatorcam$setHeight(PANORAMA_RESOLUTION);
                window.setFramebufferWidth(PANORAMA_RESOLUTION);
                window.setFramebufferHeight(PANORAMA_RESOLUTION);

                if (renderPlayerEnabled && client.player != null && client.world != null) {
                    removeEntityIfPresent(client.world, PANORAMA_RENDER_PLAYER_ENTITY_ID);
                    OtherClientPlayerEntity renderPlayer = ensureRenderPlayerEntity(client.world, client.player);
                    syncRenderPlayerEntityState(client.player, renderPlayer);
                    client.world.addEntity(renderPlayer);
                    renderPlayerWorld = client.world;
                    renderPlayerAdded = true;
                }

                clientAccessor.spectatorcam$setFramebuffer(panoramaRenderFramebuffer);
                client.setCameraEntity(panoramaEntity);
                client.options.setPerspective(Perspective.FIRST_PERSON);
                client.options.getFov().setValue(90);
                client.gameRenderer.setRenderingPanorama(true);

                return new RenderContext(
                    client,
                    clientAccessor,
                    mainFramebuffer,
                    previousCameraEntity,
                    previousPerspective,
                    previousFov,
                    previousPanoramaMode,
                    window,
                    windowAccessor,
                    previousWindowWidth,
                    previousWindowHeight,
                    previousFramebufferWidth,
                    previousFramebufferHeight,
                    renderPlayerWorld,
                    renderPlayerAdded
                );
            } catch (RuntimeException exception) {
                try {
                    clientAccessor.spectatorcam$setFramebuffer(mainFramebuffer);
                } catch (RuntimeException ignored) {
                    // Best-effort rollback.
                }
                try {
                    windowAccessor.spectatorcam$setWidth(previousWindowWidth);
                    windowAccessor.spectatorcam$setHeight(previousWindowHeight);
                    window.setFramebufferWidth(previousFramebufferWidth);
                    window.setFramebufferHeight(previousFramebufferHeight);
                } catch (RuntimeException ignored) {
                    // Best-effort rollback.
                }
                try {
                    client.options.getFov().setValue(previousFov);
                    client.options.setPerspective(previousPerspective);
                    if (previousCameraEntity != null) {
                        client.setCameraEntity(previousCameraEntity);
                    } else if (client.player != null) {
                        client.setCameraEntity(client.player);
                    } else {
                        client.setCameraEntity(null);
                    }
                } catch (RuntimeException ignored) {
                    // Best-effort rollback.
                }
                try {
                    client.gameRenderer.setRenderingPanorama(previousPanoramaMode);
                } catch (RuntimeException ignored) {
                    // Best-effort rollback.
                }
                if (renderPlayerAdded && renderPlayerWorld != null) {
                    removeEntityIfPresent(renderPlayerWorld, PANORAMA_RENDER_PLAYER_ENTITY_ID);
                }
                throw exception;
            }
        }

        private void renderPanoramaFace(MinecraftClient client, int index, long sessionId) {
            positionPanoramaEntity(yawForIndex(index), pitchForIndex(index));
            client.gameRenderer.renderWorld(RenderTickCounter.ONE);

            takeScreenshotAsyncFast(client, panoramaRenderFramebuffer, image -> onPanoramaFaceCaptured(client, sessionId, index, image));
        }

        private void onPanoramaFaceCaptured(MinecraftClient client, long sessionId, int index, NativeImage image) {
            boolean activeSession = sessionId == captureSessionId;
            try {
                if (!activeSession || !running) {
                    image.close();
                    return;
                }

                if (capturedFaces[index] != null) {
                    capturedFaces[index].close();
                }
                capturedFaces[index] = image;
            } finally {
                if (activeSession) {
                    pendingFaceCaptures = Math.max(0, pendingFaceCaptures - 1);
                }
            }

            if (!cycleInProgress || facesScheduledInCycle < 6 || pendingFaceCaptures > 0) {
                return;
            }

            cycleInProgress = false;
            activeFaceIndex = -1;
            completedCycles++;
            nextCycleTick = Math.max(tickCounter + 1L, cycleStartTick + intervalTicks);
            submitStitchJob(client, detachCapturedFaces());
        }

        private final class RenderContext implements AutoCloseable {
            private final MinecraftClient client;
            private final MinecraftClientAccessor clientAccessor;
            private final Framebuffer mainFramebuffer;
            private final Entity previousCameraEntity;
            private final Perspective previousPerspective;
            private final int previousFov;
            private final boolean previousPanoramaMode;
            private final Window window;
            private final WindowAccessor windowAccessor;
            private final int previousWindowWidth;
            private final int previousWindowHeight;
            private final int previousFramebufferWidth;
            private final int previousFramebufferHeight;
            private final ClientWorld renderPlayerWorld;
            private final boolean renderPlayerAdded;

            private RenderContext(
                MinecraftClient client,
                MinecraftClientAccessor clientAccessor,
                Framebuffer mainFramebuffer,
                Entity previousCameraEntity,
                Perspective previousPerspective,
                int previousFov,
                boolean previousPanoramaMode,
                Window window,
                WindowAccessor windowAccessor,
                int previousWindowWidth,
                int previousWindowHeight,
                int previousFramebufferWidth,
                int previousFramebufferHeight,
                ClientWorld renderPlayerWorld,
                boolean renderPlayerAdded
            ) {
                this.client = client;
                this.clientAccessor = clientAccessor;
                this.mainFramebuffer = mainFramebuffer;
                this.previousCameraEntity = previousCameraEntity;
                this.previousPerspective = previousPerspective;
                this.previousFov = previousFov;
                this.previousPanoramaMode = previousPanoramaMode;
                this.window = window;
                this.windowAccessor = windowAccessor;
                this.previousWindowWidth = previousWindowWidth;
                this.previousWindowHeight = previousWindowHeight;
                this.previousFramebufferWidth = previousFramebufferWidth;
                this.previousFramebufferHeight = previousFramebufferHeight;
                this.renderPlayerWorld = renderPlayerWorld;
                this.renderPlayerAdded = renderPlayerAdded;
            }

            @Override
            public void close() {
                client.gameRenderer.setRenderingPanorama(previousPanoramaMode);
                windowAccessor.spectatorcam$setWidth(previousWindowWidth);
                windowAccessor.spectatorcam$setHeight(previousWindowHeight);
                window.setFramebufferWidth(previousFramebufferWidth);
                window.setFramebufferHeight(previousFramebufferHeight);
                client.options.getFov().setValue(previousFov);
                client.options.setPerspective(previousPerspective);
                if (previousCameraEntity != null) {
                    client.setCameraEntity(previousCameraEntity);
                } else if (client.player != null) {
                    client.setCameraEntity(client.player);
                } else {
                    client.setCameraEntity(null);
                }
                clientAccessor.spectatorcam$setFramebuffer(mainFramebuffer);
                if (renderPlayerAdded && renderPlayerWorld != null) {
                    removeEntityIfPresent(renderPlayerWorld, PANORAMA_RENDER_PLAYER_ENTITY_ID);
                }
            }
        }

        private void submitStitchJob(MinecraftClient client, NativeImage[] faces) {
            if (!stitchInFlight.compareAndSet(false, true)) {
                closeFaces(faces);
                if (tickCounter - lastSkippedStitchMessageTick >= 100L) {
                    lastSkippedStitchMessageTick = tickCounter;
                    send(client, "Skipped one panorama stitch to keep frame time stable.");
                }
                return;
            }

            stitchExecutor.execute(() -> {
                try {
                    byte[] stitchedBytes = stitchCubemapBytes(faces);
                    long modifiedTime = System.currentTimeMillis();
                    boolean exportSnapshot = exportToDisk;
                    Path exportPath = null;
                    if (exportSnapshot) {
                        Path screenshotsDir = client.runDirectory.toPath().resolve(ScreenshotRecorder.SCREENSHOTS_DIRECTORY);
                        Files.createDirectories(screenshotsDir);
                        exportPath = screenshotsDir.resolve(CUBEMAP_FILE_NAME);
                        Files.write(exportPath, stitchedBytes);
                    }

                    latestCubemapBytes = stitchedBytes;
                    latestCubemapTimestamp = modifiedTime;
                } catch (Exception exception) {
                    client.execute(() -> send(client, "Panorama stitch failed: " + exception.getMessage()));
                } finally {
                    closeFaces(faces);
                    stitchInFlight.set(false);
                }
            });
        }

        private byte[] stitchCubemapBytes(NativeImage[] faces) throws IOException {
            try (NativeImage stitched = new NativeImage(PANORAMA_RESOLUTION * 3, PANORAMA_RESOLUTION * 2, false)) {
                for (int row = 0; row < 2; row++) {
                    for (int col = 0; col < 3; col++) {
                        int faceIndex = CUBEMAP_LAYOUT[row * 3 + col];
                        faces[faceIndex].copyRect(
                            stitched,
                            0,
                            0,
                            col * PANORAMA_RESOLUTION,
                            row * PANORAMA_RESOLUTION,
                            PANORAMA_RESOLUTION,
                            PANORAMA_RESOLUTION,
                            false,
                            false
                        );
                    }
                }
                return encodePngBytes(stitched);
            }
        }

        private NativeImage[] detachCapturedFaces() {
            for (int i = 0; i < capturedFaces.length; i++) {
                if (capturedFaces[i] == null) {
                    throw new IllegalStateException("Missing captured face " + i);
                }
            }

            NativeImage[] cycleFaces = new NativeImage[capturedFaces.length];
            for (int i = 0; i < capturedFaces.length; i++) {
                cycleFaces[i] = capturedFaces[i];
                capturedFaces[i] = null;
            }
            return cycleFaces;
        }

        private float yawForIndex(int index) {
            return switch (index) {
                case 0, 4, 5 -> baseYaw;
                case 1 -> baseYaw + 90.0f;
                case 2 -> baseYaw + 180.0f;
                case 3 -> baseYaw + 270.0f;
                default -> throw new IllegalArgumentException("Unsupported panorama face index: " + index);
            };
        }

        private float pitchForIndex(int index) {
            return switch (index) {
                case 0, 1, 2, 3 -> basePitch;
                case 4 -> clampPitch(basePitch - 90.0f);
                case 5 -> clampPitch(basePitch + 90.0f);
                default -> throw new IllegalArgumentException("Unsupported panorama face index: " + index);
            };
        }

        private float clampPitch(float pitch) {
            return Math.max(-90.0f, Math.min(90.0f, pitch));
        }

        private void ensurePanoramaEntity(ClientWorld world) {
            if (panoramaEntity != null && panoramaWorld == world) {
                return;
            }

            panoramaEntity = new OtherClientPlayerEntity(world, new GameProfile(PANORAMA_PROFILE_ID, "panorama_camera"));
            panoramaEntity.noClip = true;
            panoramaEntity.setNoGravity(true);
            panoramaWorld = world;
        }

        private OtherClientPlayerEntity ensureRenderPlayerEntity(ClientWorld world, ClientPlayerEntity sourcePlayer) {
            if (panoramaRenderPlayerEntity != null && panoramaRenderPlayerWorld == world) {
                return panoramaRenderPlayerEntity;
            }
            panoramaRenderPlayerEntity = createRenderPlayerEntity(
                world,
                PANORAMA_RENDER_PLAYER_PROFILE_ID,
                PANORAMA_RENDER_PLAYER_ENTITY_ID,
                sourcePlayer
            );
            panoramaRenderPlayerWorld = world;
            return panoramaRenderPlayerEntity;
        }

        private void ensureFramebuffers(MinecraftClient client) {
            if (panoramaRenderFramebuffer == null
                || panoramaRenderFramebuffer.textureWidth != PANORAMA_RESOLUTION
                || panoramaRenderFramebuffer.textureHeight != PANORAMA_RESOLUTION) {
                if (panoramaRenderFramebuffer != null) {
                    panoramaRenderFramebuffer.delete();
                }
                panoramaRenderFramebuffer = new SimpleFramebuffer("panshot-panorama", PANORAMA_RESOLUTION, PANORAMA_RESOLUTION, true);
            }
        }

        private void positionPanoramaEntity(float yaw, float pitch) {
            // `origin.y` is treated as camera eye Y; convert to entity base Y for vanilla camera math.
            double entityY = origin.y - panoramaEntity.getStandingEyeHeight();
            panoramaEntity.refreshPositionAndAngles(origin.x, entityY, origin.z, yaw, pitch);
            panoramaEntity.setYaw(yaw);
            panoramaEntity.setPitch(pitch);
            panoramaEntity.lastYaw = yaw;
            panoramaEntity.lastPitch = pitch;
            panoramaEntity.lastX = origin.x;
            panoramaEntity.lastY = entityY;
            panoramaEntity.lastZ = origin.z;
            panoramaEntity.setVelocity(Vec3d.ZERO);
            panoramaEntity.setHeadYaw(yaw);
            panoramaEntity.setBodyYaw(yaw);
            panoramaEntity.lastHeadYaw = yaw;
            panoramaEntity.lastBodyYaw = yaw;
        }

        private void stopInternal(MinecraftClient client, boolean notify, String reason) {
            captureSessionId++;
            running = false;
            intervalTicks = 0L;
            nextCycleTick = 0L;
            cycleStartTick = 0L;
            completedCycles = 0;
            cycleInProgress = false;
            facesScheduledInCycle = 0;
            pendingFaceCaptures = 0;
            activeFaceIndex = -1;
            clearCapturedFaces();
            panoramaEntity = null;
            panoramaWorld = null;
            panoramaRenderPlayerEntity = null;
            panoramaRenderPlayerWorld = null;
            if (vanillaMainFramebuffer != null) {
                try {
                    ((MinecraftClientAccessor)client).spectatorcam$setFramebuffer(vanillaMainFramebuffer);
                } catch (RuntimeException ignored) {
                    // Best-effort rollback.
                }
            }

            if (panoramaRenderFramebuffer != null) {
                panoramaRenderFramebuffer.delete();
                panoramaRenderFramebuffer = null;
            }

            if (reason != null) {
                send(client, reason);
            } else if (notify) {
                send(client, "Panorama capture stopped.");
            }
        }

        private void send(MinecraftClient client, String message) {
            if (client.player != null) {
                client.player.sendMessage(Text.literal(MESSAGE_PREFIX + message), false);
            }
        }

        private void sendViewerLink(MinecraftClient client, String url) {
            if (client.player != null) {
                Text link = Text.literal(url).styled(style -> style
                    .withUnderline(true)
                    .withClickEvent(createOpenUrlClickEvent(url)));
                client.player.sendMessage(Text.literal(MESSAGE_PREFIX + "Panorama viewer: ").append(link), false);
            }
        }

        private void clearCapturedFaces() {
            for (int i = 0; i < capturedFaces.length; i++) {
                if (capturedFaces[i] != null) {
                    capturedFaces[i].close();
                    capturedFaces[i] = null;
                }
            }
        }

        private void closeFaces(NativeImage[] faces) {
            for (int i = 0; i < faces.length; i++) {
                if (faces[i] != null) {
                    faces[i].close();
                    faces[i] = null;
                }
            }
        }

        @Override
        public boolean isPanoramaRunning() {
            return running;
        }

        @Override
        public byte[] getLatestCubemapBytes() {
            return latestCubemapBytes;
        }

        @Override
        public long getLatestCubemapTimestamp() {
            return latestCubemapTimestamp;
        }
    }

    private static final class SingleCaptureController implements SinglePreviewWebServer.StateProvider {
        private static final UUID SINGLE_PROFILE_ID = UUID.fromString("d5d2f96a-8f54-4f75-92f1-a4051512e53b");
        private static final UUID SINGLE_RENDER_PLAYER_PROFILE_ID = UUID.fromString("fb3c2f64-a8d6-4a65-b5fb-c6d58f2ce6ca");
        private static final int SINGLE_RENDER_PLAYER_ENTITY_ID = Integer.MIN_VALUE + 43;
        private static final int DEFAULT_SINGLE_WIDTH = 1024;
        private static final int DEFAULT_SINGLE_HEIGHT = 1024;
        private static final int MIN_SINGLE_DIMENSION = 64;
        private static final int MAX_SINGLE_DIMENSION = 4096;
        private static final int DEFAULT_SINGLE_FOV = 90;
        private static final int MIN_SINGLE_FOV = 1;
        private static final int MAX_SINGLE_FOV = 179;

        private volatile boolean running;
        private long tickCounter;
        private long intervalTicks;
        private long nextCaptureTick;
        private int completedCaptures;
        private boolean capturePending;
        private long captureSessionId;
        private Vec3d origin = Vec3d.ZERO;
        private float yaw;
        private float pitch;
        private OtherClientPlayerEntity singleEntity;
        private ClientWorld singleWorld;
        private OtherClientPlayerEntity singleRenderPlayerEntity;
        private ClientWorld singleRenderPlayerWorld;
        private SimpleFramebuffer singleRenderFramebuffer;
        private int captureWidth = DEFAULT_SINGLE_WIDTH;
        private int captureHeight = DEFAULT_SINGLE_HEIGHT;
        private int captureFov = DEFAULT_SINGLE_FOV;
        private volatile boolean renderPlayerEnabled;
        private volatile byte[] latestImageBytes;
        private volatile long latestImageTimestamp;
        private final ExecutorService encodeExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "panshot-single-encode");
            thread.setDaemon(true);
            return thread;
        });
        private final AtomicBoolean encodeInFlight = new AtomicBoolean(false);
        private long lastSkippedEncodeMessageTick = Long.MIN_VALUE;

        private void tick(MinecraftClient client) {
            tickCounter++;
            if (!running) {
                return;
            }

            if (client.player == null || client.world == null) {
                stopInternal(client, false, "Single preview stopped because no world is loaded.");
                return;
            }

            if (singleEntity == null || singleWorld != client.world) {
                ensureSingleEntity(client.world);
            }

            if (capturePending || tickCounter < nextCaptureTick) {
                return;
            }

            try {
                long sessionId = captureSessionId;
                capturePending = true;
                nextCaptureTick = tickCounter + intervalTicks;
                captureSingleFrame(client, sessionId);
            } catch (Exception exception) {
                stopInternal(client, false, null);
                send(client, "Single preview capture failed: " + exception.getMessage());
            }
        }

        private int startAtPlayer(MinecraftClient client, double intervalSeconds) {
            if (client.player == null) {
                send(client, "Join a world first.");
                return 0;
            }

            Vec3d eyePos = client.player.getEyePos();
            return startAt(client, intervalSeconds, eyePos.x, eyePos.y, eyePos.z, client.player.getYaw(), client.player.getPitch());
        }

        private int startAt(MinecraftClient client, double intervalSeconds, double x, double y, double z, float yaw, float pitch) {
            if (client.player == null || client.world == null) {
                send(client, "Join a world first.");
                return 0;
            }

            PANORAMA_CONTROLLER.stop(client, false);
            origin = new Vec3d(x, y, z);
            this.yaw = yaw;
            this.pitch = clampPitch(pitch);
            intervalTicks = Math.max(1L, Math.round(intervalSeconds * 20.0));
            captureSessionId++;
            running = true;
            completedCaptures = 0;
            capturePending = false;
            nextCaptureTick = tickCounter;
            ensureSingleEntity(client.world);
            ensureFramebuffers(captureWidth, captureHeight);

            try {
                String viewerUrl = SINGLE_WEB_SERVER.ensureStarted(this);
                sendViewerLink(client, viewerUrl);
            } catch (IOException exception) {
                send(client, "Single viewer failed to start: " + exception.getMessage());
            }

            send(client, String.format(
                Locale.ROOT,
                "Single preview started at %.3f %.3f %.3f every %.2f seconds (yaw %.1f, pitch %.1f, %dx%d, fov %d, renderplayer %s).",
                x,
                y,
                z,
                intervalTicks / 20.0,
                this.yaw,
                this.pitch,
                captureWidth,
                captureHeight,
                captureFov,
                renderPlayerEnabled ? "on" : "off"
            ));
            return 1;
        }

        private int stop(MinecraftClient client, boolean notify) {
            if (!running) {
                if (notify) {
                    send(client, "Single preview is not running.");
                }
                return 0;
            }

            stopInternal(client, notify, null);
            return 1;
        }

        private int status(MinecraftClient client) {
            if (!running) {
                send(client, "Single preview is not running.");
                return 1;
            }

            double seconds = Math.max(0.0, (nextCaptureTick - tickCounter) / 20.0);
            send(client, String.format(
                Locale.ROOT,
                "Running: next frame in %.2f seconds from %.3f %.3f %.3f (yaw %.1f, pitch %.1f, frames %d, %dx%d, fov %d, renderplayer %s).",
                seconds,
                origin.x,
                origin.y,
                origin.z,
                yaw,
                pitch,
                completedCaptures,
                captureWidth,
                captureHeight,
                captureFov,
                renderPlayerEnabled ? "on" : "off"
            ));
            return 1;
        }

        private int resolutionStatus(MinecraftClient client) {
            send(client, String.format(
                Locale.ROOT,
                "Single preview resolution is %dx%d.",
                captureWidth,
                captureHeight
            ));
            return 1;
        }

        private int setResolution(MinecraftClient client, int width, int height) {
            captureWidth = clampDimension(width);
            captureHeight = clampDimension(height);
            send(client, String.format(
                Locale.ROOT,
                "Single preview resolution set to %dx%d.",
                captureWidth,
                captureHeight
            ));
            return 1;
        }

        private int fovStatus(MinecraftClient client) {
            send(client, String.format(Locale.ROOT, "Single preview FOV is %d.", captureFov));
            return 1;
        }

        private int setFov(MinecraftClient client, int fov) {
            captureFov = clampFov(fov);
            send(client, String.format(Locale.ROOT, "Single preview FOV set to %d.", captureFov));
            return 1;
        }

        private int renderPlayerStatus(MinecraftClient client) {
            send(client, "Single renderplayer is " + (renderPlayerEnabled ? "on" : "off") + ".");
            return 1;
        }

        private int setRenderPlayerEnabled(MinecraftClient client, boolean enabled) {
            renderPlayerEnabled = enabled;
            send(client, "Single renderplayer " + (enabled ? "enabled" : "disabled") + ".");
            return 1;
        }

        private void captureSingleFrame(MinecraftClient client, long sessionId) {
            try (RenderContext context = beginSingleRender(client)) {
                renderSingleFrame(client, sessionId);
            }
        }

        private RenderContext beginSingleRender(MinecraftClient client) {
            int width = captureWidth;
            int height = captureHeight;
            int targetFov = captureFov;
            ensureFramebuffers(width, height);

            MinecraftClientAccessor clientAccessor = (MinecraftClientAccessor)client;
            GameRendererAccessor gameRendererAccessor = (GameRendererAccessor)client.gameRenderer;
            Framebuffer mainFramebuffer = clientAccessor.spectatorcam$getFramebuffer();
            Entity previousCameraEntity = client.getCameraEntity();
            Perspective previousPerspective = client.options.getPerspective();
            int previousFov = client.options.getFov().getValue();
            boolean previousRenderBlockOutline = gameRendererAccessor.spectatorcam$isRenderBlockOutline();
            float previousFovScale = gameRendererAccessor.spectatorcam$getFovScale();
            float previousOldFovScale = gameRendererAccessor.spectatorcam$getOldFovScale();
            boolean previousPanoramaMode = client.gameRenderer.isRenderingPanorama();
            boolean previousHudHidden = client.options.hudHidden;
            Window window = client.getWindow();
            WindowAccessor windowAccessor = (WindowAccessor)(Object)window;
            int previousWindowWidth = window.getWidth();
            int previousWindowHeight = window.getHeight();
            int previousFramebufferWidth = window.getFramebufferWidth();
            int previousFramebufferHeight = window.getFramebufferHeight();
            ClientWorld renderPlayerWorld = null;
            boolean renderPlayerAdded = false;

            windowAccessor.spectatorcam$setWidth(width);
            windowAccessor.spectatorcam$setHeight(height);
            window.setFramebufferWidth(width);
            window.setFramebufferHeight(height);

            clientAccessor.spectatorcam$setFramebuffer(singleRenderFramebuffer);

            client.setCameraEntity(singleEntity);
            client.options.setPerspective(Perspective.FIRST_PERSON);
            client.options.getFov().setValue(targetFov);
            client.options.hudHidden = true;
            gameRendererAccessor.spectatorcam$setRenderBlockOutline(false);
            client.gameRenderer.setRenderingPanorama(false);
            if (renderPlayerEnabled && client.player != null && client.world != null) {
                removeEntityIfPresent(client.world, SINGLE_RENDER_PLAYER_ENTITY_ID);
                OtherClientPlayerEntity renderPlayer = ensureRenderPlayerEntity(client.world, client.player);
                syncRenderPlayerEntityState(client.player, renderPlayer);
                client.world.addEntity(renderPlayer);
                renderPlayerWorld = client.world;
                renderPlayerAdded = true;
            }

            return new RenderContext(
                client,
                clientAccessor,
                gameRendererAccessor,
                mainFramebuffer,
                previousCameraEntity,
                previousPerspective,
                previousFov,
                previousRenderBlockOutline,
                previousFovScale,
                previousOldFovScale,
                previousPanoramaMode,
                previousHudHidden,
                window,
                windowAccessor,
                previousWindowWidth,
                previousWindowHeight,
                previousFramebufferWidth,
                previousFramebufferHeight,
                renderPlayerWorld,
                renderPlayerAdded
            );
        }

        private void renderSingleFrame(MinecraftClient client, long sessionId) {
            positionSingleEntity(yaw, pitch);
            client.gameRenderer.renderWorld(RenderTickCounter.ONE);
            takeScreenshotAsyncFast(client, singleRenderFramebuffer, image -> onSingleFrameCaptured(client, sessionId, image));
        }

        private void onSingleFrameCaptured(MinecraftClient client, long sessionId, NativeImage image) {
            if (sessionId != captureSessionId || !running) {
                image.close();
                return;
            }

            completedCaptures++;
            capturePending = false;
            submitEncodeJob(client, image);
        }

        private final class RenderContext implements AutoCloseable {
            private final MinecraftClient client;
            private final MinecraftClientAccessor clientAccessor;
            private final GameRendererAccessor gameRendererAccessor;
            private final Framebuffer mainFramebuffer;
            private final Entity previousCameraEntity;
            private final Perspective previousPerspective;
            private final int previousFov;
            private final boolean previousRenderBlockOutline;
            private final float previousFovScale;
            private final float previousOldFovScale;
            private final boolean previousPanoramaMode;
            private final boolean previousHudHidden;
            private final Window window;
            private final WindowAccessor windowAccessor;
            private final int previousWindowWidth;
            private final int previousWindowHeight;
            private final int previousFramebufferWidth;
            private final int previousFramebufferHeight;
            private final ClientWorld renderPlayerWorld;
            private final boolean renderPlayerAdded;

            private RenderContext(
                MinecraftClient client,
                MinecraftClientAccessor clientAccessor,
                GameRendererAccessor gameRendererAccessor,
                Framebuffer mainFramebuffer,
                Entity previousCameraEntity,
                Perspective previousPerspective,
                int previousFov,
                boolean previousRenderBlockOutline,
                float previousFovScale,
                float previousOldFovScale,
                boolean previousPanoramaMode,
                boolean previousHudHidden,
                Window window,
                WindowAccessor windowAccessor,
                int previousWindowWidth,
                int previousWindowHeight,
                int previousFramebufferWidth,
                int previousFramebufferHeight,
                ClientWorld renderPlayerWorld,
                boolean renderPlayerAdded
            ) {
                this.client = client;
                this.clientAccessor = clientAccessor;
                this.gameRendererAccessor = gameRendererAccessor;
                this.mainFramebuffer = mainFramebuffer;
                this.previousCameraEntity = previousCameraEntity;
                this.previousPerspective = previousPerspective;
                this.previousFov = previousFov;
                this.previousRenderBlockOutline = previousRenderBlockOutline;
                this.previousFovScale = previousFovScale;
                this.previousOldFovScale = previousOldFovScale;
                this.previousPanoramaMode = previousPanoramaMode;
                this.previousHudHidden = previousHudHidden;
                this.window = window;
                this.windowAccessor = windowAccessor;
                this.previousWindowWidth = previousWindowWidth;
                this.previousWindowHeight = previousWindowHeight;
                this.previousFramebufferWidth = previousFramebufferWidth;
                this.previousFramebufferHeight = previousFramebufferHeight;
                this.renderPlayerWorld = renderPlayerWorld;
                this.renderPlayerAdded = renderPlayerAdded;
            }

            @Override
            public void close() {
                client.gameRenderer.setRenderingPanorama(previousPanoramaMode);
                windowAccessor.spectatorcam$setWidth(previousWindowWidth);
                windowAccessor.spectatorcam$setHeight(previousWindowHeight);
                window.setFramebufferWidth(previousFramebufferWidth);
                window.setFramebufferHeight(previousFramebufferHeight);
                client.options.getFov().setValue(previousFov);
                gameRendererAccessor.spectatorcam$setRenderBlockOutline(previousRenderBlockOutline);
                gameRendererAccessor.spectatorcam$setFovScale(previousFovScale);
                gameRendererAccessor.spectatorcam$setOldFovScale(previousOldFovScale);
                client.options.hudHidden = previousHudHidden;
                client.options.setPerspective(previousPerspective);
                if (previousCameraEntity != null) {
                    client.setCameraEntity(previousCameraEntity);
                } else if (client.player != null) {
                    client.setCameraEntity(client.player);
                } else {
                    client.setCameraEntity(null);
                }
                clientAccessor.spectatorcam$setFramebuffer(mainFramebuffer);
                if (renderPlayerAdded && renderPlayerWorld != null) {
                    removeEntityIfPresent(renderPlayerWorld, SINGLE_RENDER_PLAYER_ENTITY_ID);
                }
            }
        }

        private void submitEncodeJob(MinecraftClient client, NativeImage image) {
            if (!encodeInFlight.compareAndSet(false, true)) {
                image.close();
                if (tickCounter - lastSkippedEncodeMessageTick >= 100L) {
                    lastSkippedEncodeMessageTick = tickCounter;
                    send(client, "Skipped one single-frame update to keep frame time stable.");
                }
                return;
            }

            encodeExecutor.execute(() -> {
                try (NativeImage capturedImage = image) {
                    latestImageBytes = encodePngBytes(capturedImage);
                    latestImageTimestamp = System.currentTimeMillis();
                } catch (Exception exception) {
                    client.execute(() -> send(client, "Single preview encode failed: " + exception.getMessage()));
                } finally {
                    encodeInFlight.set(false);
                }
            });
        }

        private float clampPitch(float value) {
            return Math.max(-90.0f, Math.min(90.0f, value));
        }

        private int clampDimension(int value) {
            return Math.max(MIN_SINGLE_DIMENSION, Math.min(MAX_SINGLE_DIMENSION, value));
        }

        private int clampFov(int fov) {
            return Math.max(MIN_SINGLE_FOV, Math.min(MAX_SINGLE_FOV, fov));
        }

        private void ensureSingleEntity(ClientWorld world) {
            if (singleEntity != null && singleWorld == world) {
                return;
            }

            singleEntity = new OtherClientPlayerEntity(world, new GameProfile(SINGLE_PROFILE_ID, "single_preview_camera"));
            singleEntity.noClip = true;
            singleEntity.setNoGravity(true);
            singleWorld = world;
        }

        private OtherClientPlayerEntity ensureRenderPlayerEntity(ClientWorld world, ClientPlayerEntity sourcePlayer) {
            if (singleRenderPlayerEntity != null && singleRenderPlayerWorld == world) {
                return singleRenderPlayerEntity;
            }

            singleRenderPlayerEntity = createRenderPlayerEntity(
                world,
                SINGLE_RENDER_PLAYER_PROFILE_ID,
                SINGLE_RENDER_PLAYER_ENTITY_ID,
                sourcePlayer
            );
            singleRenderPlayerWorld = world;
            return singleRenderPlayerEntity;
        }

        private void ensureFramebuffers(int width, int height) {
            if (singleRenderFramebuffer == null
                || singleRenderFramebuffer.textureWidth != width
                || singleRenderFramebuffer.textureHeight != height) {
                if (singleRenderFramebuffer != null) {
                    singleRenderFramebuffer.delete();
                }
                singleRenderFramebuffer = new SimpleFramebuffer("panshot-single", width, height, true);
            }
        }

        private void positionSingleEntity(float yaw, float pitch) {
            double entityY = origin.y - singleEntity.getStandingEyeHeight();
            singleEntity.refreshPositionAndAngles(origin.x, entityY, origin.z, yaw, pitch);
            singleEntity.setYaw(yaw);
            singleEntity.setPitch(pitch);
            singleEntity.lastYaw = yaw;
            singleEntity.lastPitch = pitch;
            singleEntity.lastX = origin.x;
            singleEntity.lastY = entityY;
            singleEntity.lastZ = origin.z;
            singleEntity.setVelocity(Vec3d.ZERO);
            singleEntity.setHeadYaw(yaw);
            singleEntity.setBodyYaw(yaw);
            singleEntity.lastHeadYaw = yaw;
            singleEntity.lastBodyYaw = yaw;
        }

        private void stopInternal(MinecraftClient client, boolean notify, String reason) {
            captureSessionId++;
            running = false;
            intervalTicks = 0L;
            nextCaptureTick = 0L;
            completedCaptures = 0;
            capturePending = false;
            singleEntity = null;
            singleWorld = null;
            singleRenderPlayerEntity = null;
            singleRenderPlayerWorld = null;

            if (singleRenderFramebuffer != null) {
                singleRenderFramebuffer.delete();
                singleRenderFramebuffer = null;
            }

            if (reason != null) {
                send(client, reason);
            } else if (notify) {
                send(client, "Single preview stopped.");
            }
        }

        private void send(MinecraftClient client, String message) {
            if (client.player != null) {
                client.player.sendMessage(Text.literal(MESSAGE_PREFIX + message), false);
            }
        }

        private void sendViewerLink(MinecraftClient client, String url) {
            if (client.player != null) {
                Text link = Text.literal(url).styled(style -> style
                    .withUnderline(true)
                    .withClickEvent(createOpenUrlClickEvent(url)));
                client.player.sendMessage(Text.literal(MESSAGE_PREFIX + "Single viewer: ").append(link), false);
            }
        }

        @Override
        public boolean isSingleRunning() {
            return running;
        }

        @Override
        public byte[] getLatestImageBytes() {
            return latestImageBytes;
        }

        @Override
        public long getLatestImageTimestamp() {
            return latestImageTimestamp;
        }
    }
}
