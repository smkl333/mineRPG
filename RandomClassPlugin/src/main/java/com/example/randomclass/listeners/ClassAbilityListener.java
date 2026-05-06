package com.example.randomclass.listeners;

import com.example.randomclass.ClassManager;
import com.example.randomclass.PlayerClass;
import com.example.randomclass.RandomClassPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.util.Vector;

public class ClassAbilityListener implements Listener {

    private final ClassManager classManager;
    private final RandomClassPlugin plugin;
    private final Map<UUID, Long> farmerTeleportCooldowns = new HashMap<>();
    private final Map<UUID, Long> adventurerRegenFieldCooldowns = new HashMap<>();
    private final Map<UUID, Long> warriorDoubleJumpCooldowns = new HashMap<>();
    private final Map<UUID, Long> warriorArrowStormCooldowns = new HashMap<>();

    public ClassAbilityListener(RandomClassPlugin plugin, ClassManager classManager) {
        this.plugin = plugin;
        this.classManager = classManager;
    }

    public void cleanup(UUID uuid) {
        farmerTeleportCooldowns.remove(uuid);
        adventurerRegenFieldCooldowns.remove(uuid);
        warriorDoubleJumpCooldowns.remove(uuid);
        warriorArrowStormCooldowns.remove(uuid);
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        // [추가] 애로우 스톰 화살 데미지 처리
        if (event.getDamager() instanceof Arrow) {
            Arrow arrow = (Arrow) event.getDamager();
            if (arrow.hasMetadata("arrow_storm_arrow")) {
                int level = arrow.getMetadata("arrow_storm_level").get(0).asInt();
                double damage = classManager.getWarriorArrowStormDamage(level);
                event.setDamage(damage);
                return;
            }
        }

        // [공통] 레벨업/전직 축하용 폭죽 데미지 무효화
        if (event.getDamager() instanceof org.bukkit.entity.Firework) {
            org.bukkit.entity.Firework fw = (org.bukkit.entity.Firework) event.getDamager();
            if (fw.hasMetadata("no_damage")) {
                event.setCancelled(true);
                return;
            }
        }

        // [마법사] 지팡이 TNT 데미지 조절 (레벨별 차등 & 본인 면역)
        if (event.getDamager() instanceof org.bukkit.entity.TNTPrimed) {
            org.bukkit.entity.TNTPrimed tnt = (org.bukkit.entity.TNTPrimed) event.getDamager();
            if (tnt.hasMetadata("mage_tnt")) {
                String ownerUuidStr = tnt.hasMetadata("mage_owner_uuid")
                        ? tnt.getMetadata("mage_owner_uuid").get(0).asString()
                        : "";

                // 1. 본인 데미지 무효화
                if (event.getEntity() instanceof Player
                        && ((Player) event.getEntity()).getUniqueId().toString().equals(ownerUuidStr)) {
                    event.setCancelled(true);
                    return;
                }

                // 2. 피격 대상에 따른 데미지 보정
                if (event.getEntity() instanceof Player || event.getEntity() instanceof org.bukkit.entity.Monster
                        || event.getEntity() instanceof org.bukkit.entity.Animals) {
                    double multiplier = 0.33; // 기본 (Lv.1~2)

                    // 소환사의 레벨에 따른 배율 결정
                    if (!ownerUuidStr.isEmpty()) {
                        Player owner = plugin.getServer().getPlayer(java.util.UUID.fromString(ownerUuidStr));
                        if (owner != null) {
                            int level = classManager.getPlayerClassLevel(owner);
                            multiplier = classManager.getMageTntMultiplier(level);
                        }
                    }
                    event.setDamage(event.getDamage() * multiplier);
                }
            }
        }

        // [모험가 10레벨 능력] 몬스터에게 죽을 위기 시 텔레포트
        if (event.getEntity() instanceof Player) {
            Player victim = (Player) event.getEntity();
            PlayerClass victimClass = classManager.getPlayerClass(victim);
            int victimLevel = classManager.getPlayerClassLevel(victim);

            if (victimClass == PlayerClass.ADVENTURER && victimLevel >= 10 && event.getDamager() instanceof Monster) {
                if (victim.getHealth() + victim.getAbsorptionAmount() <= event.getFinalDamage()) {

                    // 쿨타임 체크
                    long now = System.currentTimeMillis();
                    long lastUsed = farmerTeleportCooldowns.getOrDefault(victim.getUniqueId(), 0L);
                    int cooldownSec = plugin.getConfig().getInt("cooldowns.adventurer_teleport", 180);
                    if (now - lastUsed < cooldownSec * 1000L) {
                        return; // 쿨타임 중이면 능력 발동 안함
                    }

                    event.setCancelled(true);

                    Location spawnLoc = victim.getRespawnLocation();
                    if (spawnLoc == null)
                        spawnLoc = victim.getWorld().getSpawnLocation();

                    victim.teleport(spawnLoc);
                    // 기존 속성 초기화 (최신 API의 MAX_HEALTH 사용)
                    org.bukkit.attribute.AttributeInstance healthAttr = victim
                            .getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                    if (healthAttr != null)
                        victim.setHealth(healthAttr.getValue());

                    victim.sendMessage(classManager.getMessage("skill.adventurer_escape",
                            "&c[위기 탈출] &f치명적인 데미지를 입어 스폰 지점으로 긴급 이동했습니다!"));
                    victim.playSound(victim.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);

                    // 쿨타임 시작
                    farmerTeleportCooldowns.put(victim.getUniqueId(), now);
                    startCooldownDisplay(victim, Material.DIAMOND_AXE, cooldownSec, "위기 탈출");
                }
            }

            // [추가] 모험가 10레벨 '포화' 능력 피격 시 30초 쿨타임 (피격될 때마다 초기화)
            if (victimClass == PlayerClass.ADVENTURER && victimLevel >= 10) {
                classManager.triggerAdventurerCombatCooldown(victim);
            }
        }

        // 공격자 기반 로직
        if (!(event.getDamager() instanceof Player))
            return;

        Player player = (Player) event.getDamager();
        PlayerClass pClass = classManager.getPlayerClass(player);
        int level = classManager.getPlayerClassLevel(player);

        // [공통] 몬스터(Monster)는 모든 직업이 자유롭게 때릴 수 있음
        if (event.getEntity() instanceof Monster) {
            // [전사 전용 능력] 몬스터에게 슬로우 부여 (5레벨: 슬로우 I, 10레벨: 슬로우 II)
            if (pClass == PlayerClass.WARRIOR && level >= 5 && event.getEntity() instanceof LivingEntity) {
                LivingEntity victim = (LivingEntity) event.getEntity();
                int amplifier = (level >= 10) ? 1 : 0;
                int duration = classManager.getWarriorSlowDuration();
                victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, duration, amplifier));
            }
            return; // 몬스터 타격은 여기서 종료 (취소되지 않음)
        }
    }

    @EventHandler
    public void onEntityDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Monster))
            return;

        Player killer = event.getEntity().getKiller();
        if (killer == null)
            return;

        PlayerClass pClass = classManager.getPlayerClass(killer);
        if (pClass == PlayerClass.WARRIOR || pClass == PlayerClass.MAGE) {
            // 1. 직업 레벨 상승권 드랍 (config 확률 기반)
            if (Math.random() < classManager.getLevelUpTicketChance()) {
                event.getDrops().add(classManager.createLevelUpTicket());
            }

            // 2. 찢어진 상승권 조각 드랍 (config 확률 기반)
            if (Math.random() < classManager.getTornTicketChance()) {
                event.getDrops().add(classManager.createTornTicket());
            }
        }
    }

    @EventHandler
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        if (event.getEntity() != null && event.getEntity().hasMetadata("mage_tnt")) {
            event.blockList().clear(); // 블록 파괴 방지
        }
    }

    @EventHandler
    public void onFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        PlayerClass pClass = classManager.getPlayerClass(player);

        if (pClass == PlayerClass.WARRIOR || pClass == PlayerClass.MAGE) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + pClass.getDisplayName() + "은(는) 낚시를 할 수 없습니다!");
        }
    }

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
                    if (targetItem != null) {
                        targetItem.setAmount(Math.min(remaining, 64)); // 64개 제한
                    }
                    remaining--;
                } else {
                    if (targetItem != null) {
                        targetItem.setAmount(1);
                    }
                    player.sendMessage(ChatColor.GREEN + "[모험가] " + ChatColor.YELLOW + skillName
                            + ChatColor.GREEN + " 능력을 다시 사용할 수 있습니다!");
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private ItemStack findJobItem(Player player, Material material) {
        // 도끼 스킬은 재질이 변할 수 있으므로 _AXE 계열 전체를 허용
        if (material == Material.DIAMOND_AXE) {
            for (ItemStack stack : player.getInventory().getContents()) {
                if (stack != null && classManager.isJobItem(stack)) {
                    String typeName = stack.getType().name();
                    if (typeName.endsWith("_AXE")) {
                        return stack;
                    }
                }
            }
            return null;
        }

        // 그 외 스킬은 정확한 재질 일치로 탐색
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material && classManager.isJobItem(stack)) {
                return stack;
            }
        }
        return null;
    }

    @EventHandler
    public void onPlayerToggleFlight(org.bukkit.event.player.PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (classManager.getPlayerClass(player) != PlayerClass.WARRIOR)
            return;
        if (classManager.getPlayerClassLevel(player) < 10)
            return;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || player.getGameMode() == org.bukkit.GameMode.SPECTATOR)
            return;

        event.setCancelled(true);
        player.setAllowFlight(false);
        player.setFlying(false);

        // 쿨타임 체크
        long now = System.currentTimeMillis();
        long lastUsed = warriorDoubleJumpCooldowns.getOrDefault(player.getUniqueId(), 0L);
        int cooldownSec = classManager.getWarriorDoubleJumpCooldown();
        if (now - lastUsed < cooldownSec * 1000L)
            return;

        warriorDoubleJumpCooldowns.put(player.getUniqueId(), now);

        // 점프 효과 (현재 보는 방향으로 강력하게 돌진 및 도약)
        org.bukkit.util.Vector v = player.getLocation().getDirection().multiply(0.9).setY(0.5);
        player.setVelocity(v);

        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_SHOOT, 1.0f, 1.5f);
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_BAT_TAKEOFF, 0.5f, 1.2f);
    }

    @EventHandler
    public void onPlayerMove(org.bukkit.event.player.PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (classManager.getPlayerClass(player) != PlayerClass.WARRIOR)
            return;
        if (classManager.getPlayerClassLevel(player) < 10)
            return;

        if (((org.bukkit.entity.Entity) player).isOnGround() && !player.getAllowFlight()) {
            // 쿨타임이 지났을 때만 비행(더블 점프 시도) 허용
            long now = System.currentTimeMillis();
            long lastUsed = warriorDoubleJumpCooldowns.getOrDefault(player.getUniqueId(), 0L);
            int cooldownSec = classManager.getWarriorDoubleJumpCooldown();

            if (now - lastUsed >= cooldownSec * 1000L) {
                player.setAllowFlight(true);
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
            return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || !classManager.isJobItem(item))
            return;

        // 전사의 화살(Arrow) 아이템 우클릭 체크
        PlayerClass playerClass = classManager.getPlayerClass(player);
        int level = classManager.getPlayerClassLevel(player);

        // 모험가 재생 필드: 포션 우클릭 시 발동
        if (item.getType() == Material.POTION && playerClass == PlayerClass.ADVENTURER && level >= 1) {
            event.setCancelled(true);

            long now = System.currentTimeMillis();
            long lastUsed = adventurerRegenFieldCooldowns.getOrDefault(player.getUniqueId(), 0L);
            int cooldownSec = classManager.getAdventurerRegenFieldCooldownSeconds(level);
            if (now - lastUsed < cooldownSec * 1000L) {
                return;
            }

            spawnAdventurerRegenField(player, level);
            adventurerRegenFieldCooldowns.put(player.getUniqueId(), now);
            startCooldownDisplay(player, Material.POTION, cooldownSec, "재생 필드");
            return;
        }

        if (item.getType() != Material.ARROW || playerClass != PlayerClass.WARRIOR)
            return;

        if (level < 4)
            return;

        // 쿨타임 체크
        long now = System.currentTimeMillis();
        long lastUsed = warriorArrowStormCooldowns.getOrDefault(player.getUniqueId(), 0L);
        int cooldownSec = classManager.getWarriorArrowStormCooldown();
        long diff = now - lastUsed;

        if (diff < cooldownSec * 1000L) {
            // 쿨타임 중일 때는 아무 메시지도 띄우지 않고 중단
            return;
        }

        warriorArrowStormCooldowns.put(player.getUniqueId(), now);
        fireArrowStorm(player, level);

        // 화살 개수로 쿨타임 표시 시작
        new org.bukkit.scheduler.BukkitRunnable() {
            int remaining = cooldownSec;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    this.cancel();
                    return;
                }

                // 매초 인벤토리에서 화살(직업 아이템)을 새로 찾아 업데이트
                ItemStack targetItem = null;
                for (ItemStack stack : player.getInventory().getContents()) {
                    if (stack != null && stack.getType() == Material.ARROW && classManager.isJobItem(stack)) {
                        targetItem = stack;
                        break;
                    }
                }

                if (remaining > 0) {
                    if (targetItem != null)
                        targetItem.setAmount(remaining);
                    remaining--;
                } else {
                    if (targetItem != null)
                        targetItem.setAmount(1);
                    player.sendMessage(classManager.getMessage("prefix.warrior", "&6[전사] ")
                            + classManager.getMessage("skill.ready", "&a%skill% 스킬을 사용할 수 있습니다!")
                                    .replace("%skill%", "애로우 스톰"));
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 20L); // 1초(20틱)마다 실행

        player.sendMessage(classManager.getMessage("prefix.warrior", "&6[전사] ")
                + classManager.getMessage("skill.warrior_arrow_storm", "&6애로우 스톰 발동!"));
    }

    private void fireArrowStorm(Player player, int level) {
        int count = classManager.getWarriorArrowStormCount();
        Location loc = player.getLocation().add(0, 0.9, 0);

        double angleStep = 360.0 / count;
        for (int i = 0; i < count; i++) {
            double angle = Math.toRadians(i * angleStep);
            Vector direction = new Vector(Math.cos(angle), 0.1, Math.sin(angle)).normalize();

            @SuppressWarnings("all")
            Arrow arrow = player.getWorld().spawn(loc, Arrow.class);

            arrow.setShooter(player);
            arrow.setVelocity(direction.multiply(2.0));
            arrow.setMetadata("arrow_storm_arrow", new FixedMetadataValue(plugin, true));
            arrow.setMetadata("arrow_storm_level", new FixedMetadataValue(plugin, level));
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        }

        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ARROW_SHOOT, 1.0f, 0.5f);
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.5f);
    }

    private void spawnAdventurerRegenField(Player player, int level) {
        int durationSec = classManager.getAdventurerRegenFieldDurationSeconds(level);
        int regenAmp = classManager.getAdventurerRegenFieldAmplifier(level);
        float radius = (float) classManager.getAdventurerRegenFieldRadius(level);

        AreaEffectCloud cloud = player.getWorld().spawn(player.getLocation(), AreaEffectCloud.class);
        cloud.setParticle(Particle.HEART);
        cloud.setRadius(radius);
        cloud.setDuration(durationSec * 20);
        cloud.setWaitTime(0);
        cloud.setReapplicationDelay(20);
        cloud.addCustomEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, regenAmp, false, true), true);

        player.sendMessage(classManager.getMessage("prefix.adventurer", "&a[모험가] ")
                + ChatColor.GREEN + "재생 필드를 전개했습니다! (" + durationSec + "초, 재생 " + (regenAmp + 1) + ")");
        player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.BLOCK_BELL_USE, 1.0f, 1.1f);
    }
}
