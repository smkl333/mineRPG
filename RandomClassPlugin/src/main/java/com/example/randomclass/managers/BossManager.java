package com.example.randomclass.managers;

import com.example.randomclass.RandomClassPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Giant;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.Entity;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.io.File;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;

public class BossManager {

    private final RandomClassPlugin plugin;
    private FileConfiguration bossConfig;
    private File bossFile;

    private Zombie currentBoss;
    private BossBar bossBar;
    private Location spawnLocation;
    private final NamespacedKey bossKey;

    private double maxHealth;
    private double attackCooldown;
    private double damage;
    private int pullCooldown;
    private double pullRadius;
    private double resetRadius;
    private int respawnTimeMinutes;

    private int pullTaskTick = 0;
    private int resetTaskTick = 0;
    private boolean isOnCooldown = false;
    private boolean isSpawnNotified = false;

    private double lifestealRatio;
    private double berserkTriggerRatio;
    private double berserkLifestealRatio;
    private String berserkNamePrefix;
    private double berserkAttackCooldown;
    private double berserkSpeedBonus;
    private double berserkDamageBonus;
    private boolean isBerserk = false;
    private double expMultiplier;
    private final Set<UUID> contributors = new HashSet<>();
    private final List<Zombie> minions = new ArrayList<>();
    
    private Location lastLocation;
    private int stuckTicks = 0;

