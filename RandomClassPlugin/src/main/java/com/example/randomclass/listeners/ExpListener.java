package com.example.randomclass.listeners;

import com.example.randomclass.ClassManager;
import com.example.randomclass.PlayerClass;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;

public class ExpListener implements Listener {

    private final ClassManager classManager;

    public ExpListener(ClassManager classManager) {
        this.classManager = classManager;
    }

    @EventHandler
    public void onLevelChange(org.bukkit.event.player.PlayerLevelChangeEvent event) {
        // 이제 마크 레벨업이 직업 레벨업을 의미하지 않으므로 로직 비움
    }

    @EventHandler
    public void onExpChange(PlayerExpChangeEvent event) {
        Player player = event.getPlayer();
        PlayerClass pClass = classManager.getPlayerClass(player);
        
        if (pClass == PlayerClass.ADVENTURER) {
            int level = classManager.getPlayerClassLevel(player);
            double bonusPerLevel = classManager.getFarmerExpMultiplier(); // 기본 0.1
            double multiplier = 1.0 + (bonusPerLevel * level);
            
            int originalAmount = event.getAmount();
            if (originalAmount <= 0) return;

            int newAmount = (int) Math.ceil(originalAmount * multiplier);
            event.setAmount(newAmount);
        }
    }

    @EventHandler
    public void onAnimalDeath(EntityDeathEvent event) {
        // 모험가가 동물을 처치하면 경험치 드롭량 50% 추가
        if (!(event.getEntity() instanceof Animals)) return;

        Player killer = event.getEntity().getKiller();
        if (killer == null) return;

        if (classManager.getPlayerClass(killer) == PlayerClass.ADVENTURER) {
            int original = event.getDroppedExp();
            if (original > 0) {
                double multiplier = classManager.getAdventurerAnimalExpMultiplier();
                event.setDroppedExp((int) Math.ceil(original * multiplier));
            }
        }
    }
}

