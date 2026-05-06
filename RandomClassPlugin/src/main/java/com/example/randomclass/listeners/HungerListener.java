package com.example.randomclass.listeners;

import com.example.randomclass.ClassManager;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExhaustionEvent;

public class HungerListener implements Listener {

    private final ClassManager classManager;


    public HungerListener(ClassManager classManager) {
        this.classManager = classManager;
    }

    @EventHandler
    public void onPlayerExhaustion(EntityExhaustionEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getEntity();

        // 일반 허기 시스템 배율 적용
        double multiplier = classManager.getHungerMultiplier(player);
        event.setExhaustion((float) (event.getExhaustion() * multiplier));
    }
}
