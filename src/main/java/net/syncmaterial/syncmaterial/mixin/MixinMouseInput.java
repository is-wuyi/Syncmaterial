package net.syncmaterial.syncmaterial.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.syncmaterial.syncmaterial.client.gui.HudEditOverlay;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MixinMouseInput {
    @Shadow @Final private MinecraftClient client;

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void onMouseButton(long window, int button, int action, int mods, CallbackInfo ci) {
        if (window != this.client.getWindow().getHandle()) return;
        if (this.client.currentScreen != null) return;

        HudEditOverlay overlay = HudEditOverlay.getInstance();
        if (!overlay.isActive()) return;

        double scale = this.client.getWindow().getScaleFactor();
        double mouseX = this.client.mouse.getX() / scale;
        double mouseY = this.client.mouse.getY() / scale;

        if (action == 1) { // press
            if (overlay.onMouseClicked(mouseX, mouseY, button)) {
                ci.cancel();
            }
        } else if (action == 0) { // release
            if (overlay.onMouseReleased(mouseX, mouseY, button)) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "onCursorPos", at = @At("HEAD"), cancellable = true)
    private void onCursorPos(long window, double x, double y, CallbackInfo ci) {
        if (window != this.client.getWindow().getHandle()) return;
        if (this.client.currentScreen != null) return;

        HudEditOverlay overlay = HudEditOverlay.getInstance();
        if (!overlay.isActive()) return;

        double scale = this.client.getWindow().getScaleFactor();
        double mouseX = x / scale;
        double mouseY = y / scale;

        // 当左键按住时模拟 dragged 事件
        if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
            if (overlay.onMouseDragged(mouseX, mouseY, 0, 0, 0)) {
                ci.cancel();
            }
        }
    }
}
