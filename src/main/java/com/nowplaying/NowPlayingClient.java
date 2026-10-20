package com.nowplaying;

import com.nowplaying.gui.NowPlayingScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The registered F8 key toggles the Now Playing screen from anywhere (in a world, title screen, menus).
 */
public class NowPlayingClient implements ClientModInitializer {
    private static boolean f8WasDown;

    @Override
    public void onInitializeClient() {
        NowPlayingSys.LOGGER.info("NowPlayingSys client initialized; F8 opens the media screen.");
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long window = client.getWindow().getWindow();
            boolean f8Down = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F8) == GLFW.GLFW_PRESS;
            if (f8Down && !f8WasDown) {
                NowPlayingSys.LOGGER.info("Now Playing key pressed");
                toggleScreen(client);
            }
            f8WasDown = f8Down;
        });
    }

    private static void toggleScreen(Minecraft minecraft) {
        Screen current = getCurrentScreen(minecraft);
        if (current instanceof NowPlayingScreen) {
            setCurrentScreen(minecraft, null);
        } else if (!(current instanceof ChatScreen)) { // don't hijack F8 while typing in chat
            setCurrentScreen(minecraft, new NowPlayingScreen());
        }
    }

    // ------------------------------------------------------------------ screen access

    private static Screen getCurrentScreen(Minecraft minecraft) {
        Object gui = minecraft.gui;
        if (gui != null) {
            try {
                Method screenMethod = gui.getClass().getMethod("screen");
                Object screen = screenMethod.invoke(gui);
                if (screen instanceof Screen s) {
                    return s;
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall back to older client field access.
            }
        }

        try {
            Field screenField = minecraft.getClass().getField("screen");
            Object screen = screenField.get(minecraft);
            if (screen instanceof Screen s) {
                return s;
            }
        } catch (ReflectiveOperationException ignored) {
            // No compatible screen accessor exists on this runtime.
        }

        return null;
    }

    private static void setCurrentScreen(Minecraft minecraft, Screen screen) {
        minecraft.setScreen(screen);
    }
}
