package com.example.randomclass.listeners;

import com.example.randomclass.ClassManager;
import com.example.randomclass.PlayerClass;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerQuitEvent;
import com.example.randomclass.RandomClassPlugin;

public class PlayerJoinListener implements Listener {

    private final RandomClassPlugin plugin;
    private final ClassManager classManager;

    public PlayerJoinListener(RandomClassPlugin plugin, ClassManager classManager) {
        this.plugin = plugin;
        this.classManager = classManager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 플레이어의 프로필 캐시 로드 (및 필요 시 yml 초기화)
        classManager.loadPlayerProfile(player);

        PlayerClass pClass = classManager.getPlayerClass(player);

        if (pClass == PlayerClass.NONE) {
            player.sendMessage(classManager.getMessage("job.none_message", "&b당신은 현재 직업이 없는 &f백수&b입니다."));
            player.sendMessage(classManager.getMessage("job.none_guide", "&e/job &a을 입력하여 직업을 뽑아보세요!"));
            player.setGameMode(org.bukkit.GameMode.ADVENTURE);
        } else {
            player.sendMessage(classManager.getMessage("job.current_job", "&6현재 직업: &f{job}")
                    .replace("{job}", pClass.getDisplayName()));
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            classManager.applyClassBuffs(player);
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        PlayerClass pClass = classManager.getPlayerClass(player);
        if (pClass != PlayerClass.NONE) {
            // 플레이어 리스폰 직후에 포션 효과를 주면 무시될 수 있으므로 1틱 지연 후 적용
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> classManager.applyClassBuffs(player), 1L);
        }
    }

    @EventHandler
    public void onPlayerConsume(PlayerItemConsumeEvent event) {
        if (event.getItem().getType() == Material.MILK_BUCKET) {
            Player player = event.getPlayer();
            PlayerClass pClass = classManager.getPlayerClass(player);
            if (pClass != PlayerClass.NONE) {
                // 우유로 인해 버프가 지워진 직후에 다시 부여하기 위해 1틱 지연
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> classManager.applyClassBuffs(player), 1L);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        java.util.UUID uuid = player.getUniqueId();

        classManager.cleanupPlayerData(player);
        if (plugin.getClassAbilityListener() != null)
            plugin.getClassAbilityListener().cleanup(uuid);
        if (plugin.getMageAbilityListener() != null)
            plugin.getMageAbilityListener().cleanup(uuid);
    }
}
