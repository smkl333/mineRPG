package com.example.randomclass.listeners;

import com.example.randomclass.ClassManager;
import com.example.randomclass.PlayerClass;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.enchantments.Enchantment;

import java.util.Random;

public class BlockBreakListener implements Listener {

    private final ClassManager classManager;
    private final Plugin plugin;
    private final Random random = new Random();

    public BlockBreakListener(ClassManager classManager, Plugin plugin) {
        this.classManager = classManager;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        // 플레이어가 직접 설치한 블록은 메타데이터로 기록 (어뷰징 방지)
        event.getBlock().setMetadata("player_placed", new FixedMetadataValue(plugin, true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        PlayerClass pClass = classManager.getPlayerClass(player);

        if (pClass != PlayerClass.ADVENTURER) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool == null || !tool.hasItemMeta() || !tool.getItemMeta().hasEnchant(Enchantment.FORTUNE)) return;

        int fortuneLevel = tool.getItemMeta().getEnchantLevel(Enchantment.FORTUNE);
        if (fortuneLevel <= 0) return;

        Block block = event.getBlock();
        Material type = block.getType();

        // [1] 농작물 커스텀 행운 적용 (밀, 비트루트)
        if (type == Material.WHEAT || type == Material.BEETROOTS) {
            if (block.getBlockData() instanceof Ageable) {
                Ageable ageable = (Ageable) block.getBlockData();
                if (ageable.getAge() == ageable.getMaximumAge()) {
                    Material dropMat = (type == Material.WHEAT) ? Material.WHEAT : Material.BEETROOT;
                    int extraDrop = getCropExtraDrop(fortuneLevel);
                    if (extraDrop > 0) {
                        block.getWorld().dropItemNaturally(block.getLocation(), new ItemStack(dropMat, extraDrop));
                    }
                    // 다 자란 농작물 수확 시 경험치 지급 (config 범위 기반)
                    int minExp = classManager.getAdventurerHarvestExpMin();
                    int maxExp = classManager.getAdventurerHarvestExpMax();
                    int expDrop = minExp + random.nextInt(Math.max(1, maxExp - minExp + 1));
                    player.giveExp(expDrop);
                }
            }
            return;
        }

        // [1-1] 사탕수수 파괴 시 경험치 지급 (플레이어가 직접 설치한 블록만 제외)
        if (type == Material.SUGAR_CANE) {
            // 맨 아래 블록(근원)이 플레이어가 설치한 것이 아닐 때만 경험치 지급
            if (!block.hasMetadata("player_placed")) {
                int minExp = classManager.getAdventurerHarvestExpMin();
                int maxExp = classManager.getAdventurerHarvestExpMax();
                int expDrop = minExp + random.nextInt(Math.max(1, maxExp - minExp + 1));
                player.giveExp(expDrop);
            }
            return;
        }

        // [2] 나무 커스텀 행운 적용 (원목 -> 판자 추가 드랍)
        String typeName = type.name();
        if (typeName.endsWith("_LOG") || typeName.endsWith("_STEM") || typeName.endsWith("_WOOD") || typeName.endsWith("_HYPHAE")) {
            // 설치된 블록이면 보너스 판자 제외
            if (block.hasMetadata("player_placed")) return;

            Material plankMat = getPlankMaterial(typeName);
            if (plankMat != null) {
                int extraPlanks = getWoodExtraDrop(fortuneLevel);
                if (extraPlanks > 0) {
                    block.getWorld().dropItemNaturally(block.getLocation(), new ItemStack(plankMat, extraPlanks));
                }
            }
        }
    }

    private int getCropExtraDrop(int fortuneLevel) {
        // 행운 1: 30%로 1개 추가
        // 행운 2: 60%로 1개 추가
        // 행운 3: 100%로 1개 추가, 30%로 2개 추가
        double chance = random.nextDouble();
        if (fortuneLevel >= 3) {
            return (chance < 0.3) ? 2 : 1;
        } else if (fortuneLevel == 2) {
            return (chance < 0.6) ? 1 : 0;
        } else {
            return (chance < 0.3) ? 1 : 0;
        }
    }

    private int getWoodExtraDrop(int fortuneLevel) {
        // 행운 1: 50% 확률로 1개
        // 행운 2: 100% 확률로 1개, 50% 확률로 2개
        // 행운 3: 100% 확률로 2개
        if (fortuneLevel >= 3) return 2;
        if (fortuneLevel == 2) return random.nextBoolean() ? 2 : 1;
        return random.nextBoolean() ? 1 : 0;
    }

    private Material getPlankMaterial(String logName) {
        String prefix;
        if (logName.endsWith("_LOG")) {
            prefix = logName.replace("_LOG", "");
        } else if (logName.endsWith("_STEM")) {
            prefix = logName.replace("_STEM", "");
        } else if (logName.endsWith("_WOOD")) {
            prefix = logName.replace("_WOOD", "");
        } else if (logName.endsWith("_HYPHAE")) {
            prefix = logName.replace("_HYPHAE", "");
        } else {
            return null;
        }

        // STRIPPED_OAK -> OAK
        if (prefix.startsWith("STRIPPED_")) {
            prefix = prefix.replace("STRIPPED_", "");
        }

        try {
            return Material.valueOf(prefix + "_PLANKS");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
