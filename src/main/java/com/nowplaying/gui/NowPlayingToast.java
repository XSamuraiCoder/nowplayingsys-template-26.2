package com.nowplaying.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.SystemToast.SystemToastIds;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;

public class NowPlayingToast {
    private static final SystemToastIds TOAST_ID = SystemToastIds.PERIODIC_NOTIFICATION;

    public static void show(Minecraft client, String message) {
        if (client == null) {
            return;
        }

        ToastComponent toastManager = getToastComponent(client);
        if (toastManager == null) {
            return;
        }

        SystemToast.addOrUpdate(
            toastManager,
            TOAST_ID,
            Component.literal("Now Playing"),
            Component.literal(message)
        );
    }

    private static ToastComponent getToastComponent(Minecraft client) {
        Object gui = client.gui;
        if (gui != null) {
            try {
                Method toastManagerMethod = gui.getClass().getMethod("getToastComponent");
                Object manager = toastManagerMethod.invoke(gui);
                if (manager instanceof ToastComponent tm) {
                    return tm;
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall back to older Minecraft accessor.
            }
        }

        try {
            Method getToastManagerMethod = client.getClass().getMethod("getToasts");
            Object manager = getToastManagerMethod.invoke(client);
            if (manager instanceof ToastComponent tm) {
                return tm;
            }
        } catch (ReflectiveOperationException ignored) {
            // No compatible toast manager accessor exists on this runtime.
        }

        return null;
    }
}
