package com.example.randomclass;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.Arrays;
import java.util.Random;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

public class ClassManager {

    private final NamespacedKey jobItemKey;
    private final NamespacedKey ownerItemKey;
    private final NamespacedKey guiTargetClassKey;
    private final NamespacedKey tornTicketKey;
    private final NamespacedKey levelUpTicketKey;
    private final NamespacedKey HEALTH_MODIFIER_KEY;
    private final NamespacedKey ATTACK_MODIFIER_KEY;
    private final NamespacedKey STRENGTH_COMPENSATION_KEY;
    private final NamespacedKey WEAKNESS_COMPENSATION_KEY;
    private final NamespacedKey inventorySaveTicketKey;
    private final NamespacedKey awakeningTicketKey;
    private final Random random;
    private final Plugin plugin;

    private final File dataFile;
    private FileConfiguration dataConfig;
    private FileConfiguration mainConfig;

    public static class PlayerProfile {
        public PlayerClass playerClass = PlayerClass.NONE;
        public final Map<PlayerClass, Integer> levels = new java.util.EnumMap<>(PlayerClass.class);
        public final Map<String, Boolean> commonAwakenings = new HashMap<>();
        public final Map<String, Long> cooldowns = new HashMap<>();
        public int hungerResetTicks = 0;
        public String lastKnownName = "";
        public boolean dirty = false;
    }

    private final Map<UUID, PlayerProfile> profileCache = new HashMap<>();

    private final Map<UUID, Long> adventurerCombatCooldowns = new HashMap<>();
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> saturationTasks = new HashMap<>();

    // 용맹(Valor) 상태 추적
    private final Map<UUID, Long> valorAuraEndTimes = new HashMap<>();
    private final Map<UUID, Long> valorBuffEndTimes = new HashMap<>();
    private final Map<UUID, Long> valorBuffAuraEndTimes = new HashMap<>();

    private final File messagesFile;
    private FileConfiguration messagesConfig;
    private final Map<UUID, Boolean> statusDisplayEnabled = new HashMap<>();

    public boolean isStatusDisplayEnabled(UUID uuid) {
        return statusDisplayEnabled.getOrDefault(uuid, true);
    }

    public void setStatusDisplayEnabled(java.util.UUID uuid, boolean enabled) {
        statusDisplayEnabled.put(uuid, enabled);
    }

    public boolean toggleStatusDisplay(Player player) {
        java.util.UUID uuid = player.getUniqueId();
        boolean newVal = !isStatusDisplayEnabled(uuid);
        setStatusDisplayEnabled(uuid, newVal);
        return newVal;
    }

