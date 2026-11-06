package com.hwell.hwelladdon.mixin;

import com.hwell.hwelladdon.modules.HwellAutoBuyShop;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonNetworkHandler.class)
public class GrimBypassMixin {

    private boolean isBypassing = false;

    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
    private void onSendPacket(Packet<?> packet, CallbackInfo ci) {
        if (isBypassing) return;

        if (packet instanceof ClickSlotC2SPacket && HwellAutoBuyShop.isBuying) {
            isBypassing = true;
            try {
                ClientCommonNetworkHandler handler = (ClientCommonNetworkHandler) (Object) this;
                MinecraftClient mc = MinecraftClient.getInstance();
                
                if (mc.player != null) {
                    boolean isSprinting = mc.player.isSprinting();

                    // 1. Nếu đang chạy, gửi packet dừng chạy để Grim không chặn click
                    if (isSprinting) {
                        handler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
                    }

                    // 2. Gửi Input rỗng (báo hủy mọi phím WASD)
                    handler.sendPacket(new PlayerInputC2SPacket(new PlayerInput(false, false, false, false, false, false, false)));
                    
                    // 3. Gửi Click thật (Lúc này Grim thấy bạn đứng yên, không chạy -> Cho phép click)
                    handler.sendPacket(packet);
                    
                    // 4. Khôi phục lại Input thật (để bạn tiếp tục chạy bằng phím)
                    handler.sendPacket(new PlayerInputC2SPacket(mc.player.input.playerInput));
                    
                    // 5. Khôi phục lại trạng thái chạy (nếu trước đó đang chạy)
                    if (isSprinting) {
                        handler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_SPRINTING));
                    }
                }
            } finally {
                isBypassing = false;
            }
            
            ci.cancel(); 
        }
    }
}