package com.nowplaying.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;

public class NowPlayingToast {
    private static final SystemToastId TOAST_ID = new SystemToastId(9001L);

    public static void show(Minecraft client, String message) {
        if (client == null) {
            return;
        }

        ToastComponent toastComponent = getToastComponent(client);
        if (toastComponent == null) {
            return;
        }

        SystemToast.addOrUpdate(
            toastComponent,
            TOAST_ID,
            Component.literal("Now Playing"),
            Component.literal(message)
        );
    }

    private static ToastComponent getToastComponent(Minecraft client) {
        Object gui = client.gui;
        if (gui != null) {
            try {
                Method toastComponentMethod = gui.getClass().getMethod("getToastComponent");
                Object component = toastComponentMethod.invoke(gui);
                if (component instanceof ToastComponent tc) {
                    return tc;
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall back to older Minecraft accessor.
            }
        }

        try {
            Method getToastsMethod = client.getClass().getMethod("getToasts");
            Object component = getToastsMethod.invoke(client);
            if (component instanceof ToastComponent tc) {
                return tc;
            }
        } catch (ReflectiveOperationException ignored) {
            // No compatible toast manager accessor exists on this runtime.
        }

        return null;
    }
}
