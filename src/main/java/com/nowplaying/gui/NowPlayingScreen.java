package com.nowplaying.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.nowplaying.MediaDetector;
import com.nowplaying.MediaDetector.MediaMetadata;
import com.nowplaying.NowPlayingSys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NowPlayingScreen extends Screen {
    private static final int PANEL_WIDTH = 392;
    private static final int PANEL_HEIGHT = 228;
    private static final int PANEL_INSET = 8;
    private static final int ALBUM_SIZE = 144;
    private static final int PROGRESS_WIDTH = 182;
    private static final int PROGRESS_HEIGHT = 6;
    /** Right column is 192 wide and text starts 8 in; leave a margin on the right too (and 1px for the title shadow). */
    private static final int TEXT_MAX_WIDTH = 168;

    private static final Identifier FALLBACK_ALBUM_ART =
            Identifier.fromNamespaceAndPath(NowPlayingSys.MOD_ID, "textures/gui/album_art.png");
    private static final ExecutorService ART_LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "NowPlaying-AlbumArtLoader");
        thread.setDaemon(true);
        return thread;
    });

    private Button prevButton;
    private Button playPauseButton;
    private Button nextButton;
    /** Last media snapshot applied to the controls; compared by identity to avoid redundant updates. */
    private MediaMetadata shown;

    // Album art state. Only one load runs at a time and the latest wanted URL wins, so superseded
    // results are closed instead of leaking, and a failing URL is not retried every poll.
    private String wantedArtUrl = "";
    private String appliedArtUrl = "";
    private String failedArtUrl = "";
    private String inFlightArtUrl = "";
    private CompletableFuture<AlbumArtPayload> albumArtFuture;
    private Identifier dynamicArtTextureId;
    private int artWidth = ALBUM_SIZE;
    private int artHeight = ALBUM_SIZE;

    private record AlbumArtPayload(String sourceUrl, NativeImage image, int width, int height) {
    }

    public NowPlayingScreen() {
        super(Component.literal("Now Playing"));
    }

    @Override
    protected void init() {
        MediaDetector.start();
        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;
        int rightPaneX = panelX + 184;
        prevButton = addRenderableWidget(Button.builder(Component.literal("Prev"), b -> MediaDetector.sendCommand("Previous"))
                .bounds(rightPaneX + 8, panelY + 156, 56, 20).tooltip(Tooltip.create(Component.literal("Previous Track"))).build());
        playPauseButton = addRenderableWidget(Button.builder(Component.literal("Play"), b -> MediaDetector.sendCommand("PlayPause"))
                .bounds(rightPaneX + 70, panelY + 156, 56, 20).tooltip(Tooltip.create(Component.literal("Play / Pause"))).build());
        nextButton = addRenderableWidget(Button.builder(Component.literal("Next"), b -> MediaDetector.sendCommand("Next"))
                .bounds(rightPaneX + 132, panelY + 156, 56, 20).tooltip(Tooltip.create(Component.literal("Next Track"))).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh Metadata"), b -> MediaDetector.refreshAsync())
                .bounds(rightPaneX + 8, panelY + 182, 180, 20)
                .tooltip(Tooltip.create(Component.literal("Force a fresh metadata + album art fetch"))).build());
        shown = null;
        refreshFromSnapshot();
    }

    @Override
    public void tick() {
        super.tick();
        refreshFromSnapshot();
        pollAlbumArt();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x = (width - PANEL_WIDTH) / 2;
        int y = (height - PANEL_HEIGHT) / 2;
        graphics.fill(x, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, 0xFF131A27);
        graphics.fill(x + PANEL_INSET, y + PANEL_INSET, x + PANEL_WIDTH - PANEL_INSET, y + PANEL_HEIGHT - PANEL_INSET, 0xFF1C2C43);
        graphics.fill(x + 16, y + 16, x + 176, y + PANEL_HEIGHT - 16, 0xCC101B2A);
        graphics.fill(x + 184, y + 16, x + 376, y + PANEL_HEIGHT - 16, 0xCC0F1826);
        graphics.fill(x + 180, y + 20, x + 181, y + PANEL_HEIGHT - 20, 0xFF2F4361);
        graphics.fill(x + 24, y + 24, x + 24 + ALBUM_SIZE, y + 24 + ALBUM_SIZE, 0xFF2A3F5E);
        Identifier art = dynamicArtTextureId == null ? FALLBACK_ALBUM_ART : dynamicArtTextureId;
        float aspect = (float) artWidth / artHeight;
        int drawWidth = ALBUM_SIZE;
        int drawHeight = ALBUM_SIZE;
        if (aspect > 1.0f) {
            drawHeight = Math.max(1, Math.round(ALBUM_SIZE / aspect));
        } else {
            drawWidth = Math.max(1, Math.round(ALBUM_SIZE * aspect));
        }
        int artX = x + 24 + (ALBUM_SIZE - drawWidth) / 2;
        int artY = y + 24 + (ALBUM_SIZE - drawHeight) / 2;
        graphics.blit(RenderPipelines.GUI_TEXTURED, art, artX, artY, 0, 0, drawWidth, drawHeight,
                artWidth, artHeight, artWidth, artHeight);
        graphics.centeredText(font, "Now Playing", x + 24 + ALBUM_SIZE / 2, y + 176, 0xFFD9E6F7);

        MediaMetadata m = shown;
        boolean hasTrack = m != null && m.hasTrack();
        String title = hasTrack ? fit(m.title()) : "No media detected";
        String artist = hasTrack ? fit(m.artist() == null || m.artist().isBlank() ? "Unknown Artist" : m.artist()) : "Open a player to begin";
        String source = hasTrack ? fit("Source: " + m.source()) : "Source: None";
        String status = hasTrack ? fit("Status: " + statusLabel(m.state())) : "Status: Idle";
        graphics.text(font, title, x + 200, y + 28, 0xFFF1F7FF, true);
        graphics.text(font, artist, x + 200, y + 48, 0xFFB6C3D6);
        graphics.text(font, source, x + 200, y + 72, 0xFF8FA8C8);
        graphics.text(font, status, x + 200, y + 92, 0xFF93A1B5);
        graphics.fill(x + 192, y + 122, x + 192 + PROGRESS_WIDTH, y + 122 + PROGRESS_HEIGHT, 0xFF1A3557);
        float progress = hasTrack && m.hasTimeline() ? m.progress() : 0f;
        int progressWidth = Math.clamp(Math.round(PROGRESS_WIDTH * progress), 0, PROGRESS_WIDTH);
        graphics.fill(x + 192, y + 122, x + 192 + progressWidth, y + 122 + PROGRESS_HEIGHT, 0xFF57A6FF);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /** Applies control changes only when the media snapshot changes. */
    private void refreshFromSnapshot() {
        MediaMetadata snapshot = MediaDetector.getCurrentlyPlayingMetadata();
        if (snapshot != shown) {
            shown = snapshot;
            applyMetadata(snapshot);
        }
    }

    private void applyMetadata(MediaMetadata metadata) {
        if (!metadata.hasTrack()) {
            playPauseButton.setMessage(Component.literal("Play"));
            setControlsEnabled(false);
            wantedArtUrl = "";
            setFallbackAlbumArt();
            return;
        }

        playPauseButton.setMessage(Component.literal(metadata.isPlaying() ? "Pause" : "Play"));
        setControlsEnabled(MediaDetector.canControl());

        if (metadata.hasArtUrl()) {
            queueAlbumArtLoad(metadata.artUrl());
        } else {
            wantedArtUrl = "";
            setFallbackAlbumArt();
        }
    }

    /** Truncates with "..." so long titles can't spill out of the panel (TruncatedTextComponent didn't). */
    private static String fit(String text) {
        Font font = Minecraft.getInstance().font;
        if (font.width(text) <= TEXT_MAX_WIDTH) {
            return text;
        }
        String ellipsis = "...";
        return font.plainSubstrByWidth(text, TEXT_MAX_WIDTH - font.width(ellipsis)) + ellipsis;
    }

    private static String statusLabel(MediaDetector.PlaybackState state) {
        return switch (state) {
            case PLAYING -> "Playing";
            case PAUSED -> "Paused";
            case STOPPED -> "Stopped";
            case UNKNOWN -> "Active";
        };
    }

    // ------------------------------------------------------------------ album art

    private void queueAlbumArtLoad(String artUrl) {
        wantedArtUrl = artUrl;
        if (artUrl.equals(appliedArtUrl) || artUrl.equals(failedArtUrl)) {
            return;
        }
        startArtLoadIfIdle();
    }

    private void startArtLoadIfIdle() {
        if (albumArtFuture != null || wantedArtUrl.isBlank()) {
            return;
        }
        String url = wantedArtUrl;
        inFlightArtUrl = url;
        albumArtFuture = CompletableFuture.supplyAsync(() -> loadAlbumArt(url), ART_LOADER);
    }

    private void pollAlbumArt() {
        if (albumArtFuture == null || !albumArtFuture.isDone()) {
            return;
        }
        CompletableFuture<AlbumArtPayload> finished = albumArtFuture;
        albumArtFuture = null;
        String url = inFlightArtUrl;

        try {
            AlbumArtPayload payload = finished.join();
            if (payload.sourceUrl().equals(wantedArtUrl)) {
                applyAlbumArt(payload);
            } else {
                payload.image().close(); // superseded while loading
            }
        } catch (CompletionException e) {
            NowPlayingSys.LOGGER.warn("Album art load failed for {}: {}", url, String.valueOf(e.getCause()));
            failedArtUrl = url;
            if (url.equals(wantedArtUrl)) {
                setFallbackAlbumArt();
            }
        }

        // The wanted URL may have changed while this one was loading.
        if (!wantedArtUrl.isBlank() && !wantedArtUrl.equals(appliedArtUrl) && !wantedArtUrl.equals(failedArtUrl)) {
            startArtLoadIfIdle();
        }
    }

    /** Runs on the loader thread. Supports http(s) and local file: URLs (many Linux players use file:). */
    private static AlbumArtPayload loadAlbumArt(String artUrl) {
        URLConnection connection = null;
        try {
            URI uri = URI.create(artUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("file")) {
                throw new IOException("Unsupported album art URL scheme: " + scheme);
            }

            connection = uri.toURL().openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", "NowPlayingSys/1.0");

            try (InputStream in = connection.getInputStream()) {
                BufferedImage original = ImageIO.read(in);
                if (original == null) {
                    throw new IOException("Failed to decode image");
                }
                try {
                    return new AlbumArtPayload(artUrl, toNativeImage(original), original.getWidth(), original.getHeight());
                } finally {
                    original.flush();
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new CompletionException(e);
        } finally {
            if (connection instanceof HttpURLConnection http) {
                http.disconnect();
            }
        }
    }

    private static NativeImage toNativeImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        try {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = image.getRGB(x, y);
                    int a = (argb >> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    nativeImage.setPixelABGR(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            return nativeImage;
        } catch (RuntimeException e) {
            nativeImage.close();
            throw e;
        }
    }

    private void applyAlbumArt(AlbumArtPayload payload) {
        Minecraft client = Minecraft.getInstance();
        releaseDynamicAlbumArt();

        Identifier textureId = Identifier.fromNamespaceAndPath(NowPlayingSys.MOD_ID,
                "dynamic/album_art_" + Integer.toUnsignedString(payload.sourceUrl().hashCode()));
        client.getTextureManager().register(textureId,
                new LinearDynamicTexture(payload.image()));

        dynamicArtTextureId = textureId;
        artWidth = payload.width();
        artHeight = payload.height();
        appliedArtUrl = payload.sourceUrl();
    }

    private void setFallbackAlbumArt() {
        releaseDynamicAlbumArt();
        appliedArtUrl = "";
        artWidth = ALBUM_SIZE;
        artHeight = ALBUM_SIZE;
    }

    /** Releasing through the TextureManager closes the texture (and its image) as well. */
    private void releaseDynamicAlbumArt() {
        if (dynamicArtTextureId != null) {
            Minecraft.getInstance().getTextureManager().release(dynamicArtTextureId);
            dynamicArtTextureId = null;
        }
    }

    // ------------------------------------------------------------------ screen plumbing

    private void setControlsEnabled(boolean enabled) {
        if (prevButton != null) prevButton.active = enabled;
        if (playPauseButton != null) playPauseButton.active = enabled;
        if (nextButton != null) nextButton.active = enabled;
    }

    @Override
    public void removed() {
        MediaDetector.stop();
        // Any in-flight load finishes on its own thread; its result is dropped with the screen.
        albumArtFuture = null;
        releaseDynamicAlbumArt();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** DynamicTexture defaults to nearest-neighbor sampling; album art benefits from linear filtering. */
    private static final class LinearDynamicTexture extends DynamicTexture {
        private LinearDynamicTexture(NativeImage image) {
            super(() -> "NowPlayingAlbumArt", image);
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }

}