    public ClassManager(RandomClassPlugin plugin) {
        this.plugin = plugin;
        this.jobItemKey = new NamespacedKey(plugin, "is_job_item");
        this.ownerItemKey = new NamespacedKey(plugin, "owner_uuid");
        this.guiTargetClassKey = new NamespacedKey(plugin, "gui_target_class");
        this.tornTicketKey = new NamespacedKey(plugin, "torn_ticket");
        this.levelUpTicketKey = new NamespacedKey(plugin, "level_up_ticket");
        this.HEALTH_MODIFIER_KEY = new NamespacedKey(plugin, "class_health_bonus");
        this.ATTACK_MODIFIER_KEY = new NamespacedKey(plugin, "class_attack_bonus");
        this.STRENGTH_COMPENSATION_KEY = new NamespacedKey(plugin, "strength_compensation");
        this.WEAKNESS_COMPENSATION_KEY = new NamespacedKey(plugin, "weakness_compensation");
        this.inventorySaveTicketKey = new NamespacedKey(plugin, "inventory_save_ticket");
        this.awakeningTicketKey = new NamespacedKey(plugin, "awakening_ticket");
        this.random = new Random();

        // 메인 설정 로드
        this.mainConfig = plugin.getConfig();

        // 데이터 파일 초기화
        this.dataFile = new File(plugin.getDataFolder(), "players.yml");
        loadDataConfig();

        // 메시지 파일 초기화
        this.messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        loadMessagesConfig();

        // 접속 중인 플레이어 프로필 캐시 로드 (서버 리로드 대응)
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            loadPlayerProfile(p);
        }
    }

    public void reloadConfigs() {
        plugin.reloadConfig();
        this.mainConfig = plugin.getConfig();
        loadDataConfig();
        loadMessagesConfig();

        // 리로드 후 접속 중인 플레이어에게 버프/아이템 즉시 재적용
        for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            updatePlayerPrefix(p);
            applyClassBuffs(p);
        }
    }

    /**
     * 엔티티에게 정밀한 % 단위 둔화를 적용합니다.
     * 
     * @param entity     대상 엔티티
     * @param percentage 감소할 비율 (0.2 = 20% 감소)
     * @param ticks      지속 시간
     */
    public void applyCustomSlow(org.bukkit.entity.LivingEntity entity, double percentage, int ticks) {
        org.bukkit.attribute.AttributeInstance ai = entity.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
        if (ai == null)
            return;

        // 기존에 우리 플러그인이 걸었던 둔화가 있으면 제거 (중복 방지)
        org.bukkit.NamespacedKey slowKey = new org.bukkit.NamespacedKey(plugin, "random_class_slow");
        for (org.bukkit.attribute.AttributeModifier mod : ai.getModifiers()) {
            if (mod.getKey().equals(slowKey)) {
                ai.removeModifier(mod);
            }
        }

        // 새 둔화 적용 (ADD_SCALAR 방식: 1.0 + modifier)
        // 20% 감소시키려면 -0.2 를 더해야 함
        org.bukkit.attribute.AttributeModifier slowMod = new org.bukkit.attribute.AttributeModifier(
                slowKey,
                -percentage,
                org.bukkit.attribute.AttributeModifier.Operation.ADD_SCALAR,
                org.bukkit.inventory.EquipmentSlotGroup.ANY);

        ai.addModifier(slowMod);

        // 시각적 효과를 위해 바닐라 슬로우 포션 부여 (앰플리파이어 0 = Slowness I)
        entity.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, ticks, 0,
                false, false));

        // 지속 시간 후 제거
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                if (entity.isValid()) {
                    ai.removeModifier(slowMod);
                }
            }
        }.runTaskLater(plugin, ticks);
    }

    public NamespacedKey getOwnerItemKey() {
        return ownerItemKey;
    }

    public NamespacedKey getGuiTargetClassKey() {
        return guiTargetClassKey;
    }

    public NamespacedKey getTornTicketKey() {
        return tornTicketKey;
    }

    public NamespacedKey getLevelUpTicketKey() {
        return levelUpTicketKey;
    }

    public NamespacedKey getAwakeningTicketKey() {
        return awakeningTicketKey;
    }

    public NamespacedKey getInventorySaveTicketKey() {
        return inventorySaveTicketKey;
    }

    public void registerRecipes() {
        NamespacedKey recipeKey = new NamespacedKey(plugin, "level_up_ticket_recipe");
        // 이미 등록되어 있다면 무시하거나 제거할 수 있지만,
        // 1.16+ 부터는 RecipeChoice.ExactChoice를 사용하여 커스텀 NBT 아이템을 재료로 쓸 수 있습니다.
        try {
            // 중복 등록 방지를 위해 기존 레시피 제거
            plugin.getServer().removeRecipe(recipeKey);

            org.bukkit.inventory.ShapedRecipe recipe = new org.bukkit.inventory.ShapedRecipe(recipeKey,
                    createLevelUpTicket());
            recipe.shape("TTT", "TTT", "TTT");
            recipe.setIngredient('T', new org.bukkit.inventory.RecipeChoice.ExactChoice(createTornTicket()));
            plugin.getServer().addRecipe(recipe);

            // 각성권 레시피 추가 (레벨업 상승권 2개 나란히)
            NamespacedKey awakeningKey = new NamespacedKey(plugin, "awakening_ticket_recipe");
            plugin.getServer().removeRecipe(awakeningKey);
            org.bukkit.inventory.ShapedRecipe awkRecipe = new org.bukkit.inventory.ShapedRecipe(awakeningKey,
                    createAwakeningTicket());
            awkRecipe.shape("LL ");
            awkRecipe.setIngredient('L', new org.bukkit.inventory.RecipeChoice.ExactChoice(createLevelUpTicket()));
            plugin.getServer().addRecipe(awkRecipe);
        } catch (Exception e) {
            // RecipeChoice.ExactChoice가 지원되지 않는 하위 버전일 경우 대비
            plugin.getLogger().warning("커스텀 레시피 등록에 실패했습니다. (버전 호환성 문제일 수 있습니다)");
        }
    }

    private void loadMessagesConfig() {
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    private void loadDataConfig() {
        if (!dataFile.getParentFile().exists()) {
            dataFile.getParentFile().mkdirs();
        }
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    public void saveDataConfig() {
        if (!plugin.isEnabled()) {
            try {
                dataConfig.save(dataFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return;
        }

        try {
            // Serialize to string synchronously on the main thread (thread-safe)
            String dataStr = dataConfig.saveToString();
            
            // Write string to file asynchronously
            org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                synchronized (dataFile) {
                    try {
                        java.nio.file.Files.write(
                            dataFile.toPath(),
                            dataStr.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        );
                    } catch (IOException e) {
                        plugin.getLogger().severe("Failed to save player data asynchronously: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void reloadPlayerData() {
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        profileCache.clear();
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            loadPlayerProfile(player);
            updatePlayerPrefix(player);
            applyClassBuffs(player);
        }
    }

    public void loadPlayerProfile(Player player) {
        initializePlayerLevels(player);
        UUID uuid = player.getUniqueId();
        String uuidStr = uuid.toString();
        
        PlayerProfile profile = new PlayerProfile();
        profile.lastKnownName = player.getName();
        
        String className = dataConfig.getString(uuidStr + ".class", "NONE");
        try {
            profile.playerClass = PlayerClass.valueOf(className);
        } catch (IllegalArgumentException e) {
            profile.playerClass = PlayerClass.NONE;
        }
        
        for (PlayerClass pClass : PlayerClass.values()) {
            if (pClass != PlayerClass.NONE) {
                profile.levels.put(pClass, dataConfig.getInt(uuidStr + ".levels." + pClass.name(), 1));
            }
        }
        
        org.bukkit.configuration.ConfigurationSection awkSection = dataConfig.getConfigurationSection(uuidStr + ".common_awakenings");
        if (awkSection != null) {
            for (String key : awkSection.getKeys(false)) {
                profile.commonAwakenings.put(key, awkSection.getBoolean(key, false));
            }
        }
        
        org.bukkit.configuration.ConfigurationSection cdSection = dataConfig.getConfigurationSection(uuidStr + ".cooldowns");
        if (cdSection != null) {
            for (String key : cdSection.getKeys(false)) {
                profile.cooldowns.put(key, cdSection.getLong(key, 0L));
            }
        }
        
        profile.hungerResetTicks = dataConfig.getInt(uuidStr + ".hunger_reset_ticks", 0);
        profile.dirty = false;
        
        profileCache.put(uuid, profile);
    }

    private void saveProfileToConfig(UUID uuid, PlayerProfile profile) {
        String uuidStr = uuid.toString();
        dataConfig.set(uuidStr + ".class", profile.playerClass.name());
        dataConfig.set(uuidStr + ".last_known_name", profile.lastKnownName);
        for (Map.Entry<PlayerClass, Integer> entry : profile.levels.entrySet()) {
            dataConfig.set(uuidStr + ".levels." + entry.getKey().name(), entry.getValue());
        }
        for (Map.Entry<String, Boolean> entry : profile.commonAwakenings.entrySet()) {
            dataConfig.set(uuidStr + ".common_awakenings." + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Long> entry : profile.cooldowns.entrySet()) {
            dataConfig.set(uuidStr + ".cooldowns." + entry.getKey(), entry.getValue());
        }
        dataConfig.set(uuidStr + ".hunger_reset_ticks", profile.hungerResetTicks);
        profile.dirty = false;
    }

    public void saveAllProfiles() {
        boolean anyDirty = false;
        for (Map.Entry<UUID, PlayerProfile> entry : profileCache.entrySet()) {
            PlayerProfile profile = entry.getValue();
            if (profile.dirty) {
                saveProfileToConfig(entry.getKey(), profile);
                anyDirty = true;
            }
        }
        if (anyDirty) {
            saveDataConfig();
        }
    }

    public void initializePlayerLevels(Player player) {
        String uuid = player.getUniqueId().toString();
        boolean changed = false;
        PlayerClass[] classes = { PlayerClass.WARRIOR, PlayerClass.MAGE, PlayerClass.ADVENTURER };

        for (PlayerClass pClass : classes) {
            String levelPath = uuid + ".levels." + pClass.name();
            if (!dataConfig.contains(levelPath) || dataConfig.getInt(levelPath, 0) < 1) {
                dataConfig.set(levelPath, 1);
                changed = true;
            }
        }

        // 공통 각성 초기화
        String miningPath = uuid + ".common_awakenings.mining_2x2";
        if (!dataConfig.contains(miningPath)) {
            dataConfig.set(miningPath, false);
            changed = true;
        }

        if (changed) {
            dataConfig.set(uuid + ".last_known_name", player.getName());
            saveDataConfig();
        }
    }

    public PlayerClass getPlayerClass(Player player) {
        PlayerProfile profile = profileCache.get(player.getUniqueId());
        if (profile != null) {
            return profile.playerClass;
        }
        String uuid = player.getUniqueId().toString();
        String className = dataConfig.getString(uuid + ".class", "NONE");
        try {
            return PlayerClass.valueOf(className);
        } catch (IllegalArgumentException e) {
            return PlayerClass.NONE;
        }
    }

    public long getSkillCooldown(Player player, String skillName) {
        PlayerProfile profile = profileCache.get(player.getUniqueId());
        if (profile != null) {
            return profile.cooldowns.getOrDefault(skillName, 0L);
        }
        return dataConfig.getLong(player.getUniqueId().toString() + ".cooldowns." + skillName, 0L);
    }

    public void setSkillCooldown(Player player, String skillName, long timestamp) {
        String uuid = player.getUniqueId().toString();
        dataConfig.set(uuid + ".cooldowns." + skillName, timestamp);
        PlayerProfile profile = profileCache.get(player.getUniqueId());
        if (profile != null) {
            profile.cooldowns.put(skillName, timestamp);
            profile.dirty = true;
        }
        saveDataConfig();
    }

    public void setPlayerClass(Player player, PlayerClass playerClass) {
        String uuid = player.getUniqueId().toString();
        dataConfig.set(uuid + ".class", playerClass.name());
        if (playerClass != PlayerClass.NONE) {
            String levelPath = uuid + ".levels." + playerClass.name();
            if (dataConfig.getInt(levelPath, 0) < 1) {
                dataConfig.set(levelPath, 1);
            }
        }
        dataConfig.set(uuid + ".last_known_name", player.getName()); // 관리자 식별용
        
        PlayerProfile profile = profileCache.get(player.getUniqueId());
        if (profile != null) {
            profile.playerClass = playerClass;
            if (playerClass != PlayerClass.NONE) {
                profile.levels.putIfAbsent(playerClass, 1);
            }
            profile.lastKnownName = player.getName();
            profile.dirty = true;
        }
        
        saveDataConfig();
        updatePlayerPrefix(player);
    }

    public int getPlayerClassLevel(Player player) {
        return getStoredLevel(player, getPlayerClass(player));
    }

    /**
     * 플레이어가 더블 점프(비행)를 할 수 있는 직업과 레벨인지 확인합니다.
     * 현재 전사 8레벨 이상, 마법사 8레벨 이상이 대상입니다.
     */
    public boolean canDoubleJump(Player player) {
        PlayerClass pClass = getPlayerClass(player);
        int level = getPlayerClassLevel(player);

        if (pClass == PlayerClass.WARRIOR && level >= 8)
            return true;
        if (pClass == PlayerClass.MAGE && level >= 8)
            return true;

        return false;
    }

    public int getStoredLevel(Player player, PlayerClass targetClass) {
        if (targetClass == PlayerClass.NONE)
            return 0;
        PlayerProfile profile = profileCache.get(player.getUniqueId());
        if (profile != null) {
            return profile.levels.getOrDefault(targetClass, 1);
        }
        String uuid = player.getUniqueId().toString();
        return Math.max(1, dataConfig.getInt(uuid + ".levels." + targetClass.name(), 1));
    }

    public String getMessage(String key, String def) {
        if (messagesConfig == null)
            return ChatColor.translateAlternateColorCodes('&', def);
        String msg = messagesConfig.getString(key);
        if (msg == null) {
            // config.yml의 하위 호환성 유지
            msg = mainConfig.getString("messages." + key, def);
        }
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    public boolean isWarriorGeumgang(int level) {
        return level == 11 || level == 13;
    }

    public boolean isWarriorLifesteal(int level) {
        return level == 12 || level == 13;
    }

    public boolean isMageBlackHole(int level) {
        return level == 11 || level == 13;
    }

    public boolean isMageSurge(int level) {
        return level == 12 || level == 13;
    }

    public boolean isAdventurerValor(int level) {
        return level == 11 || level == 13;
    }

    public boolean isAdventurerSurvival(int level) {
        return level == 12 || level == 13;
    }

    public void setPlayerClassLevel(Player player, int level) {
        setStoredLevel(player, getPlayerClass(player), level);
    }

    public void setStoredLevel(Player player, PlayerClass targetClass, int level) {
        if (targetClass == PlayerClass.NONE)
            return;
        String uuid = player.getUniqueId().toString();
        dataConfig.set(uuid + ".levels." + targetClass.name(), Math.max(1, level));
        dataConfig.set(uuid + ".last_known_name", player.getName());
        
        PlayerProfile profile = profileCache.get(player.getUniqueId());
        if (profile != null) {
            profile.levels.put(targetClass, Math.max(1, level));
            profile.lastKnownName = player.getName();
            profile.dirty = true;
        }
        
        saveDataConfig();

        // 이제 마크 레벨은 건드리지 않고 접두어만 업데이트
        updatePlayerPrefix(player);
    }

    public void playLevelUpEffects(Player player, PlayerClass targetClass, int newLevel) {
        PlayerClass pClass = targetClass;

        // 1. 개인 채팅 메시지
        if (newLevel > 10) {
            int stage = 0;
            if (pClass == PlayerClass.WARRIOR) {
                if (isWarriorGeumgang(newLevel))
                    stage++;
                if (isWarriorLifesteal(newLevel))
                    stage++;
            } else if (pClass == PlayerClass.MAGE) {
                if (isMageBlackHole(newLevel))
                    stage++;
                if (isMageSurge(newLevel))
                    stage++;
            } else if (pClass == PlayerClass.ADVENTURER) {
                if (isAdventurerValor(newLevel))
                    stage++;
                if (isAdventurerSurvival(newLevel))
                    stage++;
            }

            org.bukkit.Bukkit.broadcastMessage(org.bukkit.ChatColor.AQUA + "========================================");
            org.bukkit.Bukkit.broadcastMessage(
                    org.bukkit.ChatColor.GOLD + "[경축] " + org.bukkit.ChatColor.WHITE + player.getName() +
                            org.bukkit.ChatColor.YELLOW + "님이 " + org.bukkit.ChatColor.GREEN + pClass.getDisplayName()
                            + " 각성 단계를 " +
                            org.bukkit.ChatColor.RED + stage + org.bukkit.ChatColor.YELLOW + "단계로 올렸습니다!");
            org.bukkit.Bukkit
                    .broadcastMessage(org.bukkit.ChatColor.AQUA + "========================================");
            for (int i = 0; i < 4; i++) {
                spawnFirework(player,
                        org.bukkit.Color.fromRGB(random.nextInt(256), random.nextInt(256), random.nextInt(256)));
            }
        } else {
            String msg = getMessage("job.upgrade_success", "&d레벨을 &f{level}&d(으)로 올렸습니다!");
            player.sendMessage(msg.replace("{level}", String.valueOf(newLevel)));
        }

        // 2. 폭죽 효과
        if (newLevel < 10) {
            spawnFirework(player, org.bukkit.Color.PURPLE);
        } else {
            for (int i = 0; i < 4; i++) {
                spawnFirework(player,
                        org.bukkit.Color.fromRGB(random.nextInt(256), random.nextInt(256), random.nextInt(256)));
            }

            // 3. 정확히 10레벨 달성 시에만 전체 공지
            if (newLevel == 10) {
                org.bukkit.Bukkit
                        .broadcastMessage(org.bukkit.ChatColor.AQUA + "========================================");
                org.bukkit.Bukkit.broadcastMessage(
                        org.bukkit.ChatColor.GOLD + "[경축] " + org.bukkit.ChatColor.WHITE + player.getName() +
                                org.bukkit.ChatColor.YELLOW + "님이 " + org.bukkit.ChatColor.GREEN + "10레벨" +
                                org.bukkit.ChatColor.YELLOW + "을 달성하여 " + org.bukkit.ChatColor.RED
                                + getJobTitle(pClass, 10) + org.bukkit.ChatColor.YELLOW + "가 되었습니다!");
                org.bukkit.Bukkit
                        .broadcastMessage(org.bukkit.ChatColor.AQUA + "========================================");
            }
        }
    }

    private void spawnFirework(Player player, org.bukkit.Color color) {
        org.bukkit.entity.Firework fw = (org.bukkit.entity.Firework) player.getWorld().spawnEntity(player.getLocation(),
                org.bukkit.entity.EntityType.FIREWORK_ROCKET);
        // 데미지 방지를 위한 메타데이터 추가
        fw.setMetadata("no_damage", new org.bukkit.metadata.FixedMetadataValue(plugin, true));

        org.bukkit.inventory.meta.FireworkMeta fwm = fw.getFireworkMeta();
        fwm.addEffect(org.bukkit.FireworkEffect.builder()
                .withColor(color, org.bukkit.Color.WHITE)
                .with(org.bukkit.FireworkEffect.Type.BALL_LARGE)
                .flicker(true)
                .trail(true)
                .build());
        fwm.setPower(0);
        fw.setFireworkMeta(fwm);
        fw.detonate();
    }

    public int getHungerBaseTicks(Player player) {
        PlayerProfile profile = profileCache.get(player.getUniqueId());
        if (profile != null) {
            return profile.hungerResetTicks;
        }
        String uuid = player.getUniqueId().toString();
        return dataConfig.getInt(uuid + ".hunger_reset_ticks", 0);
    }

    public void setHungerBaseTicks(Player player, int ticks) {
        String uuid = player.getUniqueId().toString();
        dataConfig.set(uuid + ".hunger_reset_ticks", ticks);
        
        PlayerProfile profile = profileCache.get(player.getUniqueId());
        if (profile != null) {
            profile.hungerResetTicks = ticks;
            profile.dirty = true;
        }
        
        saveDataConfig();
    }

    public boolean isAdventurerSaturationActive(Player player) {
        if (getPlayerClass(player) != PlayerClass.ADVENTURER || getPlayerClassLevel(player) < 10)
            return false;

        long now = System.currentTimeMillis();
        long lastHit = adventurerCombatCooldowns.getOrDefault(player.getUniqueId(), 0L);
        int cooldownSec = getAdventurerSaturationCooldown();

        return (now - lastHit >= cooldownSec * 1000L);
    }

    public void triggerAdventurerCombatCooldown(Player player) {
        if (getPlayerClass(player) != PlayerClass.ADVENTURER || getPlayerClassLevel(player) < 10)
            return;

        adventurerCombatCooldowns.put(player.getUniqueId(), System.currentTimeMillis());

        // 포션 효과 제거
        player.removePotionEffect(PotionEffectType.SATURATION);

        // 기존 재지급 작업이 있다면 취소
        if (saturationTasks.containsKey(player.getUniqueId())) {
            saturationTasks.get(player.getUniqueId()).cancel();
        }

        // 10초(설정값) 뒤에 포션 재지급하는 스케줄러 시작
        int cooldownSec = getAdventurerSaturationCooldown();
        org.bukkit.scheduler.BukkitTask task = new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && getPlayerClass(player) == PlayerClass.ADVENTURER
                        && getPlayerClassLevel(player) >= 10) {
                    player.addPotionEffect(
                            new PotionEffect(PotionEffectType.SATURATION, Integer.MAX_VALUE, 0, false, false));
                    player.sendMessage(getMessage("skill.adventurer_saturation_active", "&a[포화] &f능력이 다시 활성화되었습니다."));
                }
                saturationTasks.remove(player.getUniqueId());
            }
        }.runTaskLater(plugin, (long) (cooldownSec * 20L));

        saturationTasks.put(player.getUniqueId(), task);
    }

    public void cleanupPlayerData(Player player) {
        java.util.UUID uuid = player.getUniqueId();
        if (saturationTasks.containsKey(uuid)) {
            saturationTasks.get(uuid).cancel();
            saturationTasks.remove(uuid);
        }
        adventurerCombatCooldowns.remove(uuid);

        PlayerProfile profile = profileCache.remove(uuid);
        if (profile != null && profile.dirty) {
            saveProfileToConfig(uuid, profile);
            saveDataConfig();
        }

        // 스코어보드 팀 제거 (네임텍 초기화)
        org.bukkit.scoreboard.Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = "RC_" + player.getName();
        if (teamName.length() > 16)
            teamName = teamName.substring(0, 16);
        org.bukkit.scoreboard.Team team = sb.getTeam(teamName);
        if (team != null) {
            team.unregister();
        }
    }

    public boolean isHungerSystemEnabled() {
        return mainConfig.getBoolean("hunger.enabled", true);
    }

    public double getHungerMultiplier(Player player) {
        if (!mainConfig.getBoolean("hunger.enabled", true)) {
            return 1.0;
        }
        int playTicks = player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE);
        int relativeTicks = playTicks - getHungerBaseTicks(player);
        if (relativeTicks < 0)
            relativeTicks = 0;

        // config에서 도달 시간(hours)을 가져와 틱으로 변환
        double hours = mainConfig.getDouble("hunger.hours_to_max", 5.0);
        double maxTicks = hours * 60 * 60 * 20;

        double progress = (maxTicks > 0) ? Math.min(relativeTicks, maxTicks) / maxTicks : 1.0;
        double maxMultiplier = mainConfig.getDouble("hunger.max_multiplier", 1.5);

        return 1.0 + ((maxMultiplier - 1.0) * progress);
    }

    public int getHungerResetCost() {
        return mainConfig.getInt("hunger.reset_cost", 5);
    }

    public int getJobChangeCost() {
        return mainConfig.getInt("costs.job_change", 5);
    }

    public int getAwakeningLevelCost() {
        return mainConfig.getInt("costs.awakening_level_cost", 30);
    }

    public int getUpgradeCost(int currentLevel) {
        java.util.List<Integer> costs = mainConfig.getIntegerList("costs.upgrade");
        if (costs.isEmpty() || currentLevel < 1 || currentLevel >= 10)
            return 0;
        return costs.get(currentLevel - 1);
    }

    public double getMageBlackHoleDamageMultiplier() {
        return mainConfig.getDouble("abilities.mage.awakening.blackhole_damage_multiplier", 1.2);
    }

    public double getMageBlackHoleRadius() {
        return mainConfig.getDouble("abilities.mage.awakening.blackhole_radius", 10.0);
    }

    public double getMageBlackHoleCooldownIncrease() {
        return mainConfig.getDouble("abilities.mage.awakening.blackhole_cooldown_increase_seconds", 5.0);
    }

    public double getMageBlackHolePullStrength() {
        return mainConfig.getDouble("abilities.mage.awakening.blackhole_pull_strength", 0.5);
    }

    public double getMageSurgeDurationSeconds() {
        return mainConfig.getDouble("abilities.mage.awakening.surge_duration_seconds", 10.0);
    }

    public double getMageSurgeCooldownReduction() {
        return mainConfig.getDouble("abilities.mage.awakening.surge_cooldown_reduction", 0.8);
    }

    public double getMageSurgeCooldownSeconds() {
        return mainConfig.getDouble("abilities.mage.awakening.surge_cooldown_seconds", 120.0);
    }

    public double getMageTntMultiplier(int level) {
        java.util.List<Double> multipliers = mainConfig.getDoubleList("cooldowns.mage_tnt_damage_levels");
        if (multipliers.isEmpty())
            return 0.33;
        int index = Math.max(0, Math.min(level - 1, multipliers.size() - 1));
        return multipliers.get(index);
    }

    public double getFarmerExpMultiplier() {
        return mainConfig.getDouble("exp.adventurer_multiplier_per_level", 0.1);
    }

    // --- 신규 설정 Getter 메서드들 ---

    public double getLevelUpTicketChance() {
        return mainConfig.getDouble("drops.level_up_ticket_chance", 0.01);
    }

    public double getTornTicketChance() {
        return mainConfig.getDouble("drops.torn_ticket_chance", 0.1);
    }

    public double getInventorySaveChance() {
        return mainConfig.getDouble("drops.inventory_save", 0.03);
    }

    public boolean isAnnounceTicketDropsEnabled() {
        return mainConfig.getBoolean("drops.announce_ticket_drops", true);
    }

    public String getInitialRewardItem() {
        return mainConfig.getString("initial_reward.item", "STEAK");
    }

    public int getInitialRewardAmount() {
        return mainConfig.getInt("initial_reward.amount", 10);
    }

    public double getMageTeleportDistance() {
        return mainConfig.getDouble("abilities.mage.teleport_distance", 10.0);
    }

    public int getMageTntFuseTicks() {
        return mainConfig.getInt("abilities.mage.tnt_fuse_ticks", 40);
    }

    public double getMageWandCooldown() {
        return mainConfig.getDouble("cooldowns.mage_wand", 15.0);
    }

    public double getMageSpellbookCooldown() {
        return mainConfig.getDouble("cooldowns.mage_spellbook", 30.0);
    }

    public double getMageFireballCooldown() {
        return mainConfig.getDouble("cooldowns.mage_fireball", 12.0);
    }

    public double getMageFrostJumpCooldown() {
        return mainConfig.getDouble("cooldowns.mage_frost_jump", 20.0);
    }

    public double getMageFireballDamage(int level) {
        java.util.List<Double> damages = mainConfig.getDoubleList("abilities.mage.fireball_damage_levels");
        if (damages == null || damages.isEmpty()) {
            return 13.0; // 기본값
        }
        int index = Math.max(0, Math.min(level - 1, damages.size() - 1));
        return damages.get(index);
    }

    public int getMageFireballFlameDurationSeconds() {
        return mainConfig.getInt("abilities.mage.fireball_flame_duration_seconds", 4);
    }

    public double getMageFireballFlameRadius() {
        return mainConfig.getDouble("abilities.mage.fireball_flame_radius", 2.5);
    }

    public double getMageFireballExplosionRadius() {
        return mainConfig.getDouble("abilities.mage.fireball_explosion_radius", 3.5);
    }

    public double getMageFireballFlameDamage() {
        return mainConfig.getDouble("abilities.mage.fireball_flame_damage", 1.0);
    }

    public int getMageFrostJumpRadius() {
        return mainConfig.getInt("abilities.mage.frost_jump_radius", 5);
    }

    public int getMageFrostJumpDurationSeconds() {
        return mainConfig.getInt("abilities.mage.frost_jump_duration_seconds", 8);
    }

    public int getMageFrostJumpSlowDurationTicks() {
        return mainConfig.getInt("abilities.mage.frost_jump_slow_duration_ticks", 60);
    }

    public double getMageFrostJumpPower() {
        return mainConfig.getDouble("abilities.mage.frost_jump_power", 0.4);
    }

    public double getMageFrostJumpSlowPercentage() {
        return mainConfig.getDouble("abilities.mage.frost_jump_slow_percentage", 0.3);
    }

    public double getMageCooldownMultiplier(int level) {
        java.util.List<Double> reductions = mainConfig.getDoubleList("abilities.mage.cooldown_reduction_levels");
        if (reductions.isEmpty())
            return 1.0;
        int index = Math.max(0, Math.min(level - 1, reductions.size() - 1));
        return reductions.get(index);
    }

    public double getWarriorShieldCooldown() {
        return mainConfig.getDouble("cooldowns.warrior_shield", 15.0);
    }

    public double getWarriorGeumgangReflect() {
        return mainConfig.getDouble("abilities.warrior.geumgang_reflect_percent", 0.25);
    }

    public double getWarriorGeumgangCooldown() {
        return mainConfig.getDouble("abilities.warrior.geumgang_cooldown", 40.0);
    }

    public int getWarriorGeumgangDurationSeconds() {
        return mainConfig.getInt("abilities.warrior.geumgang_duration_seconds", 5);
    }

    public double getWarriorLifestealPercent() {
        return mainConfig.getDouble("abilities.warrior.lifesteal_percent", 0.5);
    }

    public int getWarriorLifestealDurationSeconds() {
        return mainConfig.getInt("abilities.warrior.lifesteal_duration_seconds", 5);
    }

    public double getWarriorDoubleJumpCooldown() {
        return mainConfig.getDouble("abilities.warrior.double_jump.cooldown_seconds", 6.0);
    }

    public double getWarriorDoubleJumpower() {
        return mainConfig.getDouble("abilities.warrior.jump_multiplier", 0.7);
    }

    public double getWarriorArrowStormDamage(int level) {
        java.util.List<Double> damages = mainConfig.getDoubleList("abilities.warrior.arrow_storm.damage_levels");
        if (damages.isEmpty())
            return 100.0;
        int index = Math.max(0, Math.min(level - 1, damages.size() - 1));
        return damages.get(index);
    }

    public int getWarriorArrowStormCount() {
        return mainConfig.getInt("abilities.warrior.arrow_storm.arrow_count", 12);
    }

    public double getWarriorArrowStormCooldown(int level) {
        java.util.List<Double> cooldowns = mainConfig.getDoubleList("abilities.warrior.arrow_storm.cooldown_levels");
        if (cooldowns == null || cooldowns.isEmpty()) {
            return 20.0;
        }
        int index = Math.max(0, Math.min(level - 1, cooldowns.size() - 1));
        return cooldowns.get(index);
    }

    public int getWarriorSlowDuration() {
        return mainConfig.getInt("abilities.warrior.slow_duration_ticks", 20);
    }

    public double getWarriorSlowPercentage(int level) {
        java.util.List<Double> list = mainConfig.getDoubleList("abilities.warrior.slow_percentage_levels");
        if (list.isEmpty())
            return 0.15;
        int index = Math.max(0, Math.min(level - 1, list.size() - 1));
        return list.get(index);
    }

    public double getWarriorDashMultiplier() {
        return mainConfig.getDouble("abilities.warrior.dash_multiplier", 0.6);
    }

    public double getWarriorDamageMultiplier(int level) {
        java.util.List<Double> multipliers = mainConfig.getDoubleList("abilities.warrior.damage_multipliers");
        if (multipliers.isEmpty())
            return (level >= 10) ? 0.3 : (level >= 6) ? 0.2 : 0.1;
        int index = Math.max(0, Math.min(level - 1, multipliers.size() - 1));
        return multipliers.get(index);
    }

    public int getAdventurerHarvestExpMin() {
        return mainConfig.getInt("abilities.adventurer.harvest_exp_min", 1);
    }

    public int getAdventurerHarvestExpMax() {
        return mainConfig.getInt("abilities.adventurer.harvest_exp_max", 3);
    }

    public double getAdventurerAnimalExpMultiplier() {
        return mainConfig.getDouble("abilities.adventurer.animal_exp_multiplier", 1.5);
    }

    // --- 용맹 (Valor) 설정 Getter ---
    public double getAdventurerValorCooldown() {
        return mainConfig.getDouble("abilities.adventurer.awakening.valor_cooldown_seconds", 120.0);
    }

    public double getAdventurerValorDuration() {
        return mainConfig.getDouble("abilities.adventurer.awakening.valor_duration_seconds", 10.0);
    }

    public double getAdventurerValorRadius() {
        return mainConfig.getDouble("abilities.adventurer.awakening.valor_radius", 10.0);
    }

    public double getAdventurerValorDamageMultiplier() {
        return mainConfig.getDouble("abilities.adventurer.awakening.valor_damage_multiplier", 1.2);
    }

    public double getAdventurerValorLifestealPercent() {
        return mainConfig.getDouble("abilities.adventurer.awakening.valor_lifesteal_percent", 0.05);
    }

    public int getAdventurerSurvivalBuffDuration() {
        return mainConfig.getInt("abilities.adventurer.awakening.survival_buff_duration_sec", 5);
    }

    public int getAdventurerSurvivalCooldownIncrease() {
        return mainConfig.getInt("abilities.adventurer.awakening.survival_cooldown_increase_sec", 15);
    }

    // --- 용맹 (Valor) 상태 관리 ---
    public void setValorAuraEndTime(Player player, long endTimeMillis) {
        valorAuraEndTimes.put(player.getUniqueId(), endTimeMillis);
    }

    public long getValorAuraEndTime(Player player) {
        return valorAuraEndTimes.getOrDefault(player.getUniqueId(), 0L);
    }

    public void setValorBuffEndTime(Player player, long endTimeMillis) {
        valorBuffEndTimes.put(player.getUniqueId(), endTimeMillis);
    }

    public long getValorBuffEndTime(Player player) {
        return valorBuffEndTimes.getOrDefault(player.getUniqueId(), 0L);
    }

    public void setValorBuffAuraEndTime(Player player, long endTimeMillis) {
        valorBuffAuraEndTimes.put(player.getUniqueId(), endTimeMillis);
    }

    public long getValorBuffAuraEndTime(Player player) {
        return valorBuffAuraEndTimes.getOrDefault(player.getUniqueId(), 0L);
    }

    public boolean hasValorBuff(Player player) {
        return System.currentTimeMillis() < getValorBuffEndTime(player);
    }

    public int getAdventurerSaturationCooldown() {
        return mainConfig.getInt("abilities.adventurer.saturation_cooldown", 25);
    }

    public boolean isAdventurerSpeedOnHitEnabled() {
        return mainConfig.getBoolean("abilities.adventurer.speed_on_hit.enabled", true);
    }

    public int getAdventurerSpeedOnHitDurationTicks() {
        return mainConfig.getInt("abilities.adventurer.speed_on_hit.duration_seconds", 10) * 20;
    }

    public int getAdventurerSpeedOnHitAmplifier() {
        return mainConfig.getInt("abilities.adventurer.speed_on_hit.amplifier", 2);
    }

    public double getDropMultiplier() {
        return mainConfig.getDouble("drops.animal_drop_multiplier", 0.5);
    }

    private int getLevelBasedInt(String path, int level, int fallback) {
        java.util.List<Integer> values = mainConfig.getIntegerList(path);
        if (values.isEmpty())
            return fallback;
        int index = Math.max(0, Math.min(level - 1, values.size() - 1));
        return values.get(index);
    }

    private double getLevelBasedDouble(String path, int level, double fallback) {
        java.util.List<Double> values = mainConfig.getDoubleList(path);
        if (values.isEmpty()) {
            // IntegerList로 다시 확인 (하위 호환성)
            java.util.List<Integer> intValues = mainConfig.getIntegerList(path);
            if (intValues.isEmpty())
                return fallback;
            int index = Math.max(0, Math.min(level - 1, intValues.size() - 1));
            return intValues.get(index).doubleValue();
        }
        int index = Math.max(0, Math.min(level - 1, values.size() - 1));
        return values.get(index);
    }

    public int getAdventurerRegenFieldDurationSeconds(int level) {
        return getLevelBasedInt("abilities.adventurer.regen_field.duration_seconds_levels", level, 6);
    }

    public int getAdventurerRegenFieldAmplifier(int level) {
        return getLevelBasedInt("abilities.adventurer.regen_field.amplifier_levels", level, 0);
    }

    public double getAdventurerRegenFieldCooldownSeconds(int level) {
        return getLevelBasedDouble("abilities.adventurer.regen_field.cooldown_seconds_levels", level, 30.0);
    }

    public double getAdventurerRegenFieldRadius(int level) {
        java.util.List<Double> radii = mainConfig.getDoubleList("abilities.adventurer.regen_field.radius_levels");
        if (radii.isEmpty())
            return 4.0;
        int index = Math.max(0, Math.min(level - 1, radii.size() - 1));
        return radii.get(index);
    }

    public int getHighestClassLevel(Player player) {
        int maxLevel = 1;
        for (PlayerClass pClass : PlayerClass.values()) {
            if (pClass == PlayerClass.NONE)
                continue;
            int lvl = getStoredLevel(player, pClass);
            if (lvl > maxLevel)
                maxLevel = lvl;
        }
        return maxLevel;
    }

    public PlayerClass assignRandomClass(Player player) {
        PlayerClass[] classes = { PlayerClass.WARRIOR, PlayerClass.MAGE, PlayerClass.ADVENTURER };
        PlayerClass assigned = classes[random.nextInt(classes.length)];
        setPlayerClass(player, assigned);
        setPlayerClassLevel(player, 1);
        // [보상] 처음 직업을 얻었을 때 초기 보상 지급 (config 설정 기반)
        try {
            org.bukkit.Material rewardMat = org.bukkit.Material.valueOf(getInitialRewardItem());
            int amount = getInitialRewardAmount();
            if (amount > 0) {
                player.getInventory().addItem(new org.bukkit.inventory.ItemStack(rewardMat, amount));
            }
        } catch (Exception e) {
            // 설정 오류 시 기본 스테이크 지급
            player.getInventory().addItem(new org.bukkit.inventory.ItemStack(org.bukkit.Material.COOKED_BEEF, 10));
        }

        return assigned;
    }

    public String getPlayerPrefix(Player player) {
        PlayerClass pClass = getPlayerClass(player);
        if (pClass == PlayerClass.NONE)
            return "";

        int level = getPlayerClassLevel(player);

        // 등급별 색상 (직업에 무관하게 등급으로 결정)
        ChatColor color;
        if (level >= 10)
            color = ChatColor.RED; // 마스터: 빨강
        else if (level >= 7)
            color = ChatColor.YELLOW; // 전문/베테랑: 노랑
        else if (level >= 4)
            color = ChatColor.BLUE; // 숙련된: 파랑
        else
            color = ChatColor.GREEN; // 견습/초보: 초록

        String title = getJobTitle(pClass, level);
        return color + "[" + title + "] " + ChatColor.RESET;
    }

    public void updatePlayerPrefix(Player player) {
        updatePlayerPrefix(player, getPlayerClass(player), getPlayerClassLevel(player));
    }

    private void updatePlayerPrefix(Player player, PlayerClass pClass, int level) {
        if (pClass == PlayerClass.NONE) {
            player.setDisplayName(player.getName());
            player.setPlayerListName(player.getName());

            // 네임텍 초기화 (팀 제거)
            org.bukkit.scoreboard.Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
            String teamName = "RC_" + player.getName();
            if (teamName.length() > 16)
                teamName = teamName.substring(0, 16);
            org.bukkit.scoreboard.Team team = sb.getTeam(teamName);
            if (team != null)
                team.unregister();
            return;
        }

        String prefix = getPlayerPrefix(player);

        // 1. 채팅용 (DisplayName)
        player.setDisplayName(prefix + player.getName());

        // 2. 탭 리스트 및 이름표 (Scoreboard Team)
        updateScoreboardTeam(player, prefix);
    }

    private void updateScoreboardTeam(Player player, String prefix) {
        org.bukkit.scoreboard.Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = "RC_" + player.getName();
        if (teamName.length() > 16)
            teamName = teamName.substring(0, 16);

        org.bukkit.scoreboard.Team team = sb.getTeam(teamName);
        if (team == null) {
            team = sb.registerNewTeam(teamName);
        }

        team.setPrefix(prefix);
        team.setOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY,
                org.bukkit.scoreboard.Team.OptionStatus.ALWAYS);
        if (!team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }

        // 탭 리스트 이름도 동기화
        player.setPlayerListName(prefix + player.getName());
    }

    private String getJobTitle(PlayerClass pClass, int level) {
        String base = "";
        switch (pClass) {
            case WARRIOR:
                base = "전사";
                break;
            case MAGE:
                base = "마법사";
                break;
            case ADVENTURER:
                base = "모험가";
                break;
            default:
                return "";
        }

        if (level >= 10)
            return "마스터 " + base;
        if (level >= 7)
            return (pClass == PlayerClass.WARRIOR ? "베테랑 " : "전문 ") + base;
        if (level >= 4)
            return "숙련된 " + base;
        return (pClass == PlayerClass.WARRIOR ? "견습 " : "초보 ") + base;
    }

    public void syncJobItem(Player player, PlayerClass pClass) {
        // 크리에이티브 모드에서는 아이템 동기화 건너뜀 (테스트 및 건축 편의성)
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE)
            return;

        // 왼손에 어떤 직업 아이템이 있었는지 재질 기억
        ItemStack oldOffHand = player.getInventory().getItemInOffHand();
        Material offHandTarget = isJobItem(oldOffHand) ? oldOffHand.getType() : null;

        // 기존 직업 아이템 모두 제거 (모든 슬롯)
        removeOldJobItems(player);

        if (pClass == PlayerClass.NONE)
            return;

        int level = getStoredLevel(player, pClass);

        // 새로운 직업 아이템 지급
        if (pClass == PlayerClass.ADVENTURER) {
            double teleportCooldown = mainConfig.getDouble("cooldowns.adventurer_teleport", 180.0);
            safeGiveItem(player, createJobItem(player, Material.DIAMOND_HOE, ChatColor.GREEN + "모험가의 괭이", level),
                    offHandTarget);
            safeGiveItem(player, createJobItem(player, Material.DIAMOND_SHOVEL, ChatColor.GREEN + "모험가의 삽", level),
                    offHandTarget);
            ItemStack axe = createJobItem(player, Material.DIAMOND_AXE, ChatColor.GREEN + "모험가의 도끼", level);
            if (level >= 10) {
                org.bukkit.inventory.meta.ItemMeta axeMeta = axe.getItemMeta();
                java.util.List<String> lore = new java.util.ArrayList<>(axeMeta.getLore());
                String cdText = (teleportCooldown == (int) teleportCooldown) ? String.valueOf((int) teleportCooldown)
                        : String.format("%.1f", teleportCooldown);
                lore.add(1, ChatColor.GOLD + "[패시브] " + ChatColor.WHITE + "죽음의 위기 시 스폰 지점 이동 (쿨타임 " + cdText
                        + "초)");
                axeMeta.setLore(lore);
                axe.setItemMeta(axeMeta);
            }
            safeGiveItem(player, axe, offHandTarget);

            int regenDuration = getAdventurerRegenFieldDurationSeconds(level);
            int regenAmp = getAdventurerRegenFieldAmplifier(level);
            double regenCooldown = getAdventurerRegenFieldCooldownSeconds(level);
            ItemStack regenPotion = createJobItem(player, Material.POTION, ChatColor.GREEN + "모험가의 재생 포션", level);
            if(level == 13){
                regenPotion = createJobItem(player, Material.POTION, ChatColor.GREEN + "모험가의 각성된 재생 포션", level);
            }else if(level == 11){
                regenPotion = createJobItem(player, Material.POTION, ChatColor.GREEN + "모험가의 용감한 재생 포션", level);
            }else if(level == 12){
                regenPotion = createJobItem(player, Material.POTION, ChatColor.GREEN + "모험가의 강화된 재생 포션", level);
            }
            
            org.bukkit.inventory.meta.ItemMeta potionMeta = regenPotion.getItemMeta();
            if (potionMeta instanceof org.bukkit.inventory.meta.PotionMeta) {
                ((org.bukkit.inventory.meta.PotionMeta) potionMeta).setColor(org.bukkit.Color.RED);
            }
            java.util.List<String> potionLore = new java.util.ArrayList<>(potionMeta.getLore());
            String cdText = (regenCooldown == (int) regenCooldown) ? String.valueOf((int) regenCooldown)
                    : String.format("%.1f", regenCooldown);
            potionLore.add(0, ChatColor.GOLD + "[우클릭] " + ChatColor.WHITE + "재생 필드 전개 (쿨타임 " + cdText + "초)");

            if (isAdventurerSurvival(level)) {
                // 강화된 파동: 설명 변경
                int survivalDur = getAdventurerSurvivalBuffDuration();
                int survivalCdInc = getAdventurerSurvivalCooldownIncrease();
                potionLore.add(1, ChatColor.AQUA + "[각성] " + ChatColor.WHITE + "강화된 파동 (" + regenDuration + "초)");
                potionLore.add(2, ChatColor.GRAY + "파동 내 모든 디버프 해제 + 이로운 효과 " + survivalDur + "초");
                potionLore.add(3, ChatColor.RED + "(쿨타임 +" + survivalCdInc + "초 패널티)");
            } else {
                potionLore.add(1,
                        ChatColor.GRAY + "효과: 반경 " + String.format("%.1f", getAdventurerRegenFieldRadius(level))
                                + "블록, "
                                + regenDuration + "초, 재생 " + (regenAmp + 1));
            }

            if (isAdventurerValor(level)) {
                double valorCd = getAdventurerValorCooldown();
                double valorDur = getAdventurerValorDuration();
                double valorDmg = getAdventurerValorDamageMultiplier();
                int valorLifesteal = (int) (getAdventurerValorLifestealPercent() * 100);

                potionLore.add("");
                potionLore
                        .add(ChatColor.GOLD + "[각성: 용맹] " + ChatColor.WHITE + "F키를 눌러 발동 (쿨타임 " + (int) valorCd + "초)");
                potionLore.add(
                        ChatColor.GRAY + "효과: " + (int) valorDur + "초간 반경 10m내 아군 데미지 +" + (int) (valorDmg * 100 - 100)
                                + "% 및 피해 흡혈 " + valorLifesteal + "%");

                // 각성 아이템임을 시각적으로 구분 (인챈트 반짝임, 실제 효과 없음)
                potionMeta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                potionMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }

            potionMeta.setLore(potionLore);
            regenPotion.setItemMeta(potionMeta);
            safeGiveItem(player, regenPotion, offHandTarget);
        } else if (pClass == PlayerClass.MAGE) {
            double wandCooldown = getMageWandCooldown();
            double spellCooldown = getMageSpellbookCooldown();
            double fireballCooldown = getMageFireballCooldown();

            ItemStack rod = createJobItem(player, pClass, level);
            org.bukkit.inventory.meta.ItemMeta rodMeta = rod.getItemMeta();
            java.util.List<String> rodLore = new java.util.ArrayList<>(rodMeta.getLore());
            String wandCdText = (wandCooldown == (int) wandCooldown) ? String.valueOf((int) wandCooldown)
                    : String.format("%.1f", wandCooldown);
            rodLore.add(0, ChatColor.GOLD + "[우클릭] " + ChatColor.WHITE + "마법 TNT 투척 (쿨타임 " + wandCdText + "초)");
            rodMeta.setLore(rodLore);
            rod.setItemMeta(rodMeta);
            safeGiveItem(player, rod, offHandTarget);

            ItemStack spellbook = new ItemStack(Material.ENCHANTED_BOOK);
            org.bukkit.inventory.meta.ItemMeta bookMeta = spellbook.getItemMeta();
            bookMeta.setDisplayName(ChatColor.LIGHT_PURPLE + "균열의 서 (Lv." + Math.min(level, 10) + ")");
            if(level == 12 || level == 13){
                bookMeta.setDisplayName(ChatColor.LIGHT_PURPLE + "각성된 균열의 서 (Lv." + Math.min(level, 10) + ")");
            }
            String spellCdText = (spellCooldown == (int) spellCooldown) ? String.valueOf((int) spellCooldown)
                    : String.format("%.1f", spellCooldown);
            bookMeta.setLore(Arrays.asList(
                    ChatColor.GOLD + "[우클릭] " + ChatColor.WHITE + "전방 공간 도약 (쿨타임 " + spellCdText + "초)",
                    ChatColor.GRAY + "이 아이템은 귀속되어 버릴 수 없습니다.",
                    ChatColor.GRAY + "추가 인챈트가 불가능합니다.",
                    ChatColor.DARK_GRAY + "[직업 전용 아이템]"));
            bookMeta.setUnbreakable(true);
            bookMeta.getPersistentDataContainer().set(jobItemKey, PersistentDataType.BYTE, (byte) 1);
            bookMeta.getPersistentDataContainer().set(ownerItemKey, PersistentDataType.STRING,
                    player.getUniqueId().toString());
            spellbook.setItemMeta(bookMeta);
            safeGiveItem(player, spellbook, offHandTarget);

            if (level >= 5) {
                ItemStack fireball = createJobItem(player, Material.FIRE_CHARGE, ChatColor.GOLD + "마법사의 화염구", level);
                org.bukkit.inventory.meta.ItemMeta fireballMeta = fireball.getItemMeta();
                if (fireballMeta != null) {
                    java.util.List<String> fireballLore = new java.util.ArrayList<>(fireballMeta.getLore());
                    String fireballCdText = (fireballCooldown == (int) fireballCooldown)
                            ? String.valueOf((int) fireballCooldown)
                            : String.format("%.1f", fireballCooldown);
                    fireballLore.add(0,
                            ChatColor.GOLD + "[우클릭] " + ChatColor.WHITE + "화염구 발사 (쿨타임 " + fireballCdText + "초)");
                    fireballLore.add(1, ChatColor.GRAY + "블록은 태우거나 파괴하지 않고 생명체에게만 피해를 줍니다.");
                    fireballMeta.setLore(fireballLore);
                    fireball.setItemMeta(fireballMeta);
                }
                safeGiveItem(player, fireball, offHandTarget);
            }
        } else if (pClass == PlayerClass.WARRIOR) {
            safeGiveItem(player, createJobItem(player, pClass, level), offHandTarget);
            if (level >= 6) {
                ItemStack shield = createJobItem(player, Material.SHIELD, ChatColor.RED + "전사의 방패", level);
                org.bukkit.inventory.meta.ItemMeta shieldMeta = shield.getItemMeta();
                if (shieldMeta != null) {
                    java.util.List<String> shieldLore = new java.util.ArrayList<>(shieldMeta.getLore());
                    shieldLore.add(0, ChatColor.GOLD + "[왼손 착용] " + ChatColor.WHITE + "우클릭 방어 후 좌클릭: 방패 돌진");
                    shieldLore.add(1, ChatColor.GRAY + "적중 시 0.5초 기절 + 1초 암흑");
                    shieldMeta.setLore(shieldLore);
                    shield.setItemMeta(shieldMeta);
                }
                safeGiveItem(player, shield, offHandTarget);
            }
            // 3레벨 이상일 때 스킬 아이템 지급
            if (level >= 3) {
                if (isWarriorGeumgang(level)) {
                    // 각성 전사: 금강불괴 (종)
                    double geumgangCd = getWarriorGeumgangCooldown();
                    ItemStack geumgangBell = createJobItem(player, Material.BELL, ChatColor.GOLD + "금강불괴", level);
                    org.bukkit.inventory.meta.ItemMeta bellMeta = geumgangBell.getItemMeta();
                    if (bellMeta != null) {
                        java.util.List<String> bellLore = new java.util.ArrayList<>(bellMeta.getLore());
                        String cdText = (geumgangCd == (int) geumgangCd) ? String.valueOf((int) geumgangCd)
                                : String.format("%.1f", geumgangCd);
                        bellLore.add(0, ChatColor.GOLD + "[우클릭] " + ChatColor.WHITE + "금강불괴 발동 (쿨타임 " + cdText + "초)");
                        bellLore.add(1, ChatColor.GRAY + "5초간 무적 및 피해 반사");
                        bellMeta.setLore(bellLore);
                        geumgangBell.setItemMeta(bellMeta);
                    }
                    safeGiveItem(player, geumgangBell, offHandTarget);
                } else {
                    // 일반 전사 또는 피흡 각성 전사: 애로우 스톰 (화살)
                    double stormCd = getWarriorArrowStormCooldown(level);
                    ItemStack stormArrow = createJobItem(player, Material.ARROW, ChatColor.GOLD + "애로우 스톰", level);
                    org.bukkit.inventory.meta.ItemMeta arrowMeta = stormArrow.getItemMeta();
                    if (arrowMeta != null) {
                        java.util.List<String> arrowLore = new java.util.ArrayList<>(arrowMeta.getLore());
                        String stormCdText = (stormCd == (int) stormCd) ? String.valueOf((int) stormCd)
                                : String.format("%.1f", stormCd);
                        arrowLore.add(0,
                                ChatColor.GOLD + "[우클릭] " + ChatColor.WHITE + "애로우 스톰 발동 (쿨타임 " + stormCdText + "초)");
                        arrowMeta.setLore(arrowLore);
                        stormArrow.setItemMeta(arrowMeta);
                    }
                    safeGiveItem(player, stormArrow, offHandTarget);
                }
            }
        } else {
            safeGiveItem(player, createJobItem(player, pClass, level), offHandTarget);
        }

        // 전 직업 공용 곡괭이 지급
        // 공통 도구(곡괭이) 지급 - 모든 직업 중 가장 높은 레벨 기준
        int highestLevel = getHighestClassLevel(player);
        int itemLevel = Math.min(highestLevel, 10);
        safeGiveItem(player, createCommonPickaxe(player, pClass, itemLevel), offHandTarget);
    }

    private void safeGiveItem(Player player, ItemStack item, Material offHandTarget) {
        // 원래 왼손에 들고 있던 종류의 아이템이면 왼손에 우선 지급
        if (offHandTarget != null && item.getType() == offHandTarget) {
            ItemStack currentOff = player.getInventory().getItemInOffHand();
            if (currentOff == null || currentOff.getType() == Material.AIR) {
                player.getInventory().setItemInOffHand(item);
                return;
            }
        }

        java.util.HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            for (ItemStack left : leftover.values()) {
                boolean swapped = false;
                for (int i = 0; i < 36; i++) {
                    ItemStack invItem = player.getInventory().getItem(i);
                    if (invItem != null && !isJobItem(invItem)) {
                        // 일반 아이템을 바닥에 드랍하고 그 슬롯에 직업 아이템을 넣음
                        player.getWorld().dropItemNaturally(player.getLocation(), invItem);
                        player.getInventory().setItem(i, left);
                        player.sendMessage(ChatColor.RED + "인벤토리가 가득 차 직업 아이템을 받기 위해 일반 아이템을 떨어뜨렸습니다!");
                        swapped = true;
                        break;
                    }
                }
                if (!swapped) {
                    player.getWorld().dropItemNaturally(player.getLocation(), left);
                    player.sendMessage(getMessage("job.inventory_full", "&c인벤토리가 꽉 차서 어쩔 수 없이 직업 아이템을 떨어뜨렸습니다!"));
                }
            }
        }
    }

    public ItemStack createTornTicket() {
        ItemStack item = new ItemStack(Material.PAPER);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + "찢어진 직업 레벨 상승권");
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + "사냥을 통해 얻은 귀중한 조각이다.",
                ChatColor.GRAY + "작업대에 9개를 모아 조합하면",
                ChatColor.GRAY + "완전한 직업 레벨 상승권이 된다.",
                "",
                ChatColor.AQUA + "유저 간 거래 가능"));
        meta.getPersistentDataContainer().set(tornTicketKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createLevelUpTicket() {
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "직업 레벨 상승권");
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + "우클릭하여 원하는 직업의 레벨을",
                ChatColor.GRAY + "비용 없이 1 올릴 수 있다.",
                "",
                ChatColor.GRAY + "작업대에 2개를 모아 조합하면",
                ChatColor.GRAY + "10레벨 이후에 사용할수있는 각성권이 된다.",
                "",
                ChatColor.AQUA + "유저 간 거래 가능"));
        meta.getPersistentDataContainer().set(levelUpTicketKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createAwakeningTicket() {
        ItemStack item = new ItemStack(Material.ENDER_EYE);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "각성권");
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + "우클릭하여 직업 각성 능력을",
                ChatColor.GRAY + "비용 없이 1개 해금할 수 있다.",
                "",
                ChatColor.AQUA + "유저 간 거래 가능"));
        meta.addEnchant(org.bukkit.enchantments.Enchantment.MENDING, 1, true);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(awakeningTicketKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createInventorySaveTicket() {
        ItemStack item = new ItemStack(Material.END_CRYSTAL);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GREEN + "인벤토리 세이브권");
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + "사망 시 이 아이템을 1개 소모하여",
                ChatColor.GRAY + "인벤토리를 보존할 수 있다.",
                "",
                ChatColor.AQUA + "유저 간 거래 가능"));
        meta.getPersistentDataContainer().set(this.inventorySaveTicketKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isJobItem(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return false;
        return item.getItemMeta().getPersistentDataContainer().has(jobItemKey, PersistentDataType.BYTE);
    }

    private void removeOldJobItems(Player player) {
        // 메인 인벤토리 (0-35) 및 기타 슬롯 검사
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (isJobItem(item)) {
                player.getInventory().setItem(i, null);
            }
        }
        // 왼손(Off-hand) 슬롯 명시적 검사 및 제거
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (isJobItem(offHand)) {
            player.getInventory().setItemInOffHand(null);
        }
    }

    public void removeDuplicateJobItems(Player player) {
        java.util.Set<Material> foundTypes = new java.util.HashSet<>();
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (isJobItem(item)) {
                Material type = item.getType();
                // 도구 계열은 재질이 달라질 수 있으므로 대표 타입으로 취급
                if (type.name().endsWith("_AXE"))
                    type = Material.DIAMOND_AXE;
                else if (type.name().endsWith("_PICKAXE"))
                    type = Material.DIAMOND_PICKAXE;
                else if (type.name().endsWith("_SHOVEL"))
                    type = Material.DIAMOND_SHOVEL;
                else if (type.name().endsWith("_HOE"))
                    type = Material.DIAMOND_HOE;

                if (foundTypes.contains(type)) {
                    // 이미 발견된 직업 아이템 타입이면 중복이므로 삭제
                    player.getInventory().setItem(i, null);
                } else {
                    foundTypes.add(type);
                }
            }
        }
    }

    private ItemStack createJobItem(Player player, PlayerClass pClass, int level) {
        int itemLevel = Math.min(level, 10);
        Material mat = Material.AIR;
        String name = "";

        ItemStack item = null;
        switch (pClass) {
            case WARRIOR:
                if (itemLevel == 1)
                    mat = Material.STONE_SWORD;
                else if (itemLevel < 4)
                    mat = Material.IRON_SWORD;
                else if (itemLevel < 7)
                    mat = Material.DIAMOND_SWORD;
                else
                    mat = Material.NETHERITE_SWORD;
                name = ChatColor.RED + "전사의 검";
                item = new ItemStack(mat);
                addWarriorEnchants(item, itemLevel);
                break;
            case MAGE:
                mat = Material.BLAZE_ROD;
                name = ChatColor.LIGHT_PURPLE + "마법사의 지팡이";
                if (level == 11|| level == 13){
                    name = ChatColor.LIGHT_PURPLE + "각성된 마법사의 지팡이";
                }
                item = new ItemStack(mat);
                // 인챈트 제거됨 (기본 공격력만 가짐)
                break;
            default:
                return new ItemStack(Material.AIR);
        }
        return finalizeJobItem(player, item, name, itemLevel);
    }

    private ItemStack createJobItem(Player player, Material baseMat, String name, int level) {
        int itemLevel = Math.min(level, 10);
        Material actualMat = baseMat;
        // 모험가 도구 재질 결정
        if (itemLevel >= 7) {
            if (baseMat == Material.DIAMOND_HOE)
                actualMat = Material.NETHERITE_HOE;
            else if (baseMat == Material.DIAMOND_SHOVEL)
                actualMat = Material.NETHERITE_SHOVEL;
            else if (baseMat == Material.DIAMOND_AXE)
                actualMat = Material.NETHERITE_AXE;
        } else if (itemLevel == 1) {
            if (baseMat == Material.DIAMOND_HOE)
                actualMat = Material.STONE_HOE;
            else if (baseMat == Material.DIAMOND_SHOVEL)
                actualMat = Material.STONE_SHOVEL;
            else if (baseMat == Material.DIAMOND_AXE)
                actualMat = Material.STONE_AXE;
        } else if (itemLevel < 5) {
            if (baseMat == Material.DIAMOND_HOE)
                actualMat = Material.IRON_HOE;
            else if (baseMat == Material.DIAMOND_SHOVEL)
                actualMat = Material.IRON_SHOVEL;
            else if (baseMat == Material.DIAMOND_AXE)
                actualMat = Material.IRON_AXE;
        }

        ItemStack item = new ItemStack(actualMat);
        // 모험가 도구(괭이/삽/도끼)에만 인챈트 적용
        if (baseMat == Material.DIAMOND_HOE || baseMat == Material.DIAMOND_SHOVEL || baseMat == Material.DIAMOND_AXE) {
            addFarmerEnchants(item, itemLevel);
        }
        return finalizeJobItem(player, item, name, itemLevel);
    }

    private ItemStack createCommonPickaxe(Player player, PlayerClass pClass, int level) {
        Material mat;
        if (level >= 7)
            mat = Material.NETHERITE_PICKAXE;
        else if (level >= 4)
            mat = Material.DIAMOND_PICKAXE;
        else if (level >= 2)
            mat = Material.IRON_PICKAXE;
        else
            mat = Material.STONE_PICKAXE;

        String name = "";
        switch (pClass) {
            case WARRIOR:
                name = ChatColor.RED + "전사의 곡괭이";
                break;
            case MAGE:
                name = ChatColor.LIGHT_PURPLE + "마법사의 곡괭이";
                break;
            case ADVENTURER:
                name = ChatColor.GREEN + "모험가의 곡괭이";
                break;
            default:
                return new ItemStack(Material.AIR);
        }

        ItemStack item = new ItemStack(mat);
        // 효율: 레벨10(V), 레벨8(IV), 레벨6(III), 레벨4(II), 레벨2(I)
        int itemLevel = Math.min(level, 10);
        if (itemLevel >= 10)
            item.addUnsafeEnchantment(Enchantment.EFFICIENCY, 5);
        else if (itemLevel >= 8)
            item.addUnsafeEnchantment(Enchantment.EFFICIENCY, 4);
        else if (itemLevel >= 6)
            item.addUnsafeEnchantment(Enchantment.EFFICIENCY, 3);
        else if (itemLevel >= 4)
            item.addUnsafeEnchantment(Enchantment.EFFICIENCY, 2);
        else if (itemLevel >= 2)
            item.addUnsafeEnchantment(Enchantment.EFFICIENCY, 1);

        // 행운: 레벨9(III), 레벨6(II), 레벨3(I)
        if (itemLevel >= 9)
            item.addUnsafeEnchantment(Enchantment.FORTUNE, 3);
        else if (itemLevel >= 6)
            item.addUnsafeEnchantment(Enchantment.FORTUNE, 2);
        else if (itemLevel >= 3)
            item.addUnsafeEnchantment(Enchantment.FORTUNE, 1);

        return finalizeJobItem(player, item, name, level);
    }

    private ItemStack finalizeJobItem(Player player, ItemStack item, String name, int level) {
        int displayLevel = Math.min(level, 10);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name + " (Lv." + displayLevel + ")");
            java.util.List<String> lore = new java.util.ArrayList<>();

            // 모험가 세트 가짜 약탈 표시 (실제 효과 미적용)
            if (name.contains("모험가") && !name.contains("곡괭이")
                    && (name.contains("도끼") || name.contains("삽") || name.contains("괭이") || name.contains("검"))) {
                if (level >= 9) {
                    lore.add(ChatColor.GRAY + "약탈 III");
                } else if (level >= 6) {
                    lore.add(ChatColor.GRAY + "약탈 II");
                } else if (level >= 3) {
                    lore.add(ChatColor.GRAY + "약탈 I");
                }
            }

            if (name.contains("곡괭이") && hasCommonAwakening(player, "mining_2x2")) {
                lore.add(ChatColor.GOLD + "공통 각성: 2x2 채굴");
            }

            lore.addAll(Arrays.asList(
                    ChatColor.GRAY + "이 아이템은 귀속되어 버릴 수 없습니다.",
                    ChatColor.GRAY + "추가 인챈트가 불가능합니다.",
                    ChatColor.DARK_GRAY + "[직업 전용 아이템]"));
            meta.setLore(lore);
            meta.setUnbreakable(true); // 수선 대신 파괴 불가 설정
            meta.getPersistentDataContainer().set(jobItemKey, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(ownerItemKey, PersistentDataType.STRING,
                    player.getUniqueId().toString());
            item.setItemMeta(meta);
        }
        return item;

    }

    private void addFarmerEnchants(ItemStack item, int level) {
        // 효율: Lv2(1), Lv5(2), Lv6(3), Lv7(4), Lv9(5)
        if (level >= 9)
            item.addUnsafeEnchantment(Enchantment.EFFICIENCY, 5);
        else if (level >= 7)
            item.addUnsafeEnchantment(Enchantment.EFFICIENCY, 4);
        else if (level >= 6)
            item.addUnsafeEnchantment(Enchantment.EFFICIENCY, 3);
        else if (level >= 5)
            item.addUnsafeEnchantment(Enchantment.EFFICIENCY, 2);
        else if (level >= 2)
            item.addUnsafeEnchantment(Enchantment.EFFICIENCY, 1);

        // 행운: Lv4(1), Lv7(2), Lv10(3)
        if (level >= 10)
            item.addUnsafeEnchantment(Enchantment.FORTUNE, 3);
        else if (level >= 7)
            item.addUnsafeEnchantment(Enchantment.FORTUNE, 2);
        else if (level >= 4)
            item.addUnsafeEnchantment(Enchantment.FORTUNE, 1);
    }

    private void addWarriorEnchants(ItemStack item, int level) {
        // 날카로움: Lv3(1), Lv6(2), Lv7(3), Lv8(4), Lv10(5)
        if (level >= 10)
            item.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        else if (level >= 8)
            item.addUnsafeEnchantment(Enchantment.SHARPNESS, 4);
        else if (level >= 7)
            item.addUnsafeEnchantment(Enchantment.SHARPNESS, 3);
        else if (level >= 6)
            item.addUnsafeEnchantment(Enchantment.SHARPNESS, 2);
        else if (level >= 3)
            item.addUnsafeEnchantment(Enchantment.SHARPNESS, 1);

        // 발화: Lv4(1), Lv9(2)
        if (level >= 9)
            item.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 2);
        else if (level >= 4)
            item.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 1);

        // 약탈: Lv3(I), Lv6(II), Lv9(III)
        if (level >= 9)
            item.addUnsafeEnchantment(Enchantment.LOOTING, 3);
        else if (level >= 6)
            item.addUnsafeEnchantment(Enchantment.LOOTING, 2);
        else if (level >= 3)
            item.addUnsafeEnchantment(Enchantment.LOOTING, 1);
    }

    public void applyClassBuffs(Player player) {
        PlayerClass pClass = getPlayerClass(player);
        int level = getPlayerClassLevel(player);

        // 접두어(Prefix) 및 전용 아이템 업데이트
        updatePlayerPrefix(player, pClass, level);
        syncJobItem(player, pClass);

        // 기존 직업 버프 초기화
        player.removePotionEffect(PotionEffectType.SPEED);
        player.removePotionEffect(PotionEffectType.STRENGTH);
        player.removePotionEffect(PotionEffectType.HASTE);
        player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        player.removePotionEffect(PotionEffectType.HEALTH_BOOST);
        player.removePotionEffect(PotionEffectType.RESISTANCE);
        player.removePotionEffect(PotionEffectType.REGENERATION);
        player.removePotionEffect(PotionEffectType.WEAKNESS);
        player.removePotionEffect(PotionEffectType.SATURATION);

        // 기존 체력 모디파이어 초기화 (바닐라 기본치 20.0 유지)
        org.bukkit.attribute.AttributeInstance healthAttr = player
                .getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (healthAttr != null) {
            // forEach 중 remove는 ConcurrentModification 위험 → 먼저 리스트로 수집 후 제거
            java.util.List<org.bukkit.attribute.AttributeModifier> toRemove = new java.util.ArrayList<>();
            for (org.bukkit.attribute.AttributeModifier mod : healthAttr.getModifiers()) {
                if (mod.getKey().equals(this.HEALTH_MODIFIER_KEY)) {
                    toRemove.add(mod);
                }
            }
            toRemove.forEach(healthAttr::removeModifier);
        }

        org.bukkit.attribute.AttributeInstance speedAttr = player
                .getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
        if (speedAttr != null) {
            speedAttr.getModifiers().forEach(mod -> {
                if (mod.getName().contains("speed_bonus") || mod.getName().contains("speed_proc")
                        || mod.getName().contains("speed_compensation")) {
                    speedAttr.removeModifier(mod);
                }
            });
        }

        org.bukkit.attribute.AttributeInstance attackAttr = player
                .getAttribute(org.bukkit.attribute.Attribute.ATTACK_DAMAGE);
        if (attackAttr != null) {
            attackAttr.getModifiers().forEach(mod -> {
                if (mod.getKey().equals(this.ATTACK_MODIFIER_KEY)
                        || mod.getKey().equals(this.STRENGTH_COMPENSATION_KEY)
                        || mod.getKey().equals(this.WEAKNESS_COMPENSATION_KEY)) {
                    attackAttr.removeModifier(mod);
                }
            });
        }

        // [공통] 모든 직업 중 가장 높은 레벨이 7 이상이면 야간 투시 부여
        int highestLevel = getHighestClassLevel(player);
        if (highestLevel >= 7) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false));
        }

        if (pClass == PlayerClass.WARRIOR) {
            // 전사: 체력 레벨 4, 7, 10 마다 한개(2 HP) 증가
            if (healthAttr != null) {
                double extra = 0;
                if (level >= 10)
                    extra = 6.0; // 3 hearts
                else if (level >= 7)
                    extra = 4.0; // 2 hearts
                else if (level >= 4)
                    extra = 2.0; // 1 heart

                if (extra > 0) {
                    healthAttr.addModifier(new org.bukkit.attribute.AttributeModifier(
                            HEALTH_MODIFIER_KEY, extra, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER,
                            org.bukkit.inventory.EquipmentSlotGroup.ANY));
                }
            }

            // 공격력 시스템: UI용 포션 효과 + 실제 데미지 배율(스칼라)
            if (attackAttr != null) {
                int strengthAmp = (level >= 10) ? 2 : (level >= 6) ? 1 : 0; // Strength 3, 2, 1
                double scalarMultiplier = getWarriorDamageMultiplier(level);

                // 1. UI용 포션 부여 (데미지 0 상태를 만들기 위해 보정 필요)
                player.addPotionEffect(
                        new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, strengthAmp, false, false));

                // 2. 포션 고정 데미지 상쇄 (힘1=+3, 힘2=+6, 힘3=+9)
                double compensation = -(3.0 * (strengthAmp + 1));
                attackAttr.addModifier(new org.bukkit.attribute.AttributeModifier(
                        this.STRENGTH_COMPENSATION_KEY, compensation,
                        org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER,
                        org.bukkit.inventory.EquipmentSlotGroup.ANY));

                // 3. 실제 배율(스칼라) 적용
                attackAttr.addModifier(new org.bukkit.attribute.AttributeModifier(
                        this.ATTACK_MODIFIER_KEY, scalarMultiplier,
                        org.bukkit.attribute.AttributeModifier.Operation.ADD_SCALAR,
                        org.bukkit.inventory.EquipmentSlotGroup.ANY));
            }

            // 저항: Lv8부터
            if (level >= 8) {
                player.addPotionEffect(
                        new PotionEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 0, false, false));
            }

            // 재생: Lv10
            if (level >= 10) {
                player.addPotionEffect(
                        new PotionEffect(PotionEffectType.REGENERATION, Integer.MAX_VALUE, 0, false, false));
            }

        } else if (pClass == PlayerClass.MAGE) {
            // 마법사는 아직 추가된 전용 포션 효과가 없음

        } else if (pClass == PlayerClass.ADVENTURER) {
            // 농부: 약화 1 (Lv1-4), Lv5에 제거
            if (level < 5) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, Integer.MAX_VALUE, 0, false, false));

                if (attackAttr != null) {
                    // UI만 나약함으로 보이게 하고, 바닐라 감소분은 보정한 뒤 실제 공격력은 20%만 감소시킨다.
                    attackAttr.addModifier(new org.bukkit.attribute.AttributeModifier(
                            this.WEAKNESS_COMPENSATION_KEY, 4.0,
                            org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER,
                            org.bukkit.inventory.EquipmentSlotGroup.ANY));
                    attackAttr.addModifier(new org.bukkit.attribute.AttributeModifier(
                            this.ATTACK_MODIFIER_KEY, -0.20,
                            org.bukkit.attribute.AttributeModifier.Operation.ADD_SCALAR,
                            org.bukkit.inventory.EquipmentSlotGroup.ANY));
                }
            }

            // 농부: 체력 2레벨당 1칸(2 HP) 증가 (최대 레벨 10 기준으로 캡)
            if (healthAttr != null) {
                int cappedLevel = Math.min(level, 10); // 각성(레벨 11~13)에서도 10 기준으로 고정
                double extra = (cappedLevel / 2) * 2.0;
                if (extra > 0) {
                    healthAttr.addModifier(new org.bukkit.attribute.AttributeModifier(
                            HEALTH_MODIFIER_KEY, extra, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER,
                            org.bukkit.inventory.EquipmentSlotGroup.ANY));
                }
            }

            // 저항: Lv5(1단계), Lv10(2단계)
            if (level >= 5) {
                int resistAmp = (level >= 10) ? 1 : 0;
                player.addPotionEffect(
                        new PotionEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE, resistAmp, false, false));
            }

            // 포만감: Lv10 (피격 시 제거되었다가 10초 뒤 복구됨)
            if (level >= 10) {
                // 기존 작업 취소 후 즉시 부여
                if (saturationTasks.containsKey(player.getUniqueId())) {
                    saturationTasks.get(player.getUniqueId()).cancel();
                    saturationTasks.remove(player.getUniqueId());
                }
                player.addPotionEffect(
                        new PotionEffect(PotionEffectType.SATURATION, Integer.MAX_VALUE, 0, false, false));
            }

            if (level >= 2) {
                int speedAmp = (level >= 5) ? 1 : 0;
                player.addPotionEffect(
                        new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, speedAmp, false, false));
            }
        }
    }

    /**
     * 아이템 동기화 없이 오직 모험가의 신속 패시브만 다시 적용합니다.
     */
    public void refreshSpeedPassive(Player player) {
        PlayerClass pClass = getPlayerClass(player);
        int level = getPlayerClassLevel(player);

        if (pClass == PlayerClass.ADVENTURER && level >= 2) {
            int speedAmp = (level >= 5) ? 1 : 0;
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, speedAmp, false, false));
        }
    }

    /**
     * 공통 각성 능력 보유 여부를 확인합니다.
     */
    public boolean hasCommonAwakening(Player player, String type) {
        PlayerProfile profile = profileCache.get(player.getUniqueId());
        if (profile != null) {
            return profile.commonAwakenings.getOrDefault(type, false);
        }
        String uuid = player.getUniqueId().toString();
        return dataConfig.getBoolean(uuid + ".common_awakenings." + type, false);
    }

    /**
     * 공통 각성 능력을 설정합니다.
     */
    public void setCommonAwakening(Player player, String type, boolean unlocked) {
        String uuid = player.getUniqueId().toString();
        dataConfig.set(uuid + ".common_awakenings." + type, unlocked);
        
        PlayerProfile profile = profileCache.get(player.getUniqueId());
        if (profile != null) {
            profile.commonAwakenings.put(type, unlocked);
            profile.dirty = true;
        }
        
        saveDataConfig();
    }

    /**
     * 플레이어 인벤토리에서 해당 Material을 가진 직업 전용 아이템을 찾습니다.
     */
    public ItemStack findJobItem(Player player, Material material) {
        // 도끼 스킬은 재질이 변할 수 있으므로 _AXE 계열 전체를 허용
        if (material == Material.DIAMOND_AXE) {
            for (int i = 0; i < player.getInventory().getSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack != null && isJobItem(stack)) {
                    String typeName = stack.getType().name();
                    if (typeName.endsWith("_AXE")) {
                        return stack;
                    }
                }
            }
            return null;
        }

        // 그 외 스킬은 정확한 재질 일치로 탐색
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack != null && stack.getType() == material && isJobItem(stack)) {
                return stack;
            }
        }
        return null;
    }

    /**
     * 특정 기술의 쿨타임 시각적 피드백(아이템 갯수 카운트다운 및 액션바/채팅 알림)을 시작합니다.
     */
    public void startCooldownDisplay(Player player, Material material, double cooldownSeconds, String skillName, String prefix) {
        long endTime = System.currentTimeMillis() + (long) (cooldownSeconds * 1000L);
        startCooldownDisplay(player, material, () -> endTime, skillName, prefix);
    }

    /**
     * 쿨타임 종료 시각을 동적으로 제공받는 오버로드 메소드입니다. (마법사 원소 폭주 등 동적 쿨타임 단축 대응)
     */
    public void startCooldownDisplay(Player player, Material material, java.util.function.LongSupplier endTimeSupplier, String skillName, String prefix) {
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }

                long now = System.currentTimeMillis();
                long endTime = endTimeSupplier.getAsLong();
                double remaining = (endTime - now) / 1000.0;

                ItemStack targetItem = findJobItem(player, material);

                if (remaining > 0.05) {
                    if (targetItem != null && material != Material.SHIELD) {
                        int displayAmount = (int) Math.ceil(remaining);
                        targetItem.setAmount(Math.min(displayAmount, 64));
                    }
                    if (material == Material.SHIELD) {
                        String timeText = (Math.abs(remaining - Math.round(remaining)) < 0.01)
                                ? String.valueOf((int) remaining)
                                : String.format("%.1f", remaining);
                        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                                new net.md_5.bungee.api.chat.TextComponent(
                                        ChatColor.RED + skillName + " 쿨타임: " + timeText + "초"));
                    }
                } else {
                    if (targetItem != null && material != Material.SHIELD) {
                        targetItem.setAmount(1);
                    }
                    if (material == Material.SHIELD) {
                        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                                new net.md_5.bungee.api.chat.TextComponent(ChatColor.GREEN + skillName + " 준비 완료!"));
                    }
                    player.sendMessage(ChatColor.GREEN + prefix + ChatColor.YELLOW + skillName
                            + ChatColor.GREEN + " 능력을 다시 사용할 수 있습니다!");
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }
}
