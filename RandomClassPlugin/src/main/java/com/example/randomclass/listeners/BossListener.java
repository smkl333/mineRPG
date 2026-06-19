package com.example.randomclass.listeners;

import com.example.randomclass.managers.BossManager;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;

public class BossListener implements Listener {

    private final BossManager bossManager;
    private final org.bukkit.plugin.Plugin plugin;
    private long lastAttackTime = 0;

    public BossListener(BossManager bossManager, org.bukkit.plugin.Plugin plugin) {
        this.bossManager = bossManager;
        this.plugin = plugin;
    }

    @EventHandler
    public void onBossDamage(EntityDamageByEntityEvent event) {
        Zombie currentBoss = bossManager.getCurrentBoss();
        if (currentBoss == null || currentBoss.isDead() || !event.getDamager().hasMetadata("giant_zombie"))
            return;

        // 보스가 플레이어를 공격할 때
        if (event.getDamager().equals(currentBoss) && event.getEntity() instanceof Player) {
            long now = System.currentTimeMillis();
            double cooldownMs = bossManager.getAttackCooldown() * 1000.0;

            // 1. 공격 속도 제한
            if (now - lastAttackTime < cooldownMs) {
                event.setCancelled(true);
                return;
            }

            lastAttackTime = now;

            // 2. 흡혈 로직 및 상태 이상 부여
            // 데미지는 이벤트의 최종 데미지를 기준으로 함
            double damageDealt = event.getFinalDamage();
            if (damageDealt > 0) {
                // 흡혈 비율 적용 (동적으로 가져옴)
                double healAmount = damageDealt * bossManager.getLifestealRatio();
                double maxHealth = currentBoss.getAttribute(Attribute.MAX_HEALTH).getValue();
                currentBoss.setHealth(Math.min(maxHealth, currentBoss.getHealth() + healAmount));
                bossManager.updateBossBar();

                // 플레이어에게 허기 효과 부여 (10초, 허기 1)
                Player target = (Player) event.getEntity();
                target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.HUNGER,
                        200, 1, false, true));
            }
        }
    }

    @EventHandler
    public void onBossTakeDamage(EntityDamageEvent event) {
        Zombie currentBoss = bossManager.getCurrentBoss();
        if (currentBoss != null && event.getEntity().equals(currentBoss)) {
            // 기여자 등록
            if (event instanceof EntityDamageByEntityEvent) {
                EntityDamageByEntityEvent e = (EntityDamageByEntityEvent) event;
                Player attacker = null;
                if (e.getDamager() instanceof Player) {
                    attacker = (Player) e.getDamager();
                } else if (e.getDamager() instanceof org.bukkit.entity.Projectile) {
                    if (((org.bukkit.entity.Projectile) e.getDamager()).getShooter() instanceof Player) {
                        attacker = (Player) ((org.bukkit.entity.Projectile) e.getDamager()).getShooter();
                    }
                }
                if (attacker != null) {
                    bossManager.addContributor(attacker.getUniqueId());
                }
            }

            // 체력바 업데이트는 데미지 적용 후 계산을 위해 스케줄러 사용
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                bossManager.updateBossBar();
            });
        }
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        // [추가] 하수인 처치 시 보상 제거
        if (event.getEntity().hasMetadata("boss_minion")) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            return;
        }

        Zombie currentBoss = bossManager.getCurrentBoss();
        if (currentBoss != null && event.getEntity().equals(currentBoss)) {
            bossManager.handleBossDeath(event.getEntity().getKiller());
        }
    }
}
