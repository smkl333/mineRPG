package com.example.randomclass.listeners;

import com.example.randomclass.ClassManager;
import com.example.randomclass.PlayerClass;
import com.example.randomclass.RandomClassPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MageAbilityListener implements Listener {

    private final ClassManager classManager;
    private final RandomClassPlugin plugin;
    private final Map<UUID, Long> wandCooldowns = new HashMap<>();
    private final Map<UUID, Long> spellbookCooldowns = new HashMap<>();

    public MageAbilityListener(RandomClassPlugin plugin, ClassManager classManager) {
        this.plugin = plugin;
        this.classManager = classManager;
    }

    public void cleanup(UUID uuid) {
        wandCooldowns.remove(uuid);
        spellbookCooldowns.remove(uuid);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || !classManager.isJobItem(item))
            return;

        PlayerClass pClass = classManager.getPlayerClass(player);
        if (pClass != PlayerClass.MAGE)
            return;

        // 마법사 지팡이 (순간이동)
        if (item.getType() == Material.BLAZE_ROD) {
            event.setCancelled(true);
            long now = System.currentTimeMillis();
            long lastUsed = wandCooldowns.getOrDefault(player.getUniqueId(), 0L);
            int wandCooldown = getWandCooldown(player);
            long wandCooldownMs = wandCooldown * 1000L;
            if (now - lastUsed < wandCooldownMs) {
                return;
            }

            if (teleportMage(player)) {
                wandCooldowns.put(player.getUniqueId(), now);
                startCooldownDisplay(player, item.getType(), wandCooldown, "순간이동");
            }
        }
        // 마법서 (TNT 소환)
        else if (item.getType() == Material.ENCHANTED_BOOK) {
            event.setCancelled(true);
            long now = System.currentTimeMillis();
            long lastUsed = spellbookCooldowns.getOrDefault(player.getUniqueId(), 0L);
            int spellCooldown = getSpellbookCooldown(player);
            long spellCooldownMs = spellCooldown * 1000L;
            if (now - lastUsed < spellCooldownMs) {
                return;
            }

            spellbookCooldowns.put(player.getUniqueId(), now);
            spawnMageTNT(player);
            startCooldownDisplay(player, item.getType(), spellCooldown, "TNT 소환");
        }
    }

    /**
     * 쿨타임 동안 해당 아이템 스택의 개수를 남은 초로 업데이트.
     * 쿨타임 종료 시 개수를 1로 복원하고 채팅 알림 1회 전송.
     */
    private void startCooldownDisplay(Player player, Material material, int cooldownSeconds, String skillName) {
        new org.bukkit.scheduler.BukkitRunnable() {
            int remaining = cooldownSeconds;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }

                ItemStack targetItem = findJobItem(player, material);

                if (remaining > 0) {
                    // 남은 쿨타임(초)을 아이템 개수로 표현
                    if (targetItem != null) {
                        targetItem.setAmount(Math.min(remaining, 64));
                    }
                    remaining--;
                } else {
                    // 쿨타임 종료: 개수 1로 복원 + 채팅 알림 1회
                    if (targetItem != null) {
                        targetItem.setAmount(1);
                    }
                    player.sendMessage(classManager.getMessage("prefix.mage", "&d[마법사] ") + classManager
                            .getMessage("skill.ready", "&a스킬을 사용할 수 있습니다!").replace("%skill%", skillName));
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 20L); // 매 1초(20틱)마다 실행
    }

    /**
     * 플레이어 인벤토리에서 jobItem 중 특정 Material을 찾아 반환.
     */
    private ItemStack findJobItem(Player player, Material material) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material && classManager.isJobItem(stack)) {
                return stack;
            }
        }
        return null;
    }

    /** config.yml에서 지팡이 쿨타임(초) 읽기 (레벨별 감소 적용) */
    private int getWandCooldown(Player player) {
        int base = classManager.getMageWandCooldown();
        int level = classManager.getPlayerClassLevel(player);
        return (int) (base * classManager.getMageCooldownMultiplier(level));
    }

    /** config.yml에서 마법서 쿨타임(초) 읽기 (레벨별 감소 적용) */
    private int getSpellbookCooldown(Player player) {
        int base = classManager.getMageSpellbookCooldown();
        int level = classManager.getPlayerClassLevel(player);
        return (int) (base * classManager.getMageCooldownMultiplier(level));
    }

    private void spawnMageTNT(Player player) {
        Location spawnLoc = player.getEyeLocation().add(player.getLocation().getDirection().multiply(1.5));
        @SuppressWarnings("all")
        TNTPrimed tnt = player.getWorld().spawn(spawnLoc, TNTPrimed.class);

        tnt.setFuseTicks(classManager.getMageTntFuseTicks());
        tnt.setVelocity(player.getLocation().getDirection().multiply(0.8));
        tnt.setMetadata("mage_tnt", new FixedMetadataValue(plugin, true));
        tnt.setMetadata("mage_owner_uuid", new FixedMetadataValue(plugin, player.getUniqueId().toString()));
        player.sendMessage(classManager.getMessage("prefix.mage", "&d[마법사] ")
                + classManager.getMessage("skill.mage_tnt", "&d익스플로전!"));
    }

    private boolean teleportMage(Player player) {
        Location eyeLoc = player.getEyeLocation();
        Vector direction = eyeLoc.getDirection();

        double distance = classManager.getMageTeleportDistance();

        // 시야 광선 추적 (엔티티 무시, 블록만 충돌)
        RayTraceResult result = player.getWorld().rayTraceBlocks(eyeLoc, direction, distance,
                org.bukkit.FluidCollisionMode.NEVER, true);

        Location targetLoc;
        if (result != null && result.getHitBlock() != null) {
            // 벽에 맞은 경우, 맞은 지점 바로 앞
            targetLoc = result.getHitPosition().toLocation(player.getWorld());
            // 플레이어가 서있을 공간을 위해 뒤로 조금 무름
            targetLoc.subtract(direction.clone().multiply(0.5));
        } else {
            // 안 막혔으면 설정된 거리만큼 앞
            targetLoc = eyeLoc.clone().add(direction.multiply(distance));
        }

        targetLoc.setYaw(player.getLocation().getYaw());
        targetLoc.setPitch(player.getLocation().getPitch());

        // 안전한 위치 찾기 (현재 위치부터 반경 1블록)
        Location safeLoc = findSafeLocation(targetLoc);
        if (safeLoc != null) {
            player.teleport(safeLoc);
            player.sendMessage(classManager.getMessage("prefix.mage", "&d[마법사] ")
                    + classManager.getMessage("skill.mage_teleport", "&d공간을 도약했습니다."));
            return true;
        } else {
            player.sendMessage(classManager.getMessage("skill.unsafe_location", "&c이동할 수 있는 안전한 공간이 없습니다."));
            return false;
        }
    }

    private Location findSafeLocation(Location center) {
        Location best = null;
        double minDistance = Double.MAX_VALUE;

        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 2; y++) {
                for (int z = -1; z <= 1; z++) {
                    Location check = center.clone().add(x, y, z);
                    check.setX(check.getBlockX() + 0.5);
                    check.setZ(check.getBlockZ() + 0.5);

                    Block legBlock = check.getBlock();
                    Block headBlock = check.clone().add(0, 1, 0).getBlock();

                    // 발과 머리 부분이 비어있고(통과 가능하고)
                    if (legBlock.isPassable() && headBlock.isPassable()) {
                        double dist = check.distanceSquared(center);
                        if (dist < minDistance) {
                            minDistance = dist;
                            best = check;
                        }
                    }
                }
            }
        }
        return best;
    }
}