    public BossManager(RandomClassPlugin plugin) {
        this.plugin = plugin;
        this.bossKey = new NamespacedKey(plugin, "is_giant_zombie_boss");
        loadConfig();

        // 보스 관련 설정 불러오기
        if (bossConfig.getBoolean("bosses.giant_zombie.enabled", true)) {
            loadBossSettings();

            // 약간의 지연 후 타이머 시작 (월드 로딩 대기)
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                clearExistingBosses();
                isOnCooldown = false;
                startBossTask();
            }, 20L);
        }
    }

    private void clearExistingBosses() {
        String bossName = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&',
                bossConfig.getString("bosses.giant_zombie.name", "&c거대좀비")));

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                // 좀비나 자이언트 타입인 경우 체크
                if (entity instanceof Zombie || entity instanceof Giant) {
                    boolean isBoss = false;

                    // 1. 퍼시스턴트 데이터 확인 (가장 정확함, 재시작 후에도 유지)
                    if (entity.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE)) {
                        isBoss = true;
                    }
                    // 2. 메타데이터 확인 (메모리 내 태그)
                    else if (entity.hasMetadata("giant_zombie")) {
                        isBoss = true;
                    }
                    // 3. 이름으로 확인 (기존에 생성된 보스들 처리)
                    else if (entity.getCustomName() != null
                            && ChatColor.stripColor(entity.getCustomName()).equals(bossName)) {
                        isBoss = true;
                    }

                    if (isBoss) {
                        entity.remove();
                    }
                }
            }
        }

        if (bossBar != null) {
            bossBar.removeAll();
        }
        currentBoss = null;
        contributors.clear();
        clearMinions();
    }

    private Attribute getAttributeField(String... names) {
        for (String name : names) {
            try {
                java.lang.reflect.Field field = Attribute.class.getField(name);
                return (Attribute) field.get(null);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public void loadConfig() {
        bossFile = new File(plugin.getDataFolder(), "boss.yml");
        if (!bossFile.exists()) {
            plugin.saveResource("boss.yml", false);
        }
        bossConfig = YamlConfiguration.loadConfiguration(bossFile);
    }

    private void loadBossSettings() {
        String worldName = bossConfig.getString("bosses.giant_zombie.spawn_location.world", "world");
        double x = bossConfig.getDouble("bosses.giant_zombie.spawn_location.x", 0);
        double y = bossConfig.getDouble("bosses.giant_zombie.spawn_location.y", 70);
        double z = bossConfig.getDouble("bosses.giant_zombie.spawn_location.z", 0);

        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            spawnLocation = new Location(world, x, y, z);
        }

        maxHealth = bossConfig.getDouble("bosses.giant_zombie.health", 1000.0);
        damage = bossConfig.getDouble("bosses.giant_zombie.damage", 15.0);
        attackCooldown = bossConfig.getDouble("bosses.giant_zombie.attack_cooldown", 3.0);
        pullCooldown = bossConfig.getInt("bosses.giant_zombie.pull_skill_cooldown", 15);
        pullRadius = bossConfig.getDouble("bosses.giant_zombie.pull_skill_radius", 20.0);
        resetRadius = bossConfig.getDouble("bosses.giant_zombie.reset_radius", 100.0);
        respawnTimeMinutes = bossConfig.getInt("bosses.giant_zombie.respawn_time_minutes", 10);

        String name = ChatColor.translateAlternateColorCodes('&',
                bossConfig.getString("bosses.giant_zombie.name", "&c거대좀비"));
        bossBar = Bukkit.createBossBar(name, BarColor.RED, BarStyle.SOLID);

        lifestealRatio = bossConfig.getDouble("bosses.giant_zombie.lifesteal_ratio", 0.25);
        berserkTriggerRatio = bossConfig.getDouble("bosses.giant_zombie.berserk.trigger_health_ratio", 0.3);
        berserkLifestealRatio = bossConfig.getDouble("bosses.giant_zombie.berserk.lifesteal_ratio", 0.5);
        berserkNamePrefix = ChatColor.translateAlternateColorCodes('&',
                bossConfig.getString("bosses.giant_zombie.berserk.name_prefix", "&4[광폭화] "));
        berserkAttackCooldown = bossConfig.getDouble("bosses.giant_zombie.berserk.attack_cooldown", 1.5);
        berserkSpeedBonus = bossConfig.getDouble("bosses.giant_zombie.berserk.speed_bonus", 0.05);
        berserkDamageBonus = bossConfig.getDouble("bosses.giant_zombie.berserk.damage_bonus", 5.0);
        expMultiplier = bossConfig.getDouble("bosses.giant_zombie.exp_multiplier", 30.0);
    }

    public void spawnBoss() {
        if (spawnLocation == null || spawnLocation.getWorld() == null)
            return;

        // 기존 보스가 있다면 제거
        if (currentBoss != null && !currentBoss.isDead()) {
            currentBoss.remove();
        }

        isBerserk = false;
        if (bossBar != null) {
            String baseName = ChatColor.translateAlternateColorCodes('&',
                    bossConfig.getString("bosses.giant_zombie.name", "&c거대좀비"));
            bossBar.setTitle(baseName);
            bossBar.setColor(BarColor.RED);
        }
        contributors.clear();
        clearMinions();

        Location safeSpawnLoc = findSafeLocation(spawnLocation);

        currentBoss = safeSpawnLoc.getWorld().spawn(safeSpawnLoc, Zombie.class, zombie -> {
            zombie.setCustomName(ChatColor.translateAlternateColorCodes('&',
                    bossConfig.getString("bosses.giant_zombie.name", "&c거대좀비")));
            zombie.setCustomNameVisible(true);
            zombie.setRemoveWhenFarAway(false);
            zombie.setMetadata("giant_zombie", new FixedMetadataValue(plugin, true));
            zombie.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
            zombie.setAdult(); // 아기 좀비 방지
            zombie.setConversionTime(-1); // 드라운드 변환 방지
            zombie.setAI(true);
            zombie.setAware(true);

            // 스탯 설정 (버전 호환성 및 최신 API 경고 해결을 위해 필드 리플렉션 사용)
            try {
                // 크기 조절 (1.20.5+)
                Attribute scaleAttrType = getAttributeField("SCALE", "GENERIC_SCALE");
                if (scaleAttrType != null) {
                    AttributeInstance scaleAttr = zombie.getAttribute(scaleAttrType);
                    if (scaleAttr != null)
                        scaleAttr.setBaseValue(5.0); // 5배 크기
                }

                // 체력 설정
                Attribute healthAttrType = getAttributeField("MAX_HEALTH", "GENERIC_MAX_HEALTH");
                if (healthAttrType != null) {
                    AttributeInstance healthAttr = zombie.getAttribute(healthAttrType);
                    if (healthAttr != null)
                        healthAttr.setBaseValue(maxHealth);
                }
                zombie.setHealth(maxHealth);

                // 공격력 설정
                Attribute damageAttrType = getAttributeField("ATTACK_DAMAGE", "GENERIC_ATTACK_DAMAGE");
                if (damageAttrType != null) {
                    AttributeInstance damageAttr = zombie.getAttribute(damageAttrType);
                    if (damageAttr != null)
                        damageAttr.setBaseValue(damage);
                }

                // 이동 속도 설정 (크기가 커서 느려보이는 현상 방지)
                Attribute speedAttrType = getAttributeField("MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED");
                if (speedAttrType != null) {
                    AttributeInstance speedAttr = zombie.getAttribute(speedAttrType);
                    if (speedAttr != null)
                        speedAttr.setBaseValue(0.27); // 바닐라 좀비 0.23 보다 약간 빠름 (기존 0.35)
                }

                // 넉백 저항 설정 (1.0 = 넉백되지 않음)
                Attribute knockbackAttrType = getAttributeField("KNOCKBACK_RESISTANCE", "GENERIC_KNOCKBACK_RESISTANCE");
                if (knockbackAttrType != null) {
                    AttributeInstance knockbackAttr = zombie.getAttribute(knockbackAttrType);
                    if (knockbackAttr != null)
                        knockbackAttr.setBaseValue(1.0);
                }
            } catch (Exception e) {
                // 오류 발생 시 기본 설정 시도
                double fallbackMax = 20.0;
                Attribute healthAttrType = getAttributeField("MAX_HEALTH", "GENERIC_MAX_HEALTH");
                if (healthAttrType != null) {
                    AttributeInstance ai = zombie.getAttribute(healthAttrType);
                    if (ai != null)
                        fallbackMax = ai.getValue();
                }
                zombie.setHealth(Math.min(fallbackMax, maxHealth));
            }

            // 인식 범위(Follow Range) 설정 - 100블록
            Attribute followAttrType = getAttributeField("FOLLOW_RANGE", "GENERIC_FOLLOW_RANGE");
            if (followAttrType != null) {
                AttributeInstance followInstance = zombie.getAttribute(followAttrType);
                if (followInstance != null) {
                    followInstance.setBaseValue(100.0);
                }
            }

            // 장비 설정 (철 바지, 금 모자)
            if (zombie.getEquipment() != null) {
                zombie.getEquipment()
                        .setLeggings(new org.bukkit.inventory.ItemStack(org.bukkit.Material.IRON_LEGGINGS));
                zombie.getEquipment().setHelmet(new org.bukkit.inventory.ItemStack(org.bukkit.Material.GOLDEN_HELMET));
                zombie.getEquipment().setLeggingsDropChance(0.0f);
                zombie.getEquipment().setHelmetDropChance(0.0f);
            }

            // 불타지 않게 (낮 시간)
            zombie.addPotionEffect(
                    new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, false, false));
        });

        if (currentBoss == null) {
            plugin.getLogger().warning("[RandomClass] 보스 스폰이 다른 플러그인에 의해 취소되었거나 실패했습니다.");
            return;
        }

        // 닭 타는 문제 방지 (조키)
        if (currentBoss.getVehicle() != null) {
            currentBoss.getVehicle().remove();
        }

        updateBossBar();
        pullTaskTick = 0;

        if (!isSpawnNotified) {
            isSpawnNotified = true;
            String spawnMsg = plugin.getClassManager().getMessage("boss.spawn_notified",
                    "&e거대좀비가 나타났습니다! &7(좌표: {x}, {z})");
            spawnMsg = spawnMsg.replace("{x}", String.valueOf(spawnLocation.getBlockX()))
                    .replace("{z}", String.valueOf(spawnLocation.getBlockZ()));
            Bukkit.broadcastMessage(spawnMsg);
        }
    }

    private void startBossTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (currentBoss == null || currentBoss.isDead() || !currentBoss.isValid()) {
                if (bossBar != null) {
                    bossBar.removeAll();
                }

                // 만약 청크 언로드 등으로 invalid 상태가 된 보스가 남아있다면 삭제 (중복 방지)
                if (currentBoss != null && !currentBoss.isDead()) {
                    currentBoss.remove();
                }
                currentBoss = null;
                isBerserk = false;

                if (!isOnCooldown && spawnLocation != null && spawnLocation.getWorld() != null) {
                    boolean playerNearby = false;
                    for (Player p : spawnLocation.getWorld().getPlayers()) {
                        if (p.getLocation().distanceSquared(spawnLocation) <= resetRadius * resetRadius) {
                            playerNearby = true;
                            break;
                        }
                    }

                    if (playerNearby) {
                        clearExistingBosses();
                        spawnBoss();
                    }
                }
                return; // 보스가 없으면 처리 안함
            }

            // 보스 바 업데이트 및 플레이어 추가
            updateBossBar();

            // 1. 강제 타겟팅 로직 (매 10틱마다 타겟 갱신 - 인식을 못하는 현상 방지)
            if (currentBoss.getTicksLived() % 10 == 0) {
                org.bukkit.entity.LivingEntity target = currentBoss.getTarget();
                boolean invalidTarget = target == null || target.isDead()
                        || target.getWorld() != currentBoss.getWorld();

                if (!invalidTarget && target instanceof Player) {
                    Player pTarget = (Player) target;
                    if (pTarget.getGameMode() == org.bukkit.GameMode.CREATIVE
                            || pTarget.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                        invalidTarget = true;
                    }
                }

                if (invalidTarget) {
                    Player nearest = null;
                    double minDist = Double.MAX_VALUE;
                    for (Player p : currentBoss.getWorld().getPlayers()) {
                        if (p.isDead() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR
                                || p.getGameMode() == org.bukkit.GameMode.CREATIVE)
                            continue;
                        double dist = p.getLocation().distanceSquared(currentBoss.getLocation());
                        if (dist <= resetRadius * resetRadius && dist < minDist) {
                            minDist = dist;
                            nearest = p;
                        }
                    }
                    if (nearest != null) {
                        currentBoss.setTarget(nearest);
                    }
                }
            }

            // [물 탈출 로직] 물속에 있으면 위로 튀어오르고 타겟 방향으로 추진력 부여
            if (currentBoss.getLocation().getBlock().isLiquid() || currentBoss.getEyeLocation().getBlock().isLiquid()) {
                Vector velocity = currentBoss.getVelocity();
                velocity.setY(0.4); // 위로 튀어오름

                // 타겟이 있다면 타겟 방향으로 추가 추진력
                if (currentBoss.getTarget() != null) {
                    Vector dir = currentBoss.getTarget().getLocation().toVector()
                            .subtract(currentBoss.getLocation().toVector()).normalize();
                    velocity.add(dir.multiply(0.2));
                } else {
                    // 타겟이 없으면 랜덤하게 옆으로 이동하여 탈출 시도
                    velocity.add(new Vector(Math.random() - 0.5, 0, Math.random() - 0.5).multiply(0.3));
                }
                currentBoss.setVelocity(velocity);
            }

            // [끼임 방지 로직] 2초(40틱)마다 위치를 체크하여 끼어있으면 도약/장애물 파괴
            if (currentBoss.getTicksLived() % 40 == 0) {
                if (lastLocation != null && currentBoss.getTarget() != null) {
                    double dist = currentBoss.getLocation().distanceSquared(lastLocation);
                    if (dist < 0.25) { // 2초간 0.5블록 미만 이동 시 끼임으로 간주
                        stuckTicks++;
                    } else {
                        stuckTicks = 0;
                    }

                    if (stuckTicks >= 1) { // 끼임 감지 시
                        // 1. 위로 도약 및 타겟 방향 추진력
                        Vector stuckVel = currentBoss.getVelocity();
                        stuckVel.setY(0.5);
                        Vector dir = currentBoss.getTarget().getLocation().toVector()
                                .subtract(currentBoss.getLocation().toVector()).normalize();
                        stuckVel.add(dir.multiply(0.3));
                        currentBoss.setVelocity(stuckVel);

                        // 2. 주변 장애물 제거 (나뭇잎, 울타리 등)
                        breakObstructingBlocks(currentBoss.getLocation());
                        stuckTicks = 0;
                    }
                }
                lastLocation = currentBoss.getLocation().clone();
            }

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player == null)
                    continue;
                if (player.getWorld().equals(currentBoss.getWorld())
                        && player.getLocation().distanceSquared(currentBoss.getLocation()) <= 10000) { // 100블록
                    bossBar.addPlayer(player);
                } else {
                    bossBar.removePlayer(player);
                }
            }

            // 어그로 리셋 (100블록 밖) - 매 1초마다 체크
            resetTaskTick++;
            if (resetTaskTick >= 20) {
                resetTaskTick = 0;
                boolean playerNearby = false;
                for (Player player : currentBoss.getWorld().getPlayers()) {
                    if (player == null)
                        continue;
                    if (player.getLocation().distanceSquared(currentBoss.getLocation()) <= resetRadius * resetRadius) {
                        playerNearby = true;
                        break;
                    }
                }

                if (!playerNearby) {
                    // 주변에 플레이어가 없으면 디스폰 처리 (나중에 다시 오면 스폰되도록)
                    currentBoss.remove();
                    currentBoss = null;
                    isBerserk = false;
                    bossBar.removeAll();
                    return;
                }
            }

            // 당기기 스킬
            pullTaskTick++;
            if (pullTaskTick >= pullCooldown * 20) {
                pullTaskTick = 0;
                boolean pulled = false;
                for (Player player : currentBoss.getWorld().getPlayers()) {
                    if (player == null)
                        continue;
                    if (player.getLocation().distanceSquared(currentBoss.getLocation()) <= pullRadius * pullRadius) {
                        Vector dir = currentBoss.getLocation().toVector().subtract(player.getLocation().toVector());
                        double distance = dir.length();
                        if (distance > 0) {
                            dir.normalize();
                            // 거리에 비례하여 당기되, 최대 10블록 이동 수준으로 제한 (속도계수 1.5 최대)
                            double pullStr = Math.min(distance, 5.0) * 0.15;
                            player.setVelocity(dir.multiply(pullStr).setY(0.5));
                            pulled = true;
                        }
                    }
                }
                if (pulled) {
                    currentBoss.getWorld().playSound(currentBoss.getLocation(),
                            org.bukkit.Sound.ENTITY_ENDER_DRAGON_FLAP, 2.0f, 0.5f);
                    currentBoss.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, currentBoss.getLocation(), 50, 2, 2,
                            2, 0.1);
                }
            }

        }, 0L, 1L);
    }

    public void updateBossBar() {
        if (currentBoss != null && bossBar != null) {
            double currentHealth = currentBoss.getHealth();
            double progress = currentHealth / maxHealth;
            bossBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));

            // 광폭화 체크
            if (!isBerserk && progress <= berserkTriggerRatio) {
                triggerBerserk();
            }
        }
    }

    private void triggerBerserk() {
        isBerserk = true;
        if (currentBoss == null || currentBoss.isDead())
            return;

        // 이름 변경
        String baseName = ChatColor.translateAlternateColorCodes('&',
                bossConfig.getString("bosses.giant_zombie.name", "&c거대좀비"));
        currentBoss.setCustomName(berserkNamePrefix + baseName);
        bossBar.setTitle(berserkNamePrefix + baseName);
        bossBar.setColor(BarColor.PURPLE);

        // 스탯 강화
        try {
            // 공격력 강화
            Attribute damageAttrType = getAttributeField("ATTACK_DAMAGE", "GENERIC_ATTACK_DAMAGE");
            if (damageAttrType != null) {
                AttributeInstance damageAttr = currentBoss.getAttribute(damageAttrType);
                if (damageAttr != null) {
                    damageAttr.setBaseValue(damage + berserkDamageBonus);
                }
            }

            // 이동속도 강화
            Attribute speedAttrType = getAttributeField("MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED");
            if (speedAttrType != null) {
                AttributeInstance speedAttr = currentBoss.getAttribute(speedAttrType);
                if (speedAttr != null) {
                    speedAttr.setBaseValue(0.25 + berserkSpeedBonus); // 기본 0.25 + 보너스
                }
            }
        } catch (Exception ignored) {
        }

        // 시각 효과
        currentBoss.getWorld().playSound(currentBoss.getLocation(), org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 1.5f,
                0.5f);
        currentBoss.getWorld().spawnParticle(org.bukkit.Particle.FLAME, currentBoss.getLocation(), 100, 1, 2, 1, 0.1);

        String berserkMsg = plugin.getClassManager().getMessage("boss.berserk", "&c거대좀비가 광폭화 상태에 진입했습니다!");
        Bukkit.broadcastMessage(berserkMsg);

        // [추가] 주변에 좀비 하수인 10마리 소환
        Location center = currentBoss.getLocation();
        for (int i = 0; i < 10; i++) {
            double angle = i * (Math.PI * 2 / 10);
            double radius = 8.0; // 4블록 반경
            double rx = Math.cos(angle) * radius;
            double rz = Math.sin(angle) * radius;
            Location spawnLoc = center.clone().add(rx, 1, rz);

            center.getWorld().spawn(spawnLoc, Zombie.class, minion -> {
                minion.setCustomName(ChatColor.RED + "보스의 하수인");
                minion.setCustomNameVisible(true);
                minion.setMetadata("boss_minion", new FixedMetadataValue(plugin, true));
                minion.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, false, false));
                if (currentBoss.getTarget() != null) {
                    minion.setTarget(currentBoss.getTarget());
                }
                minions.add(minion);
            });
        }
    }

    public void handleBossDeath(Player killer) {
        if (bossBar != null) {
            bossBar.removeAll();
        }

        String killerName = (killer != null) ? killer.getName() : "누군가";
        String prefix = plugin.getClassManager().getMessage("boss.prefix", "&c[보스] ");

        String deathMsg = plugin.getClassManager().getMessage("boss.death", "{prefix}&6{killer}&f님이 &c거대좀비&f를 처치했습니다!");
        deathMsg = deathMsg.replace("{prefix}", prefix).replace("{killer}", killerName);

        String respawnMsg = plugin.getClassManager().getMessage("boss.respawn_timer", "&7보스는 &e{time}분&7 뒤에 다시 나타납니다.");
        respawnMsg = respawnMsg.replace("{time}", String.valueOf(respawnTimeMinutes));

        Bukkit.broadcastMessage("");
        Bukkit.broadcastMessage(deathMsg);
        Bukkit.broadcastMessage(respawnMsg);
        Bukkit.broadcastMessage("");

        // 처치 시 웅장한 효과음 및 시각 효과 추가
        if (currentBoss != null) {
            Location deathLoc = currentBoss.getLocation();
            // 처치 위치 주변 플레이어에게 웅장한 효과음 재생
            deathLoc.getWorld().playSound(deathLoc, org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f, 1.0f);
            deathLoc.getWorld().playSound(deathLoc, org.bukkit.Sound.ENTITY_WITHER_DEATH, 0.5f, 0.8f);
            // 처치 위치에 화려한 파티클 효과
            deathLoc.getWorld().spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING, deathLoc, 200, 2, 2, 2, 0.5);
            deathLoc.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION_EMITTER, deathLoc, 5, 1, 1, 1, 0.1);

            // 기여자 경험치 지급 (일반 좀비 5 EXP 기준)
            int expAmount = (int) (5 * expMultiplier);
            for (UUID uuid : contributors) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    p.giveExp(expAmount);
                }
            }
            contributors.clear();
            clearMinions();
        }

        isBerserk = false;
        isOnCooldown = true;
        isSpawnNotified = false;

        // 리스폰 스케줄러 - 쿨다운만 해제
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            isOnCooldown = false;
        }, respawnTimeMinutes * 60L * 20L);
    }

    public double getAttackCooldown() {
        return isBerserk ? berserkAttackCooldown : attackCooldown;
    }

    public double getLifestealRatio() {
        return isBerserk ? berserkLifestealRatio : lifestealRatio;
    }

    public Zombie getCurrentBoss() {
        return currentBoss;
    }

    public void cleanup() {
        if (bossBar != null) {
            bossBar.removeAll();
        }
        if (currentBoss != null && !currentBoss.isDead()) {
            currentBoss.remove();
        }
        isBerserk = false;
        contributors.clear();
        clearMinions();
    }

    public void clearMinions() {
        for (Zombie minion : minions) {
            if (minion != null && !minion.isDead()) {
                minion.remove();
            }
        }
        minions.clear();
    }

    public void addContributor(UUID uuid) {
        contributors.add(uuid);
    }

    private void breakObstructingBlocks(Location loc) {
        int radius = 3;
        for (int x = -radius; x <= radius; x++) {
            for (int y = 0; y <= 5; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block b = loc.clone().add(x, y, z).getBlock();
                    if (isBreakableByBoss(b.getType())) {
                        b.setType(Material.AIR);
                    }
                }
            }
        }
    }

    private boolean isBreakableByBoss(Material type) {
        String name = type.name();
        return name.contains("LEAVES") || name.contains("FENCE") || name.contains("GLASS") 
            || name.contains("GRASS") || name.contains("FLOWER") || name.contains("BUSH")
            || name.contains("LOG") || name.contains("WOOD") || name.contains("DOOR")
            || name.contains("GATE");
    }

    private Location findSafeLocation(Location loc) {
        if (loc == null || loc.getWorld() == null)
            return loc;

        World world = loc.getWorld();
        // 주변 20블록 범위 내에서 안전한 육지 검색 (나선형 검색)
        for (int d = 0; d <= 20; d++) {
            for (int x = -d; x <= d; x++) {
                for (int z = -d; z <= d; z++) {
                    if (d > 0 && Math.abs(x) != d && Math.abs(z) != d)
                        continue;

                    int checkX = loc.getBlockX() + x;
                    int checkZ = loc.getBlockZ() + z;
                    int highestY = world.getHighestBlockYAt(checkX, checkZ);

                    Location checkLoc = new Location(world, checkX + 0.5, highestY, checkZ + 0.5);
                    org.bukkit.block.Block ground = checkLoc.getBlock();

                    // 1. 바닥이 물이 아니고 단단한 블록인지 확인
                    if (!isWater(ground.getType()) && ground.getType().isSolid()) {
                        // 2. 주변 3x3 공간과 위로 10블록이 비어있는지 확인 (거대 좀비의 넓은 히트박스 고려)
                        boolean clear = true;
                        for (int h = 1; h <= 10; h++) {
                            for (int ox = -1; ox <= 1; ox++) {
                                for (int oz = -1; oz <= 1; oz++) {
                                    if (world.getBlockAt(checkX + ox, highestY + h, checkZ + oz).getType().isSolid()) {
                                        clear = false;
                                        break;
                                    }
                                }
                                if (!clear)
                                    break;
                            }
                            if (!clear)
                                break;
                        }

                        if (clear) {
                            return checkLoc.add(0, 1.5, 0); // 바닥에 끼이지 않게 약간 위에서 스폰
                        }
                    }
                }
            }
        }

        // 못 찾으면 원래 위치의 가장 높은 곳 반환
        return new Location(world, loc.getX(), world.getHighestBlockYAt(loc) + 1, loc.getZ());
    }

    private boolean isWater(org.bukkit.Material material) {
        return material == org.bukkit.Material.WATER || material.name().equals("STATIONARY_WATER");
    }
}
