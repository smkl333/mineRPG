package com.example.randomclass.listeners;

import com.example.randomclass.ClassManager;
import com.example.randomclass.PlayerClass;
import com.example.randomclass.RandomClassPlugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Color;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Animals;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.util.Vector;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

public class ClassAbilityListener implements Listener {

    private final ClassManager classManager;
    private final RandomClassPlugin plugin;
    private final Map<UUID, Long> adventurerRegenFieldCooldowns = new HashMap<>();
    private final Map<UUID, Long> warriorShieldReadyUntil = new HashMap<>();
    private final Map<UUID, Long> warriorShieldChargeUntil = new HashMap<>();
    private final Map<UUID, Long> warriorDoubleJumpCooldowns = new HashMap<>();
    private final Map<UUID, Long> warriorArrowStormCooldowns = new HashMap<>();
    private final Map<UUID, Long> warriorShieldCooldowns = new HashMap<>();
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> speedProcTasks = new HashMap<>();
    private final Map<UUID, Long> adventurerValorCooldowns = new HashMap<>();
    private final Map<UUID, Long> stunnedUntil = new HashMap<>();

    public boolean isStunned(UUID uuid) {
        Long until = stunnedUntil.get(uuid);
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            stunnedUntil.remove(uuid);
            return false;
        }
        return true;
    }

    public ClassAbilityListener(RandomClassPlugin plugin, ClassManager classManager) {
        this.plugin = plugin;
        this.classManager = classManager;
    }

    public void cleanup(UUID uuid) {
        adventurerRegenFieldCooldowns.remove(uuid);
        warriorShieldReadyUntil.remove(uuid);
        warriorShieldChargeUntil.remove(uuid);
        warriorDoubleJumpCooldowns.remove(uuid);
        warriorArrowStormCooldowns.remove(uuid);
        warriorShieldCooldowns.remove(uuid);
        stunnedUntil.remove(uuid);

        if (speedProcTasks.containsKey(uuid)) {
            speedProcTasks.get(uuid).cancel();
            speedProcTasks.remove(uuid);
        }
        adventurerValorCooldowns.remove(uuid);
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        // 기절한 플레이어/엔티티는 데미지를 줄 수 없음 (공격 불가)
        if (event.getDamager() instanceof LivingEntity) {
            LivingEntity damager = (LivingEntity) event.getDamager();
            if (isStunned(damager.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
        } else if (event.getDamager() instanceof org.bukkit.entity.Projectile) {
            org.bukkit.projectiles.ProjectileSource source = ((org.bukkit.entity.Projectile) event.getDamager()).getShooter();
            if (source instanceof LivingEntity) {
                if (isStunned(((LivingEntity) source).getUniqueId())) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        // [전사] 타격 시 신속 부여 (동물/몬스터 대상, 레벨 4 이상)
        if (event.getDamager() instanceof Player) {
            Player attacker = (Player) event.getDamager();
            if (classManager.getPlayerClass(attacker) == PlayerClass.WARRIOR
                    && classManager.getPlayerClassLevel(attacker) >= 4) {
                if (event.getEntity() instanceof org.bukkit.entity.Animals
                        || event.getEntity() instanceof org.bukkit.entity.Monster
                        || event.getEntity() instanceof Player) {
                    attacker.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 0, false, false)); // 신속 1
                }
            }
        }

        // [추가] 애로우 스톰 화살 데미지 처리
        if (event.getDamager() instanceof Arrow) {
            Arrow arrow = (Arrow) event.getDamager();
            if (arrow.hasMetadata("arrow_storm_arrow")) {
                // 보스 포함 모든 대상에게 설정된 데미지 적용
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
                            if (tnt.hasMetadata("mage_blackhole")) {
                                multiplier *= classManager.getMageBlackHoleDamageMultiplier();
                            }
                        }
                    }
                    event.setDamage(event.getDamage() * multiplier);
                }
            }
        }

        // [전사 각성] 금강불괴 데미지 반사
        if (event.getEntity() instanceof Player && !event.getEntity().hasMetadata("is_reflect_damage")) {
            Player victim = (Player) event.getEntity();
            if (victim.hasMetadata("warrior_geumgang_active")) {
                org.bukkit.entity.Entity damager = event.getDamager();
                LivingEntity actualDamager = null;

                if (damager instanceof LivingEntity) {
                    actualDamager = (LivingEntity) damager;
                } else if (damager instanceof org.bukkit.entity.Projectile) {
                    org.bukkit.projectiles.ProjectileSource source = ((org.bukkit.entity.Projectile) damager)
                            .getShooter();
                    if (source instanceof LivingEntity) {
                        actualDamager = (LivingEntity) source;
                    }
                } else if (damager instanceof org.bukkit.entity.TNTPrimed) {
                    org.bukkit.entity.Entity source = ((org.bukkit.entity.TNTPrimed) damager).getSource();
                    if (source instanceof LivingEntity) {
                        actualDamager = (LivingEntity) source;
                    }
                }

                if (actualDamager != null) {
                    double reflectPercent = classManager.getWarriorGeumgangReflect();
                    double reflectDamage = event.getDamage() * reflectPercent;
                    if (reflectDamage > 0) {
                        actualDamager.setMetadata("is_reflect_damage", new FixedMetadataValue(plugin, true));
                        actualDamager.damage(reflectDamage, victim);
                        actualDamager.removeMetadata("is_reflect_damage", plugin);
                    }
                }
            }
        }

        // [전사 각성] 피흡 점프 (공격 시 발동)
        if (event.getDamager() instanceof Player) {
            Player attacker = (Player) event.getDamager();
            if (attacker.hasMetadata("warrior_lifesteal_active")) {
                double lifestealPct = classManager.getWarriorLifestealPercent();
                double healAmount = event.getFinalDamage() * lifestealPct;
                if (healAmount > 0) {
                    double maxHealth = attacker.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                    attacker.setHealth(Math.min(attacker.getHealth() + healAmount, maxHealth));
                    attacker.getWorld().spawnParticle(Particle.HEART, attacker.getLocation().add(0, 1, 0), 3, 0.2, 0.2,
                            0.2, 0.05);
                }
            }
        }

        // [모험가 각성] 용맹 버프 적용 (공격력 20% 증가, 흡혈 5% 합연산)
        if (event.getDamager() instanceof Player) {
            Player attacker = (Player) event.getDamager();
            if (classManager.hasValorBuff(attacker)) {
                // 공격력 20% 증가
                double multiplier = classManager.getAdventurerValorDamageMultiplier();
                event.setDamage(event.getDamage() * multiplier);

                // 흡혈 5% 합연산
                double lifestealPct = classManager.getAdventurerValorLifestealPercent();
                double healAmount = event.getFinalDamage() * lifestealPct;
                if (healAmount > 0) {
                    double maxHealth = attacker.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                    attacker.setHealth(Math.min(attacker.getHealth() + healAmount, maxHealth));
                    attacker.getWorld().spawnParticle(Particle.HEART, attacker.getLocation().add(0, 1.2, 0), 2, 0.3,
                            0.3, 0.3, 0.05);
                }
            }
        }

        // [모험가 10레벨 능력] 몬스터에게 죽을 위기 시 텔레포트
        if (event.getEntity() instanceof Player) {
            Player victim = (Player) event.getEntity();
            PlayerClass victimClass = classManager.getPlayerClass(victim);
            int victimLevel = classManager.getPlayerClassLevel(victim);

            boolean isDamagedByMonster = false;
            org.bukkit.entity.Entity damager = event.getDamager();
            if (damager instanceof Monster) {
                isDamagedByMonster = true;
            } else if (damager instanceof org.bukkit.entity.Projectile) {
                if (((org.bukkit.entity.Projectile) damager).getShooter() instanceof Monster) {
                    isDamagedByMonster = true;
                }
            } else if (damager instanceof org.bukkit.entity.AreaEffectCloud) {
                if (((org.bukkit.entity.AreaEffectCloud) damager).getSource() instanceof Monster) {
                    isDamagedByMonster = true;
                }
            }

            // [추가] 모험가 Lv 8+ 피격 시 신속 효과 부여 (config 설정 기반)
            boolean isDamagedByEnemy = isDamagedByMonster || (damager instanceof Player && damager != victim);
            if (victimClass == PlayerClass.ADVENTURER && victimLevel >= 8 && isDamagedByEnemy) {
                int duration = classManager.getAdventurerSpeedOnHitDurationTicks();
                int amplifier = classManager.getAdventurerSpeedOnHitAmplifier();
                victim.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, duration, amplifier, false, false));
            }

            if (victimClass == PlayerClass.ADVENTURER && victimLevel >= 10 && isDamagedByMonster) {
                if (victim.getHealth() + victim.getAbsorptionAmount() <= event.getFinalDamage()) {

                    // 쿨타임 체크 (데이터 파일에서 영구 보관된 값 사용)
                    long now = System.currentTimeMillis();
                    long lastUsed = classManager.getSkillCooldown(victim, "adventurer_escape");
                    double cooldownSec = plugin.getConfig().getDouble("cooldowns.adventurer_teleport", 300.0);
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

                    // 쿨타임 시작 및 저장
                    classManager.setSkillCooldown(victim, "adventurer_escape", now);
                    classManager.startCooldownDisplay(victim, Material.DIAMOND_AXE, cooldownSec, "위기 탈출", "[모험가] ");
                }
            }

            // 포화 쿨타임은 onEntityDamageResistance(EntityDamageEvent)에서 통합 처리됨
        }

        // 공격자 기반 로직
        if (!(event.getDamager() instanceof Player))
            return;

        Player player = (Player) event.getDamager();
        PlayerClass pClass = classManager.getPlayerClass(player);
        int level = classManager.getPlayerClassLevel(player);
        long now = System.currentTimeMillis();

        // 전사 방패 돌진 중이라면 패시브 슬로우 발동을 방지하기 위해 판정 변수 설정
        boolean isShieldDashHit = pClass == PlayerClass.WARRIOR &&
                now <= warriorShieldChargeUntil.getOrDefault(player.getUniqueId(), 0L);

        // [전사 전용 능력] 보스를 제외한 대상(플레이어, 동물, 몬스터)에게 슬로우 부여 (Lv.5+)
        if (pClass == PlayerClass.WARRIOR && level >= 5 && event.getEntity() instanceof LivingEntity) {
            LivingEntity victimEntity = (LivingEntity) event.getEntity();

            // 보스 몬스터 제외 (바닐라 보스 + 거대좀비 커스텀 보스)
            boolean isBoss = victimEntity instanceof org.bukkit.entity.Boss || victimEntity.hasMetadata("giant_zombie");
            if (!isBoss) {
                double slowPct = classManager.getWarriorSlowPercentage(level);
                int duration = classManager.getWarriorSlowDuration();

                if (isShieldDashHit) {
                    // 기절(10틱)이 적용된 상태라면, 기절이 끝난 직후에 남은 시간만큼 둔화 부여
                    if (duration > 10) {
                        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (!victimEntity.isDead() && victimEntity.isValid()) {
                                classManager.applyCustomSlow(victimEntity, slowPct, duration - 10);
                            }
                        }, 10L);
                    }
                } else {
                    classManager.applyCustomSlow(victimEntity, slowPct, duration);
                }
            }
        }
    }

    @EventHandler
    public void onEntityDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        org.bukkit.entity.LivingEntity victim = event.getEntity();
        Player killer = victim.getKiller();

        // [추가] 마법사 스킬(화염구, TNT)에 의한 킬도 플레이어 킬로 판정
        if (killer == null
                && victim.getLastDamageCause() instanceof org.bukkit.event.entity.EntityDamageByEntityEvent) {
            org.bukkit.event.entity.EntityDamageByEntityEvent damageEvent = (org.bukkit.event.entity.EntityDamageByEntityEvent) victim
                    .getLastDamageCause();
            org.bukkit.entity.Entity damager = damageEvent.getDamager();

            if (damager instanceof org.bukkit.entity.Fireball) {
                if (((org.bukkit.entity.Fireball) damager).getShooter() instanceof Player) {
                    killer = (Player) ((org.bukkit.entity.Fireball) damager).getShooter();
                }
            } else if (damager instanceof org.bukkit.entity.TNTPrimed) {
                if (damager.hasMetadata("mage_owner_uuid")) {
                    String ownerUuidStr = damager.getMetadata("mage_owner_uuid").get(0).asString();
                    Player owner = org.bukkit.Bukkit.getPlayer(java.util.UUID.fromString(ownerUuidStr));
                    if (owner != null)
                        killer = owner;
                }
            }
        }

        if (killer == null)
            return;

        PlayerClass pClass = classManager.getPlayerClass(killer);
        double multiplier = 1.0;

        // 동물/몬스터 배율 설정
        if (victim instanceof org.bukkit.entity.Animals) {
            // 동물인 경우: 농부(ADVENTURER)는 100%, 전사/마법사는 설정된 배율 적용
            if (pClass != PlayerClass.ADVENTURER) {
                multiplier = classManager.getDropMultiplier();
            }
        } else if (!(victim instanceof org.bukkit.entity.Monster)) {
            // 몬스터나 동물이 아닌 경우 무시
            return;
        }
        // 약탈(Looting) 인챈트 보너스 적용 (Lv1: +10%, Lv2: +20%, Lv3: +30%)
        int lootingLevel = killer.getInventory().getItemInMainHand()
                .getEnchantmentLevel(org.bukkit.enchantments.Enchantment.LOOTING);

        // [추가] 모험가의 가짜 약탈 로어 파싱 및 동물 대상 바닐라 약탈 효과 적용
        if (pClass == PlayerClass.ADVENTURER) {
            if (victim instanceof org.bukkit.entity.Animals) {
                ItemStack hand = killer.getInventory().getItemInMainHand();
                if (hand != null && hand.hasItemMeta() && hand.getItemMeta().hasLore()) {
                    for (String line : hand.getItemMeta().getLore()) {
                        if (line.contains("약탈 III"))
                            lootingLevel = Math.max(lootingLevel, 3);
                        else if (line.contains("약탈 II"))
                            lootingLevel = Math.max(lootingLevel, 2);
                        else if (line.contains("약탈 I"))
                            lootingLevel = Math.max(lootingLevel, 1);
                    }
                }
            }

            // 모험가 가짜 약탈 레벨이 있으면, 동물 대상으로 바닐라 드롭을 수동으로 증가시킴
            if (lootingLevel > 0 && victim instanceof org.bukkit.entity.Animals) {
                java.util.Random random = new java.util.Random();
                java.util.List<ItemStack> extraDrops = new java.util.ArrayList<>();
                for (ItemStack drop : event.getDrops()) {
                    // 기본 드롭 아이템(가죽, 고기 등)에 대해 바닐라 약탈 공식과 유사하게 0 ~ lootingLevel 개의 추가 드롭 생성
                    int bonus = random.nextInt(lootingLevel + 1);
                    if (bonus > 0) {
                        ItemStack extra = drop.clone();
                        extra.setAmount(bonus);
                        extraDrops.add(extra);
                    }
                }
                event.getDrops().addAll(extraDrops);
            }
        }

        double lootingMultiplier = 1.0 + (lootingLevel * 0.1);
        multiplier *= lootingMultiplier;

        // [보스 보너스] 보스 몬스터(위더, 엔더 드래곤, 거대 좀비) 판정
        boolean isBoss = victim instanceof org.bukkit.entity.Boss || victim.hasMetadata("giant_zombie");
        if (isBoss) {
            multiplier *= 20.0; // 보스는 확률 20배 적용

            // 조각 3~5개 확정 드랍
            int fragmentCount = 3 + (int) (Math.random() * 3); // 3, 4, 5
            for (int i = 0; i < fragmentCount; i++) {
                event.getDrops().add(classManager.createTornTicket());
            }
            // 레벨업권 1개 드랍
            event.getDrops().add(classManager.createLevelUpTicket());
        }

        // 1. 인벤토리 세이브권 드랍 (config 확률 기반)
        if (Math.random() < classManager.getInventorySaveChance() * multiplier) {
            ItemStack ticket = classManager.createInventorySaveTicket();
            event.getDrops().add(ticket);
            if (!isBoss)
                announceTicketDrop(killer, ChatColor.stripColor(ticket.getItemMeta().getDisplayName()), true);
        }

        // 전사, 마법사 전용 드랍 (상승권 및 조각)
        if (pClass == PlayerClass.WARRIOR || pClass == PlayerClass.MAGE) {
            // 2. 직업 레벨 상승권 드랍 (config 확률 기반)
            if (Math.random() < classManager.getLevelUpTicketChance() * multiplier) {
                ItemStack ticket = classManager.createLevelUpTicket();
                event.getDrops().add(ticket);
                if (!isBoss)
                    announceTicketDrop(killer, ChatColor.stripColor(ticket.getItemMeta().getDisplayName()), true);
            }

            // 3. 찢어진 상승권 조각 드랍 (config 확률 기반)
            if (Math.random() < classManager.getTornTicketChance() * multiplier) {
                ItemStack fragment = classManager.createTornTicket();
                event.getDrops().add(fragment);
                if (!isBoss)
                    announceTicketDrop(killer, ChatColor.stripColor(fragment.getItemMeta().getDisplayName()), false);
            }
        }
        if (pClass == PlayerClass.ADVENTURER) {
            // 2. 직업 레벨 상승권 드랍 (config 확률 기반)
            if (Math.random() < classManager.getLevelUpTicketChance() * multiplier / 2.) {
                ItemStack ticket = classManager.createLevelUpTicket();
                event.getDrops().add(ticket);
                if (!isBoss)
                    announceTicketDrop(killer, ChatColor.stripColor(ticket.getItemMeta().getDisplayName()), true);
            }

            // 3. 찢어진 상승권 조각 드랍 (config 확률 기반)
            if (Math.random() < classManager.getTornTicketChance() * multiplier / 2.) {
                ItemStack fragment = classManager.createTornTicket();
                event.getDrops().add(fragment);
                if (!isBoss)
                    announceTicketDrop(killer, ChatColor.stripColor(fragment.getItemMeta().getDisplayName()), false);
            }
        }
    }

    @EventHandler
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        if (event.getEntity() != null && event.getEntity().hasMetadata("mage_tnt")) {
            event.blockList().clear(); // 블록 파괴 방지
        }
    }

    // [저항 스칼라] 바닐라 저항 포션 효과를 역산 보정하여 커스텀 비율로 대체
    // 저항 I (UI) = 실제 10% 감소, 저항 II (UI) = 실제 20% 감소
    @EventHandler(priority = org.bukkit.event.EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDamageResistance(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player))
            return;

        Player victim = (Player) event.getEntity();
        PlayerClass pClass = classManager.getPlayerClass(victim);
        int level = classManager.getPlayerClassLevel(victim);

        // [공통] 어떤 데미지든 모험가 10레벨이면 포화 쿨타임 발동 (낙사, 용암, 몬스터 모두)
        if (pClass == PlayerClass.ADVENTURER && level >= 10) {
            classManager.triggerAdventurerCombatCooldown(victim);
        }

        // 모험가 낙하 피해 면역 (생존 각성 시)
        if (pClass == PlayerClass.ADVENTURER && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            if (classManager.isAdventurerSurvival(level)) {
                event.setCancelled(true);
                return;
            }
        }

        // 저항 스칼라 적용 대상 계산
        double targetScalar = 0.0;
        if (pClass == PlayerClass.WARRIOR && level >= 8) {
            targetScalar = 0.10; // 저항 I → 10% 감소
        } else if (pClass == PlayerClass.ADVENTURER && level >= 10) {
            targetScalar = 0.20; // 저항 II → 20% 감소
        } else if (pClass == PlayerClass.ADVENTURER && level >= 5) {
            targetScalar = 0.10; // 저항 I → 10% 감소
        }

        if (targetScalar <= 0)
            return;

        // 바닐라 저항 포션의 감소율 역산 (Resistance I = 20%, Resistance II = 40%)
        PotionEffect resistEffect = victim.getPotionEffect(PotionEffectType.RESISTANCE);
        double vanillaFactor = 0.0;
        if (resistEffect != null) {
            vanillaFactor = Math.min(1.0, 0.20 * (resistEffect.getAmplifier() + 1));
        }

        // BASE 데미지를 역산 보정:
        // 원하는 최종 데미지 = base * (1 - targetScalar)
        // 실제 최종 데미지 = adjustedBase * (1 - vanillaFactor)
        // → adjustedBase = base * (1 - targetScalar) / (1 - vanillaFactor)
        if (vanillaFactor < 1.0) {
            double base = event.getDamage();
            double adjustedBase = base * (1.0 - targetScalar) / (1.0 - vanillaFactor);
            event.setDamage(adjustedBase);
        }
    }

    @EventHandler
    public void onPotionEffect(org.bukkit.event.entity.EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player))
            return;
        if (event.getModifiedType() != PotionEffectType.SPEED)
            return;

        // 신속 효과가 사라졌을 때(null), 패시브 효과를 다시 적용하기 위해 refreshSpeedPassive 호출
        if (event.getNewEffect() == null) {
            Player player = (Player) event.getEntity();
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    // [중요] 이미 패시브나 다른 신속 효과가 적용되어 있다면 중복 호출 방지 (무한 루프 방지)
                    if (player.hasPotionEffect(PotionEffectType.SPEED))
                        return;

                    classManager.refreshSpeedPassive(player);
                }
            });
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

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // 모험가 10레벨 위기 탈출 쿨타임 복구
        if (classManager.getPlayerClass(player) == PlayerClass.ADVENTURER
                && classManager.getPlayerClassLevel(player) >= 10) {
            long now = System.currentTimeMillis();
            long lastUsed = classManager.getSkillCooldown(player, "adventurer_escape");
            double totalCooldown = plugin.getConfig().getDouble("cooldowns.adventurer_teleport", 300.0);
            long diff = now - lastUsed;
            if (diff < totalCooldown * 1000L) {
                double remaining = (totalCooldown * 1000L - diff) / 1000.0;
                classManager.startCooldownDisplay(player, Material.DIAMOND_AXE, remaining, "위기 탈출", "[모험가] ");
            }
        }
    }



    @EventHandler
    public void onPlayerToggleFlight(org.bukkit.event.player.PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();

        // 크리에이티브/스펙테이터는 무조건 허용
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || player.getGameMode() == org.bukkit.GameMode.SPECTATOR)
            return;

        // 더블 점프 가능 직업(전사 8+, 마법사 8+)이 아니면 비행 강제 차단
        if (!classManager.canDoubleJump(player)) {
            event.setCancelled(true);
            player.setAllowFlight(false);
            player.setFlying(false);
            return;
        }

        // 전사인 경우에만 여기서 더블 점프 로직 실행 (마법사는 MageAbilityListener에서 처리)
        if (classManager.getPlayerClass(player) != PlayerClass.WARRIOR) {
            return;
        }

        event.setCancelled(true);
        player.setAllowFlight(false);
        player.setFlying(false);

        // 쿨타임 체크
        long now = System.currentTimeMillis();
        long lastUsed = warriorDoubleJumpCooldowns.getOrDefault(player.getUniqueId(), 0L);
        double cooldown = classManager.getWarriorDoubleJumpCooldown();
        if (now - lastUsed < cooldown * 1000L) {
            return;
        }
        warriorDoubleJumpCooldowns.put(player.getUniqueId(), now);
        double power = classManager.getWarriorDoubleJumpower();
        // 점프 효과 (현재 보는 방향으로 강력하게 돌진 및 도약)
        org.bukkit.util.Vector v = player.getLocation().getDirection().multiply(power).setY(0.4);
        player.setVelocity(v);

        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_SHOOT, 1.0f, 1.5f);
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_BAT_TAKEOFF, 0.5f, 1.2f);

        // [전사 각성] 피흡 효과 활성화 (Lv 12 or 13)
        int level = classManager.getPlayerClassLevel(player);
        if (classManager.isWarriorLifesteal(level)) {
            int durationSec = classManager.getWarriorLifestealDurationSeconds();
            player.setMetadata("warrior_lifesteal_active", new FixedMetadataValue(plugin, true));
            player.getWorld().spawnParticle(Particle.WITCH, player.getLocation(), 20, 0.5, 0.5, 0.5, 0.1);

            new org.bukkit.scheduler.BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline()) {
                        player.removeMetadata("warrior_lifesteal_active", plugin);
                    }
                }
            }.runTaskLater(plugin, (long) durationSec * 20);
        }
    }

    @EventHandler
    public void onPlayerMove(org.bukkit.event.player.PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (isStunned(player.getUniqueId())) {
            Location from = event.getFrom();
            Location to = event.getTo();
            if (to != null && (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ())) {
                Location newTo = from.clone();
                newTo.setYaw(to.getYaw());
                newTo.setPitch(to.getPitch());
                event.setTo(newTo);
            }
            return;
        }


        // 크리에이티브/스펙테이터는 건드리지 않음
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || player.getGameMode() == org.bukkit.GameMode.SPECTATOR)
            return;

        // 더블 점프 가능 직업(전사 8+, 마법사 8+)인 경우
        if (classManager.canDoubleJump(player)) {
            // 전사 더블 점프 허용 로직
            if (classManager.getPlayerClass(player) == PlayerClass.WARRIOR) {
                if (((org.bukkit.entity.Entity) player).isOnGround() && !player.getAllowFlight()) {
                    long now = System.currentTimeMillis();
                    long lastUsed = warriorDoubleJumpCooldowns.getOrDefault(player.getUniqueId(), 0L);
                    double cooldownSec = classManager.getWarriorDoubleJumpCooldown();
                    if (now - lastUsed >= cooldownSec * 1000L) {
                        player.setAllowFlight(true);
                    }
                }
            }
            // 마법사 로직은 MageAbilityListener에서 처리하므로 여기서 별도 setAllowFlight(false)를 하지 않음
            return;
        }

        // 그 외 모든 플레이어는 비행 권한을 박탈
        if (player.getAllowFlight()) {
            player.setAllowFlight(false);
            player.setFlying(false);
        }
    }

    @EventHandler(ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (isStunned(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        PlayerClass playerClass = classManager.getPlayerClass(player);
        int level = classManager.getPlayerClassLevel(player);
        Action action = event.getAction();
        long now = System.currentTimeMillis();

        // 전사 방패 돌진 준비/발동 (왼손 장착 필수, Lv6+)
        if (playerClass == PlayerClass.WARRIOR && level >= 6) {
            ItemStack offHand = player.getInventory().getItemInOffHand();
            boolean hasWarriorShield = offHand != null
                    && offHand.getType() == Material.SHIELD
                    && classManager.isJobItem(offHand);

            if (hasWarriorShield && (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)) {
                warriorShieldReadyUntil.put(player.getUniqueId(), System.currentTimeMillis() + 3000L);
            }

            if (hasWarriorShield && (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK)) {
                long readyUntil = warriorShieldReadyUntil.getOrDefault(player.getUniqueId(), 0L);
                if (now <= readyUntil) {
                    // 쿨타임 체크
                    long lastUsed = warriorShieldCooldowns.getOrDefault(player.getUniqueId(), 0L);
                    double cooldownSec = classManager.getWarriorShieldCooldown();
                    if (now - lastUsed < cooldownSec * 1000L) {
                        return;
                    }
                    warriorShieldCooldowns.put(player.getUniqueId(), now);
                    warriorShieldReadyUntil.remove(player.getUniqueId());

                    event.setCancelled(true);
                    warriorShieldChargeUntil.put(player.getUniqueId(), now + 500L);

                    // yml에서 설정한 돌진 배율 적용
                    double dashMultiplier = classManager.getWarriorDashMultiplier();
                    Vector dash = player.getLocation().getDirection().normalize().multiply(dashMultiplier).setY(0.1);
                    player.setVelocity(dash);
                    player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.1f);

                    // [자동 충돌 판정] 0.5초(10틱) 동안 주변 엔티티 감지
                    new org.bukkit.scheduler.BukkitRunnable() {
                        int ticks = 0;

                        @Override
                        public void run() {
                            if (ticks >= 10 || !player.isOnline() || player.isDead()) {
                                warriorShieldChargeUntil.remove(player.getUniqueId());
                                cancel();
                                return;
                            }

                            // 주변 1블록 내 엔티티 탐색
                            for (org.bukkit.entity.Entity entity : player.getNearbyEntities(1.0, 1.0, 1.0)) {
                                // 데미지 1 부여 (플레이어가 공격한 것으로 판정)
                                LivingEntity target = (LivingEntity) entity;
                                target.damage(1.0, player);
                                player.playSound(player.getLocation(),
                                            org.bukkit.Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.8f);
                                player.playSound(player.getLocation(),
                                            org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
                                if (entity instanceof LivingEntity && entity != player
                                        && !(entity instanceof org.bukkit.entity.ArmorStand)) {
                                    

                                    // 보스 몬스터는 무시하고 계속 탐색
                                    if (target instanceof org.bukkit.entity.Boss || target.hasMetadata("giant_zombie"))
                                        continue;

                                    // 스턴 적용
                                    target.setVelocity(new Vector(0, 0, 0));
                                    stunnedUntil.put(target.getUniqueId(), System.currentTimeMillis() + 1000L); // 20틱(1초) 동안 기절 상태 적용

                                    // 기절 효과 (암흑)
                                    target.removePotionEffect(PotionEffectType.DARKNESS);
                                    target.addPotionEffect(
                                            new PotionEffect(PotionEffectType.DARKNESS, 20, 0, false, true, false));

                                    if (target instanceof Player) {
                                        ((Player) target).sendTitle(ChatColor.RED + "기절!", "", 0, 20, 0);
                                    }
                                    
                                    warriorShieldChargeUntil.remove(player.getUniqueId());
                                    cancel();
                                    return;
                                }
                            }
                            ticks++;
                        }
                    }.runTaskTimer(plugin, 0L, 1L);

                    classManager.startCooldownDisplay(player, Material.SHIELD, cooldownSec, "방패 돌진", "[전사] ");
                }
                return;
            }
        }

        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK)
            return;

        ItemStack item = event.getItem();
        if (item == null)
            return;

        // 각성권 우클릭
        if (item.getType() == Material.ENDER_EYE && classManager.getAwakeningTicketKey() != null
                && item.getItemMeta() != null
                && item.getItemMeta().getPersistentDataContainer().has(classManager.getAwakeningTicketKey(),
                        org.bukkit.persistence.PersistentDataType.BYTE)) {
            event.setCancelled(true);
            if (playerClass == PlayerClass.NONE) {
                player.sendMessage(ChatColor.RED + "직업이 없는 유저는 사용할 수 없습니다!");
                return;
            }
            if (level < 10) {
                return;
            }

            // GUI 열기 (JobGuiListener가 처리하도록 타이틀 활용)
            plugin.getServer().getPluginManager().callEvent(new org.bukkit.event.inventory.InventoryClickEvent(
                    player.getOpenInventory(), org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, -1,
                    org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.NOTHING));
            // 직접 열어줌
            new com.example.randomclass.listeners.JobGuiListener(classManager).openJobGui(player); // 임시
            // 사실 그냥 JobGuiListener의 인스턴스를 통해 여는게 좋음.
            // 하지만 현재 구조상 직접 여는게 편함.
            player.performCommand("job"); // job 명령어가 있다고 가정하거나, 직접 JobGuiListener 접근
            return;
        }

        if (!classManager.isJobItem(item))
            return;

        // 전사의 화살(Arrow) 아이템 우클릭 체크
        // 모험가 재생 필드: 포션 우클릭 시 발동 (Lv.1+)
        if (item.getType() == Material.POTION && playerClass == PlayerClass.ADVENTURER && level >= 1) {
            event.setCancelled(true);

            long lastUsed = adventurerRegenFieldCooldowns.getOrDefault(player.getUniqueId(), 0L);
            double cooldownSec = classManager.getAdventurerRegenFieldCooldownSeconds(level);

            if (classManager.isAdventurerSurvival(level)) {
                cooldownSec += classManager.getAdventurerSurvivalCooldownIncrease();
            }

            if (now - lastUsed < cooldownSec * 1000L) {
                return;
            }

            spawnAdventurerRegenField(player, level);
            adventurerRegenFieldCooldowns.put(player.getUniqueId(), now);
            classManager.startCooldownDisplay(player, Material.POTION, cooldownSec, "재생 필드", "[모험가] ");
            return;
        }

        // 전사 스킬 발동 (애로우 스톰: 화살 / 금강불괴: 종)
        if (playerClass == PlayerClass.WARRIOR && level >= 3) {
            Material triggerMat = Material.ARROW;
            boolean isGeumgang = classManager.isWarriorGeumgang(level);
            if (isGeumgang) {
                triggerMat = Material.BELL;
            }

            if (item.getType() == triggerMat) {
                // 쿨타임 체크
                long lastUsed = warriorArrowStormCooldowns.getOrDefault(player.getUniqueId(), 0L);
                double cooldownSec;
                if (isGeumgang) {
                    cooldownSec = classManager.getWarriorGeumgangCooldown();
                } else {
                    cooldownSec = classManager.getWarriorArrowStormCooldown(level);
                }

                if (now - lastUsed < cooldownSec * 1000L) {
                    return;
                }

                warriorArrowStormCooldowns.put(player.getUniqueId(), now);
                String skillName = isGeumgang ? "금강불괴" : "애로우 스톰";

                if (isGeumgang) {
                    event.setCancelled(true);
                    activateGeumgangbulgoe(player);
                } else {
                    event.setCancelled(true);
                    fireArrowStorm(player, level);
                }

                classManager.startCooldownDisplay(player, triggerMat, cooldownSec, skillName, "[전사] ");

                player.sendMessage(classManager.getMessage("prefix.warrior", "&6[전사] ")
                        + ChatColor.GOLD + skillName + " 발동!");
                return;
            }
        }
    }

    private void activateGeumgangbulgoe(Player player) {
        int durationSec = classManager.getWarriorGeumgangDurationSeconds();
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, durationSec * 20, 4, false, false));
        player.setMetadata("warrior_geumgang_active", new FixedMetadataValue(plugin, true));

        player.getWorld().spawnParticle(Particle.ENCHANTED_HIT, player.getLocation().add(0, 1, 0), 50, 0.5, 0.5, 0.5,
                0.1);
        player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.BLOCK_BELL_USE, 1.2f, 1.0f); // 종소리 추가
        player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ITEM_SHIELD_BLOCK, 1.0f, 0.8f);

        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    player.removeMetadata("warrior_geumgang_active", plugin);
                    player.sendMessage(ChatColor.GRAY + "금강불괴 효과가 종료되었습니다.");
                }
            }
        }.runTaskLater(plugin, (long) durationSec * 20);
    }

    private void fireArrowStorm(Player player, int level) {
        int count = classManager.getWarriorArrowStormCount();
        Location loc = player.getLocation().add(0, 0.9, 0);

        double angleStep = 360.0 / count;
        for (int i = 0; i < count; i++) {
            final Vector direction = new Vector(
                    Math.cos(Math.toRadians(i * angleStep)),
                    0.1,
                    Math.sin(Math.toRadians(i * angleStep))).normalize();

            player.getWorld().spawn(loc, Arrow.class, arrow -> {
                arrow.setShooter(player);
                arrow.setVelocity(direction.multiply(2.0));
                arrow.setMetadata("arrow_storm_arrow", new FixedMetadataValue(plugin, true));
                arrow.setMetadata("arrow_storm_level", new FixedMetadataValue(plugin, level));
                arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);

                // 화살 비행 중 파티클 생성 스케줄러
                new org.bukkit.scheduler.BukkitRunnable() {
                    @Override
                    public void run() {
                        if (arrow.isDead() || !arrow.isValid() || arrow.isOnGround()) {
                            this.cancel();
                            return;
                        }
                        Location arrowLoc = arrow.getLocation();
                        arrowLoc.getWorld().spawnParticle(org.bukkit.Particle.FLAME, arrowLoc, 3, 0.05, 0.05, 0.05,
                                0.02);
                        arrowLoc.getWorld().spawnParticle(org.bukkit.Particle.SMOKE, arrowLoc, 1, 0, 0, 0, 0.01);
                    }
                }.runTaskTimer(plugin, 0L, 1L);
            });
        }

        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ARROW_SHOOT, 1.0f, 0.5f);
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.5f);
    }

    private void spawnAdventurerRegenField(Player player, int level) {
        int durationSec = classManager.getAdventurerRegenFieldDurationSeconds(level);
        int regenAmp = classManager.getAdventurerRegenFieldAmplifier(level); // 0=I, 1=1.5, 2=II
        float radius = (float) classManager.getAdventurerRegenFieldRadius(level);
        boolean isSurvival = classManager.isAdventurerSurvival(level);

        AreaEffectCloud cloud = (AreaEffectCloud) player.getWorld().spawnEntity(player.getLocation(),
                org.bukkit.entity.EntityType.AREA_EFFECT_CLOUD);
        if (isSurvival) {
            cloud.setParticle(Particle.TOTEM_OF_UNDYING);
            cloud.setParticle(Particle.HEART);
        } else {
            cloud.setParticle(Particle.HEART);
        }
        cloud.setRadius(radius);
        cloud.setDuration(durationSec * 20);
        cloud.setWaitTime(0);
        Set<UUID> absorptionGiven = new HashSet<>();

        // 커스텀 재생 로직 (0=재생 I(50틱), 1=재생 1.5(37틱), 2=재생 II(25틱))
        int intervalTicks = 25;
        String regenDisplayName = "I";
        if (regenAmp == 1) {
            intervalTicks = 25;
            regenDisplayName = "I";
        } else if (regenAmp == 2) {
            intervalTicks = 20;
            regenDisplayName = "II";
        } else if (regenAmp >= 3) {
            intervalTicks = 15;
            regenDisplayName = "III";
        }

        final int finalInterval = intervalTicks;
        new org.bukkit.scheduler.BukkitRunnable() {
            int elapsed = 0;

            @Override
            public void run() {
                if (cloud.isDead() || !cloud.isValid() || elapsed >= durationSec * 20) {
                    this.cancel();
                    return;
                }

                // 매 인터벌마다 반경 내 생명체 회복
                if (elapsed % finalInterval == 0) {
                    for (org.bukkit.entity.Entity entity : cloud.getNearbyEntities(radius, 2.0, radius)) {
                        if (entity instanceof LivingEntity) {
                            LivingEntity le = (LivingEntity) entity;
                            double maxHealth = le.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                            if (le.getHealth() < maxHealth) {
                                le.setHealth(Math.min(maxHealth, le.getHealth() + 1.0));
                            }
                        }
                    }
                }

                // [강화된 파동] 생존 각성 시 0.25초(5틱)마다 디버프 해제 및 버프 갱신
                if (isSurvival) {
                    int buffDurationTicks = classManager.getAdventurerSurvivalBuffDuration() * 20;
                    for (org.bukkit.entity.Entity entity : cloud.getNearbyEntities(radius, 2.0, radius)) {
                        if (entity instanceof Player) {
                            Player targetPlayer = (Player) entity;

                            if (isStunned(targetPlayer.getUniqueId())) {
                                stunnedUntil.remove(targetPlayer.getUniqueId());
                                targetPlayer.sendTitle("정화!", "", 0, 10, 0);
                            }
                            // 상태이상 해제 (슬로우, 독, 위더 등) - 파동 안에 있으면 즉각적으로 지속 해제
                            targetPlayer.removePotionEffect(PotionEffectType.SLOWNESS);
                            targetPlayer.removePotionEffect(PotionEffectType.MINING_FATIGUE);
                            targetPlayer.removePotionEffect(PotionEffectType.WEAKNESS);
                            targetPlayer.removePotionEffect(PotionEffectType.POISON);
                            targetPlayer.removePotionEffect(PotionEffectType.WITHER);
                            targetPlayer.removePotionEffect(PotionEffectType.DARKNESS);
                            targetPlayer.removePotionEffect(PotionEffectType.HUNGER);
                            int absorptionduration = buffDurationTicks
                                    + (classManager.getAdventurerRegenFieldDurationSeconds(10) * 20);
                            // 황금 사과 효과 (재생 II, 흡수 I) 5초 유지
                            if (!absorptionGiven.contains(targetPlayer.getUniqueId())) {
                                targetPlayer.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,
                                        absorptionduration, 1, false, false, true));
                                absorptionGiven.add(targetPlayer.getUniqueId());
                            }

                            targetPlayer.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,
                                    buffDurationTicks, 1, false, false, true));

                            // 이동 속도 증가 (신속 II)
                            targetPlayer.addPotionEffect(
                                    new PotionEffect(PotionEffectType.SPEED, buffDurationTicks, 1, false, false,
                                            false));
                        }
                    }
                }

                elapsed += 5;
            }
        }.runTaskTimer(plugin, 0L, 5L);

        if (isSurvival) {
            player.sendMessage(classManager.getMessage("prefix.adventurer", "&a[모험가] ")
                    + ChatColor.GOLD + "강화된 치유 파동을 전개했습니다! (상태이상 해제 및 버프 부여) (" + durationSec + "초)");
        } else {
            player.sendMessage(classManager.getMessage("prefix.adventurer", "&a[모험가] ")
                    + ChatColor.GREEN + "재생 필드를 전개했습니다! (" + durationSec + "초, 재생 " + regenDisplayName + ")");
        }
        player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.BLOCK_BELL_USE, 1.1f, 1.1f);
    }

    private void announceTicketDrop(Player player, String ticketName, boolean broadcast) {
        player.sendMessage(ChatColor.GOLD + ticketName + ChatColor.WHITE + "을(를) 획득했습니다!");

        if (!broadcast || !classManager.isAnnounceTicketDropsEnabled())
            return;

        org.bukkit.Bukkit.broadcastMessage(
                ChatColor.YELLOW + "[알림] " + ChatColor.WHITE + player.getName() + "님이 " + ChatColor.GOLD + ticketName
                        + ChatColor.WHITE + "을(를) 획득했습니다!");
    }

    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (isStunned(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        ItemStack toOffHand = event.getOffHandItem();
        ItemStack toMainHand = event.getMainHandItem();

        boolean involvesRegenPotion = (toOffHand != null && toOffHand.getType() == Material.POTION
                && classManager.isJobItem(toOffHand))
                || (toMainHand != null && toMainHand.getType() == Material.POTION
                        && classManager.isJobItem(toMainHand));

        if (!involvesRegenPotion)
            return;

        PlayerClass pClass = classManager.getPlayerClass(player);
        if (pClass != PlayerClass.ADVENTURER)
            return;

        int level = classManager.getPlayerClassLevel(player);
        if (!classManager.isAdventurerValor(level))
            return;

        event.setCancelled(true);

        if (toOffHand != null && toOffHand.getType() == Material.POTION && classManager.isJobItem(toOffHand)) {
            tryValorTrigger(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteractEntity(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (isStunned(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDropItem(org.bukkit.event.player.PlayerDropItemEvent event) {
        if (isStunned(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private void tryValorTrigger(Player player) {
        long now = System.currentTimeMillis();
        long readyTime = adventurerValorCooldowns.getOrDefault(player.getUniqueId(), 0L);
        double cdSeconds = classManager.getAdventurerValorCooldown();

        if (now < readyTime) {
            double remaining = (readyTime - now) / 1000.0;
            player.sendMessage(ChatColor.RED + "용맹이 아직 준비되지 않았습니다! ("
                    + String.format("%.1f", remaining) + "초)");
            return;
        }

        long newReadyTime = now + (long) (cdSeconds * 1000L);
        adventurerValorCooldowns.put(player.getUniqueId(), newReadyTime);
        activateValor(player);
    }

    private void activateValor(Player player) {
        double duration = classManager.getAdventurerValorDuration();
        double radius = classManager.getAdventurerValorRadius();
        long now = System.currentTimeMillis();
        long durationMillis = (long) (duration * 1000);

        classManager.setValorAuraEndTime(player, now + durationMillis);

        // 시전자에게 시각적 표시용 포션 효과 (실제 효과 없음, 효과상에만 표시)
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.LUCK,
                (int) (duration * 20),
                0,
                false, // 주변 효과없음
                false, // 파티클없음
                true // 효과상 표시 O
        ));

        player.sendMessage(classManager.getMessage("prefix.adventurer", "&a[모험가] ")
                + ChatColor.GOLD + "용맹 버프를 발동했습니다! (" + (int) duration + "초)");
        player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 1.2f);
        player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 100, 0.5, 0.5,
                0.5, 0.1);

        startActionBarCooldown(player, classManager.getAdventurerValorCooldown(), "용맹");

        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                // 매 틱마다 시전자의 최신 위치와 객체를 가져옴
                Player p = org.bukkit.Bukkit.getPlayer(player.getUniqueId());
                if (p == null || !p.isOnline()) {
                    this.cancel();
                    return;
                }

                long currentTime = System.currentTimeMillis();
                if (currentTime > classManager.getValorAuraEndTime(p)) {
                    p.sendMessage(classManager.getMessage("prefix.adventurer", "&a[모험가] ") + ChatColor.RED
                            + "용맹 오우라가 종료되었습니다.");
                    this.cancel();
                    return;
                }

                long auraEndTime = classManager.getValorAuraEndTime(p);
                Location currentLoc = p.getLocation().add(0, 0.1, 0);

                // 1. 주변 플레이어 버프 갱신 (반경 내 모든 아군)
                for (org.bukkit.entity.Entity entity : p.getWorld().getNearbyEntities(currentLoc, radius, radius,
                        radius)) {
                    if (entity instanceof Player) {
                        Player target = (Player) entity;
                        // 1틱마다 실행되므로 0.3초 정도면 충분함
                        classManager.setValorBuffEndTime(target, System.currentTimeMillis() + 300L);
                        classManager.setValorBuffAuraEndTime(target, auraEndTime);
                    }
                }

                // 2. 오우라 효과 - 10블록 전체 공간 채우기 및 경계선
                java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
                // 노란색 DUST 파티클 설정 (색상, 크기)
                Particle.DustOptions yellowDust = new Particle.DustOptions(Color.YELLOW, 1.2f);

                // 경계선 (노란색 먼지)
                for (int i = 0; i < 360; i += 12) {
                    double angle = Math.toRadians(i);
                    double x = radius * Math.cos(angle);
                    double z = radius * Math.sin(angle);
                    p.getWorld().spawnParticle(Particle.DUST, currentLoc.clone().add(x, 0, z), 1, 0, 0, 0, 0,
                            yellowDust);
                }

                // 내부 공간 (10블록 전체를 덮는 노란색 기운)
                for (int k = 0; k < 15; k++) {
                    double r = Math.sqrt(random.nextDouble()) * radius;
                    double a = random.nextDouble() * 2 * Math.PI;
                    double px = r * Math.cos(a);
                    double pz = r * Math.sin(a);
                    p.getWorld().spawnParticle(Particle.DUST, currentLoc.clone().add(px, random.nextDouble() * 0.4, pz),
                            1, 0.1, 0.1, 0.1, 0, yellowDust);
                }

                // 3. 시전자 주변 특수 효과 (회전하는 빛)
                double orbitRadius = 0.8;
                long ticks = System.currentTimeMillis() / 50;
                for (int j = 0; j < 2; j++) {
                    double offset = j * Math.PI;
                    double angle = (ticks * 0.2) + offset;
                    double ox = orbitRadius * Math.cos(angle);
                    double oz = orbitRadius * Math.sin(angle);
                    double oy = 1.0 + 0.5 * Math.sin(ticks * 0.1 + offset);
                    p.getWorld().spawnParticle(Particle.END_ROD, currentLoc.clone().add(ox, oy, oz), 1, 0, 0, 0, 0.01);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L); // 1틱마다 실행하여 완벽한 추적과 풍성한 효과 제공
    }

    private void startActionBarCooldown(Player player, double cooldownSeconds, String skillName) {
        new org.bukkit.scheduler.BukkitRunnable() {
            double remaining = cooldownSeconds;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }

                if (remaining > 0.01) {
                    String timeText;
                    if (Math.abs(remaining - Math.round(remaining)) < 0.01) {
                        timeText = String.valueOf((int) remaining);
                    } else {
                        timeText = String.format("%.1f", remaining);
                    }

                    player.spigot().sendMessage(
                            net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                            new net.md_5.bungee.api.chat.TextComponent(
                                    ChatColor.AQUA + skillName
                                            + ChatColor.RED + " 쿨타임: "
                                            + timeText + "초"));

                    remaining = Math.max(0, remaining - 0.1);
                } else {
                    player.spigot().sendMessage(
                            net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                            new net.md_5.bungee.api.chat.TextComponent(
                                    ChatColor.AQUA + skillName
                                            + ChatColor.GREEN + " 준비 완료!"));

                    player.sendMessage(classManager.getMessage("prefix.adventurer", "&a[모험가] ")
                            + ChatColor.GREEN + skillName + " 능력을 다시 사용할 수 있습니다!");

                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }
}
