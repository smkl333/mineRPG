package com.example.randomclass.listeners;

import com.example.randomclass.ClassManager;
import com.example.randomclass.PlayerClass;
import com.example.randomclass.RandomClassPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.ChatColor;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Animals;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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
    private final Map<UUID, Long> fireballCooldowns = new HashMap<>();
    private final Map<UUID, Long> frostJumpCooldowns = new HashMap<>();
    private final Map<UUID, Long> surgeCooldowns = new HashMap<>();
    private final Map<Location, UUID> frostFieldBlocks = new HashMap<>();

    public MageAbilityListener(RandomClassPlugin plugin, ClassManager classManager) {
        this.plugin = plugin;
        this.classManager = classManager;
    }

    public void cleanup(UUID uuid) {
        wandCooldowns.remove(uuid);
        spellbookCooldowns.remove(uuid);
        fireballCooldowns.remove(uuid);
        frostJumpCooldowns.remove(uuid);
        surgeCooldowns.remove(uuid);
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

        // 마법사 지팡이 (TNT 소환)
        if (item.getType() == Material.BLAZE_ROD) {
            event.setCancelled(true);
            long now = System.currentTimeMillis();
            long readyTimeAt = wandCooldowns.getOrDefault(player.getUniqueId(), 0L);
            if (now < readyTimeAt) {
                return;
            }

            double wandCooldown = getWandCooldown(player);
            if (classManager.isMageBlackHole(classManager.getPlayerClassLevel(player))) {
                wandCooldown += classManager.getMageBlackHoleCooldownIncrease();
            }

            long newReadyTime = now + (long) (wandCooldown * 1000L);
            wandCooldowns.put(player.getUniqueId(), newReadyTime);
            spawnMageTNT(player);
            classManager.startCooldownDisplay(player, item.getType(), () -> wandCooldowns.getOrDefault(player.getUniqueId(), 0L), "TNT 소환", "[마법사] ");
        }
        // 마법서 (순간이동)
        else if (item.getType() == Material.ENCHANTED_BOOK) {
            event.setCancelled(true);
            long now = System.currentTimeMillis();
            long readyTimeAt = spellbookCooldowns.getOrDefault(player.getUniqueId(), 0L);
            if (now < readyTimeAt) {
                return;
            }

            if (teleportMage(player)) {
                double spellCooldown = getSpellbookCooldown(player);
                long newReadyTime = now + (long) (spellCooldown * 1000L);
                spellbookCooldowns.put(player.getUniqueId(), newReadyTime);
                classManager.startCooldownDisplay(player, item.getType(), () -> spellbookCooldowns.getOrDefault(player.getUniqueId(), 0L), "순간이동", "[마법사] ");
            }
        } else if (item.getType() == Material.FIRE_CHARGE && classManager.getPlayerClassLevel(player) >= 5) {
            event.setCancelled(true);
            long now = System.currentTimeMillis();
            long readyTimeAt = fireballCooldowns.getOrDefault(player.getUniqueId(), 0L);
            if (now < readyTimeAt) {
                return;
            }

            double fireballCooldown = getFireballCooldown(player);
            long newReadyTime = now + (long) (fireballCooldown * 1000L);
            fireballCooldowns.put(player.getUniqueId(), newReadyTime);
            launchMageFireball(player);
            classManager.startCooldownDisplay(player, item.getType(), () -> fireballCooldowns.getOrDefault(player.getUniqueId(), 0L), "화염구", "[마법사] ");
        }
    }

    @EventHandler
    public void onMageFireballDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof SmallFireball)) {
            return;
        }

        SmallFireball fireball = (SmallFireball) event.getDamager();
        if (!fireball.hasMetadata("mage_fireball")) {
            return;
        }

        String ownerUuid = getMageFireballOwner(fireball);
        if (event.getEntity() instanceof Player
                && ((Player) event.getEntity()).getUniqueId().toString().equals(ownerUuid)) {
            event.setCancelled(true);
            return;
        }

        if (event.getEntity() instanceof LivingEntity) {
            int level = 1;
            if (!ownerUuid.isEmpty()) {
                try {
                    Player owner = org.bukkit.Bukkit.getPlayer(UUID.fromString(ownerUuid));
                    if (owner != null) {
                        level = classManager.getPlayerClassLevel(owner);
                    }
                } catch (Exception e) {
                }
            }
            event.setDamage(classManager.getMageFireballDamage(level));
        }
    }

    @EventHandler
    public void onMageFireballHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof SmallFireball)
                || !event.getEntity().hasMetadata("mage_fireball")) {
            return;
        }

        SmallFireball fireball = (SmallFireball) event.getEntity();
        Location flameLoc = fireball.getLocation();
        if (event.getHitEntity() != null) {
            flameLoc = event.getHitEntity().getLocation().add(0, 1.0, 0);
        } else if (event.getHitBlock() != null) {
            flameLoc = event.getHitBlock().getLocation().add(0.5, 1.1, 0.5);
        }

        // [이펙트 강화] 착탄 시 큰 폭발 파티클 및 사운드
        flameLoc.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION_EMITTER, flameLoc, 1);
        flameLoc.getWorld().spawnParticle(org.bukkit.Particle.LAVA, flameLoc, 20, 0.5, 0.5, 0.5, 0.1);
        flameLoc.getWorld().spawnParticle(org.bukkit.Particle.FLAME, flameLoc, 50, 1.0, 1.0, 1.0, 0.2);
        flameLoc.getWorld().playSound(flameLoc, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.8f);
        flameLoc.getWorld().playSound(flameLoc, org.bukkit.Sound.ITEM_FIRECHARGE_USE, 1.5f, 0.5f);

        // [추가] 착탄 즉시 주변 데미지 (폭발 데미지)
        String ownerUuid = getMageFireballOwner(fireball);
        double explosionRadius = classManager.getMageFireballExplosionRadius();

        int level = 1;
        if (!ownerUuid.isEmpty()) {
            try {
                Player owner = org.bukkit.Bukkit.getPlayer(UUID.fromString(ownerUuid));
                if (owner != null) {
                    level = classManager.getPlayerClassLevel(owner);
                }
            } catch (Exception e) {
            }
        }
        double explosionDamage = classManager.getMageFireballDamage(level);

        for (org.bukkit.entity.Entity nearby : flameLoc.getWorld().getNearbyEntities(flameLoc, explosionRadius,
                explosionRadius, explosionRadius)) {
            if (!(nearby instanceof LivingEntity))
                continue;

            LivingEntity le = (LivingEntity) nearby;

            // 시전자 본인은 데미지 무시
            if (le.getUniqueId().toString().equals(ownerUuid))
                continue;

            // 직접 맞은 엔티티는 이미 onMageFireballDamage에서 데미지를 입었으므로 제외 (중복 방지)
            if (le.equals(event.getHitEntity()))
                continue;

            le.damage(explosionDamage, fireball);
        }

        startMageFirePatch(flameLoc, ownerUuid);
        fireball.remove();
    }

    @EventHandler
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();

        // 크리에이티브/스펙테이터는 무조건 허용
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return;
        }

        // 더블 점프 가능 직업(전사 8+, 마법사 8+)이 아니면 비행 강제 차단
        if (!classManager.canDoubleJump(player)) {
            event.setCancelled(true);
            player.setAllowFlight(false);
            player.setFlying(false);
            return;
        }

        // 마법사인 경우에만 여기서 더블 점프 로직 실행 (전사는 ClassAbilityListener에서 처리)
        if (classManager.getPlayerClass(player) != PlayerClass.MAGE) {
            return;
        }

        event.setCancelled(true);
        player.setAllowFlight(false);
        player.setFlying(false);

        long now = System.currentTimeMillis();
        long readyTimeAt = frostJumpCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now < readyTimeAt) {
            return;
        }

        double cooldown = getFrostJumpCooldown(player);
        long newReadyTime = now + (long) (cooldown * 1000L);
        frostJumpCooldowns.put(player.getUniqueId(), newReadyTime);
        double power = classManager.getMageFrostJumpPower();
        player.setVelocity(player.getLocation().getDirection().multiply(power * 1.0).setY(power));
        createFrostField(player);

        // 12, 13 레벨이면 서리 도약의 쿨타임이 아예 보여지면 안됨 (원소 폭주 쿨타임 표시를 위해)
        int level = classManager.getPlayerClassLevel(player);
        if (level < 12) {
            startActionBarCooldown(player, cooldown, "서리 도약");
        }

        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_GLASS_BREAK, 0.9f, 1.4f);
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.2f);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        // 크리에이티브/스펙테이터는 무조건 허용
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return;
        }

        // 마법사 & 레벨 8 이상인 경우에만 서리 도약(더블 점프) 허용
        if (classManager.getPlayerClass(player) == PlayerClass.MAGE
                && classManager.getPlayerClassLevel(player) >= 8) {
            if (((org.bukkit.entity.Entity) player).isOnGround()
                    && !player.getAllowFlight()
                    && isFrostJumpReady(player)) {
                player.setAllowFlight(true);
            }
        }
        // 전사의 경우 ClassAbilityListener에서 처리하며, 그 외 비행 차단도 ClassAbilityListener에서 통합 관리함

        Location below = player.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.DOWN).getLocation();
        UUID owner = frostFieldBlocks.get(normalizeBlockLocation(below));
        if (owner == null || owner.equals(player.getUniqueId())) {
            return;
        }

        // 둔화 적용 (config 설정값 기반)
        classManager.applyCustomSlow(player, classManager.getMageFrostJumpSlowPercentage(),
                classManager.getMageFrostJumpSlowDurationTicks());

        Vector v = player.getVelocity();
        player.setVelocity(new Vector(v.getX() * 0.45, v.getY(), v.getZ() * 0.65));
    }



    private double getWandCooldown(Player player) {
        double base = classManager.getMageWandCooldown();
        int level = classManager.getPlayerClassLevel(player);
        double cd = base * classManager.getMageCooldownMultiplier(level);
        if (player.hasMetadata("mage_surge_active")) {
            cd *= (1.0 - classManager.getMageSurgeCooldownReduction());
        }
        return cd;
    }

    private double getSpellbookCooldown(Player player) {
        double base = classManager.getMageSpellbookCooldown();
        int level = classManager.getPlayerClassLevel(player);
        double cd = base * classManager.getMageCooldownMultiplier(level);
        if (player.hasMetadata("mage_surge_active")) {
            cd *= (1.0 - classManager.getMageSurgeCooldownReduction());
        }
        return cd;
    }

    private double getFireballCooldown(Player player) {
        double base = classManager.getMageFireballCooldown();
        int level = classManager.getPlayerClassLevel(player);
        double cd = base * classManager.getMageCooldownMultiplier(level);
        if (player.hasMetadata("mage_surge_active")) {
            cd *= (1.0 - classManager.getMageSurgeCooldownReduction());
        }
        return cd;
    }

    private double getFrostJumpCooldown(Player player) {
        double base = classManager.getMageFrostJumpCooldown();
        int level = classManager.getPlayerClassLevel(player);
        double cd = base * classManager.getMageCooldownMultiplier(level);
        if (player.hasMetadata("mage_surge_active")) {
            cd *= (1.0 - classManager.getMageSurgeCooldownReduction());
        }
        return cd;
    }

    private boolean isFrostJumpReady(Player player) {
        long now = System.currentTimeMillis();
        long readyTime = frostJumpCooldowns.getOrDefault(player.getUniqueId(), 0L);
        return now >= readyTime;
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

                    player.sendMessage(classManager.getMessage("prefix.mage", "&d[마법사] ")
                            + ChatColor.GREEN + skillName + " 능력을 다시 사용할 수 있습니다!");

                    cancel();
                }
            }

        }.runTaskTimer(plugin, 0L, 2L);
    }

    private Location normalizeBlockLocation(Location location) {
        return new Location(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private void createFrostField(Player player) {
        Location center = player.getLocation();
        int radius = classManager.getMageFrostJumpRadius();
        int durationTicks = classManager.getMageFrostJumpDurationSeconds() * 20;
        UUID owner = player.getUniqueId();
        Map<Location, BlockData> originalBlocks = new HashMap<>();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if ((x * x) + (z * z) > radius * radius) {
                    continue;
                }

                Block ground = findGroundBelow(center.clone().add(x, 0, z));
                if (!ground.getType().isSolid() || ground.getType() == Material.BEDROCK) {
                    continue;
                }

                Location groundLoc = normalizeBlockLocation(ground.getLocation());
                if (!frostFieldBlocks.containsKey(groundLoc)) {
                    originalBlocks.put(groundLoc, ground.getBlockData());
                    frostFieldBlocks.put(groundLoc, owner);
                    ground.setType(((x + z) % 2 == 0) ? Material.ICE : Material.SNOW_BLOCK, false);
                }

                Block top = ground.getRelative(org.bukkit.block.BlockFace.UP);
                if (top.getType().isAir()) {
                    Location topLoc = normalizeBlockLocation(top.getLocation());
                    if (!frostFieldBlocks.containsKey(topLoc)) {
                        originalBlocks.put(topLoc, top.getBlockData());
                        frostFieldBlocks.put(topLoc, owner);
                        top.setType(Material.SNOW, false);
                    }
                }
            }
        }

        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                for (Map.Entry<Location, BlockData> entry : originalBlocks.entrySet()) {
                    Block block = entry.getKey().getBlock();
                    block.setBlockData(entry.getValue(), false);
                    frostFieldBlocks.remove(normalizeBlockLocation(block.getLocation()));
                }
            }
        }.runTaskLater(plugin, durationTicks);
    }

    private Block findGroundBelow(Location location) {
        Block block = location.getBlock();
        for (int i = 0; i < 8; i++) {
            if (block.getType().isSolid()) {
                return block;
            }
            block = block.getRelative(org.bukkit.block.BlockFace.DOWN);
        }
        return block;
    }

    private void launchMageFireball(Player player) {
        Location spawnLoc = player.getEyeLocation().add(player.getLocation().getDirection().multiply(1.2));
        Vector direction = player.getLocation().getDirection().normalize();

        player.getWorld().spawn(spawnLoc, SmallFireball.class, fireball -> {
            fireball.setShooter(player);
            fireball.setDirection(direction);
            fireball.setVelocity(direction.clone().multiply(1.4));
            fireball.setIsIncendiary(false);
            fireball.setYield(0);
            fireball.setMetadata("mage_fireball", new FixedMetadataValue(plugin, true));
            fireball.setMetadata("mage_fireball_owner",
                    new FixedMetadataValue(plugin, player.getUniqueId().toString()));

            // [이펙트 강화] 비행 중 파티클 꼬리 생성
            new org.bukkit.scheduler.BukkitRunnable() {
                @Override
                public void run() {
                    if (fireball.isDead() || !fireball.isValid()) {
                        cancel();
                        return;
                    }
                    Location loc = fireball.getLocation();
                    loc.getWorld().spawnParticle(org.bukkit.Particle.FLAME, loc, 5, 0.1, 0.1, 0.1, 0.02);
                    loc.getWorld().spawnParticle(org.bukkit.Particle.LARGE_SMOKE, loc, 3, 0.05, 0.05, 0.05, 0.01);
                }
            }.runTaskTimer(plugin, 0L, 1L);
        });

        player.playSound(player.getLocation(), org.bukkit.Sound.ITEM_FIRECHARGE_USE, 1.0f, 1.0f);
        player.sendMessage(classManager.getMessage("prefix.mage", "&d[마법사] ") + ChatColor.GOLD + "화염구 발사!");
    }

    private String getMageFireballOwner(SmallFireball fireball) {
        if (!fireball.hasMetadata("mage_fireball_owner")) {
            return "";
        }
        return fireball.getMetadata("mage_fireball_owner").get(0).asString();
    }

    private void startMageFirePatch(Location location, String ownerUuid) {
        final Location center = location.clone();
        final double radius = classManager.getMageFireballFlameRadius();
        final double damage = classManager.getMageFireballFlameDamage();
        final int maxTicks = classManager.getMageFireballFlameDurationSeconds() * 20;

        new org.bukkit.scheduler.BukkitRunnable() {
            private int ticks = 0;

            @Override
            public void run() {
                if (ticks >= maxTicks) {
                    cancel();
                    return;
                }

                center.getWorld().spawnParticle(org.bukkit.Particle.FLAME, center, 24, radius / 2.0, 0.15, radius / 2.0,
                        0.02);
                center.getWorld().spawnParticle(org.bukkit.Particle.SMOKE, center, 8, radius / 2.0, 0.1, radius / 2.0,
                        0.01);

                for (org.bukkit.entity.Entity entity : center.getWorld().getNearbyEntities(center, radius, 1.5,
                        radius)) {
                    if (!(entity instanceof LivingEntity)) {
                        continue;
                    }
                    if (entity instanceof Player && ((Player) entity).getUniqueId().toString().equals(ownerUuid)) {
                        continue;
                    }
                    if (entity instanceof Player || entity instanceof Monster || entity instanceof Animals) {
                        ((LivingEntity) entity).damage(damage);
                        entity.setFireTicks(Math.max(entity.getFireTicks(), 60));
                    }
                }

                ticks += 20;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void spawnMageTNT(Player player) {
        Location spawnLoc = player.getEyeLocation().add(player.getLocation().getDirection().multiply(1.5));
        int fuseTicks = classManager.getMageTntFuseTicks();
        String ownerUuid = player.getUniqueId().toString();
        int level = classManager.getPlayerClassLevel(player);
        boolean isBlackHole = classManager.isMageBlackHole(level);

        player.getWorld().spawn(spawnLoc, TNTPrimed.class, tnt -> {
            tnt.setFuseTicks(fuseTicks);
            tnt.setVelocity(player.getLocation().getDirection().multiply(0.8));
            tnt.setSource(player);
            tnt.setMetadata("mage_tnt", new FixedMetadataValue(plugin, true));
            tnt.setMetadata("mage_owner_uuid", new FixedMetadataValue(plugin, ownerUuid));
            if (isBlackHole) {
                tnt.setMetadata("mage_blackhole", new FixedMetadataValue(plugin, true));
            }
        });

        if (isBlackHole) {
            final double radius = classManager.getMageBlackHoleRadius();
            final double pullStrength = classManager.getMageBlackHolePullStrength();

            new org.bukkit.scheduler.BukkitRunnable() {
                @Override
                public void run() {
                    TNTPrimed currentTnt = null;
                    for (TNTPrimed t : player.getWorld().getEntitiesByClass(TNTPrimed.class)) {
                        if (t.hasMetadata("mage_blackhole")
                                && t.getMetadata("mage_owner_uuid").get(0).asString().equals(ownerUuid)) {
                            currentTnt = t;
                            break;
                        }
                    }

                    if (currentTnt == null || currentTnt.isDead()) {
                        return;
                    }

                    Location tntLoc = currentTnt.getLocation();

                    // 시각적 효과 (강화된 블랙홀 연출)
                    tntLoc.getWorld().spawnParticle(org.bukkit.Particle.PORTAL, tntLoc, 60, 1.0, 1.0, 1.0, 0.1);
                    tntLoc.getWorld().spawnParticle(org.bukkit.Particle.LARGE_SMOKE, tntLoc, 15, 0.5, 0.5, 0.5, 0.05);
                    tntLoc.getWorld().playSound(tntLoc, org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.5f);

                    for (org.bukkit.entity.Entity entity : currentTnt.getNearbyEntities(radius, radius, radius)) {
                        if (entity instanceof LivingEntity && entity != player) {
                            if (entity instanceof Player
                                    && ((Player) entity).getGameMode() == org.bukkit.GameMode.SPECTATOR)
                                continue;

                            Location entLoc = entity.getLocation();
                            double dist = entLoc.distance(tntLoc);
                            if (dist > 0.5 && dist <= radius) {
                                double power = pullStrength * (1.0 - (dist / radius));
                                Vector pull = tntLoc.toVector().subtract(entLoc.toVector()).normalize()
                                        .multiply(power);
                                entity.setVelocity(entity.getVelocity().add(pull));
                            }
                        }
                    }
                }
            }.runTaskLater(plugin, 10L); // 0.5초 후 한 번만 실행

            player.sendMessage(classManager.getMessage("prefix.mage", "&d[마법사] ")
                    + ChatColor.DARK_PURPLE + "블랙홀 익스플로전!");
        } else {
            player.sendMessage(classManager.getMessage("prefix.mage", "&d[마법사] ")
                    + classManager.getMessage("skill.mage_tnt", "&d익스플로전!"));
        }
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
                    + classManager.getMessage("skill.mage_teleport", "&d점멸!"));
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

    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        ItemStack toOffHand = event.getOffHandItem();
        ItemStack toMainHand = event.getMainHandItem();

        boolean involvesSpellbook = (toOffHand != null && toOffHand.getType() == Material.ENCHANTED_BOOK
                && classManager.isJobItem(toOffHand))
                || (toMainHand != null && toMainHand.getType() == Material.ENCHANTED_BOOK
                        && classManager.isJobItem(toMainHand));

        if (!involvesSpellbook)
            return;

        PlayerClass pClass = classManager.getPlayerClass(player);
        if (pClass != PlayerClass.MAGE)
            return;

        // 마법서는 왼손에 들 수 없음
        event.setCancelled(true);

        // F케이로 마법서를 왼손으로 스왓하려할 때만 폭주 발동
        if (toOffHand != null && toOffHand.getType() == Material.ENCHANTED_BOOK
                && classManager.isJobItem(toOffHand)) {
            trySurgeTrigger(player);
        }
    }

    /**
     * 원소 폭주 발동을 시도합니다. 쿨타임 체크 후 성공시 activateMageSurge 호출.
     */
    private void trySurgeTrigger(Player player) {
        int level = classManager.getPlayerClassLevel(player);
        if (!classManager.isMageSurge(level))
            return;

        long now = System.currentTimeMillis();
        long readyTime = surgeCooldowns.getOrDefault(player.getUniqueId(), 0L);
        double cdSeconds = classManager.getMageSurgeCooldownSeconds();

        if (now < readyTime) {
            double remaining = (readyTime - now) / 1000.0;
            player.sendMessage(ChatColor.RED + "원소 폭주가 아직 준비되지 않았습니다! ("
                    + String.format("%.1f", remaining) + "초)");
            return;
        }

        long newReadyTime = now + (long) (cdSeconds * 1000L);
        surgeCooldowns.put(player.getUniqueId(), newReadyTime);
        activateMageSurge(player);
    }

    private void activateMageSurge(Player player) {
        UUID uuid = player.getUniqueId();
        double duration = classManager.getMageSurgeDurationSeconds();
        double reduction = classManager.getMageSurgeCooldownReduction();
        double ratio = 1.0 - reduction;
        long now = System.currentTimeMillis();

        // ⚠️ 메타데이터 설정 전에 쿨타임 단축
        reduceCooldownMap(uuid, wandCooldowns, ratio, now);
        reduceCooldownMap(uuid, spellbookCooldowns, ratio, now);
        reduceCooldownMap(uuid, fireballCooldowns, ratio, now);
        reduceCooldownMap(uuid, frostJumpCooldowns, ratio, now);

        // 쿨타임 단쳙5 후 메타데이터 활성화 (getXxxCooldown이 이제 surge 감소된 값 반환)
        player.setMetadata("mage_surge_active", new FixedMetadataValue(plugin, true));

        int reductionPct = (int) (reduction * 100);
        player.sendMessage(classManager.getMessage("prefix.mage", "&d[마법사] ")
                + ChatColor.RED + "원소 폭주 발동! (" + (int) duration
                + "초간 쿨타임 " + reductionPct + "% 감소)");
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.5f);

        // 파티클 시각 효과 + 종료 시 2개의 Runnable을 하나로 통합
        new org.bukkit.scheduler.BukkitRunnable() {
            int ticks = 0;
            final int maxTicks = (int) (duration * 20);

            @Override
            public void run() {
                if (!player.isOnline()) {
                    player.removeMetadata("mage_surge_active", plugin);
                    cancel();
                    return;
                }

                if (ticks >= maxTicks) {
                    player.removeMetadata("mage_surge_active", plugin);
                    player.sendMessage(classManager.getMessage("prefix.mage", "&d[마법사] ")
                            + ChatColor.GRAY + "원소 폭주 상태가 해제되었습니다.");
                    cancel();
                    return;
                }

                Location loc = player.getLocation().add(0, 1, 0);
                loc.getWorld().spawnParticle(org.bukkit.Particle.DUST, loc, 15, 0.5, 0.5, 0.5, 0,
                        new org.bukkit.Particle.DustOptions(org.bukkit.Color.RED, 1.5f));
                loc.getWorld().spawnParticle(org.bukkit.Particle.FLAME, loc, 5, 0.3, 0.3, 0.3, 0.05);
                ticks += 5;
            }
        }.runTaskTimer(plugin, 0L, 5L);

        startActionBarCooldown(player, classManager.getMageSurgeCooldownSeconds(), "원소 폭주");
    }

    private void reduceCooldownMap(UUID uuid, Map<UUID, Long> map, double ratio, long now) {
        if (!map.containsKey(uuid))
            return;
        long readyTime = map.get(uuid);
        long remaining = readyTime - now;

        if (remaining > 0) {
            long newRemaining = (long) (remaining * ratio);
            map.put(uuid, now + newRemaining);
        }
    }

    @EventHandler
    public void onOffhandClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player))
            return;
        Player player = (Player) event.getWhoClicked();

        PlayerClass pClass = classManager.getPlayerClass(player);
        if (pClass != PlayerClass.MAGE)
            return;

        boolean isOffhandSlot = false;
        ItemStack item = null;

        if (event.getRawSlot() == 45 || (event.getSlot() == 40
                && event.getInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING)) {
            item = event.getCursor();
            isOffhandSlot = true;
        } else if (event.getAction() == InventoryAction.HOTBAR_SWAP
                || event.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD) {
            item = event.getCurrentItem();
            if (event.getRawSlot() == 45)
                isOffhandSlot = true;
        }

        if (isOffhandSlot && item != null && item.getType() == Material.ENCHANTED_BOOK
                && classManager.isJobItem(item)) {
            event.setCancelled(true);
            trySurgeTrigger(player);
        }
    }
}
