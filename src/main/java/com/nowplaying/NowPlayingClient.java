package com.nowplaying;

import com.mojang.blaze3d.platform.InputConstants;
import com.nowplaying.gui.NowPlayingScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * A registered key toggles the Now Playing screen from anywhere (in a world, title screen, menus).
 */
public class NowPlayingClient implements ClientModInitializer {
    private static KeyMapping openScreenKey;
    private static boolean f8WasDown;

    @Override
    public void onInitializeClient() {
        openScreenKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.nowplayingsys.open_screen",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_F8,
                "category.nowplayingsys.controls"));
        NowPlayingSys.LOGGER.info("NowPlayingSys client initialized; F8 opens the media screen.");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long window = client.getWindow().getWindow();
            boolean f8Down = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F8) == GLFW.GLFW_PRESS;
            boolean mappingPressed = openScreenKey.consumeClick();
            boolean f8Pressed = f8Down && !f8WasDown;
            f8WasDown = f8Down;
            if (mappingPressed || f8Pressed) {
                NowPlayingSys.LOGGER.info("Now Playing key pressed");
                toggleScreen(client);
            }
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
