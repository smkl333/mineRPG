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

import java.io.File;
import java.io.IOException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.Arrays;
import java.util.Random;

public class ClassManager {

    private final NamespacedKey jobItemKey;
    private final NamespacedKey ownerItemKey;
    private final NamespacedKey guiTargetClassKey;
    private final NamespacedKey tornTicketKey;
    private final NamespacedKey levelUpTicketKey;
    private final NamespacedKey HEALTH_MODIFIER_KEY;
    private final NamespacedKey ATTACK_MODIFIER_KEY;
    private final NamespacedKey STRENGTH_COMPENSATION_KEY;
    private final Random random;
    private final Plugin plugin;

    private final File dataFile;
    private FileConfiguration dataConfig;
    private FileConfiguration mainConfig;

    private final java.util.Map<java.util.UUID, Long> adventurerCombatCooldowns = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, org.bukkit.scheduler.BukkitTask> saturationTasks = new java.util.HashMap<>();

    private final File messagesFile;
    private FileConfiguration messagesConfig;

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
        this.random = new Random();

        // 메인 설정 로드
        this.mainConfig = plugin.getConfig();

        // 데이터 파일 초기화
        this.dataFile = new File(plugin.getDataFolder(), "players.yml");
        loadDataConfig();

        // 메시지 파일 초기화
        this.messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        loadMessagesConfig();
    }

    public void reloadConfigs() {
        plugin.reloadConfig();
        this.mainConfig = plugin.getConfig();
        loadDataConfig();
        loadMessagesConfig();
    }

    public NamespacedKey getOwnerItemKey() { return ownerItemKey; }
    public NamespacedKey getGuiTargetClassKey() { return guiTargetClassKey; }
    public NamespacedKey getTornTicketKey() { return tornTicketKey; }
    public NamespacedKey getLevelUpTicketKey() { return levelUpTicketKey; }

    public void registerRecipes() {
        NamespacedKey recipeKey = new NamespacedKey(plugin, "level_up_ticket_recipe");
        // 이미 등록되어 있다면 무시하거나 제거할 수 있지만, 
        // 1.16+ 부터는 RecipeChoice.ExactChoice를 사용하여 커스텀 NBT 아이템을 재료로 쓸 수 있습니다.
        try {
            // 중복 등록 방지를 위해 기존 레시피 제거
            plugin.getServer().removeRecipe(recipeKey);
            
            org.bukkit.inventory.ShapedRecipe recipe = new org.bukkit.inventory.ShapedRecipe(recipeKey, createLevelUpTicket());
            recipe.shape("TTT", "TTT", "TTT");
            recipe.setIngredient('T', new org.bukkit.inventory.RecipeChoice.ExactChoice(createTornTicket()));
            plugin.getServer().addRecipe(recipe);
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
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void reloadPlayerData() {
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        // 접속 중인 모든 플레이어의 접두사/버프 갱신
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            updatePlayerPrefix(player);
            applyClassBuffs(player);
        }
    }

    public void initializePlayerLevels(Player player) {
        String uuid = player.getUniqueId().toString();
        boolean changed = false;
        PlayerClass[] classes = {PlayerClass.WARRIOR, PlayerClass.MAGE, PlayerClass.ADVENTURER};
        
        for (PlayerClass pClass : classes) {
            if (!dataConfig.contains(uuid + ".levels." + pClass.name())) {
                dataConfig.set(uuid + ".levels." + pClass.name(), 0);
                changed = true;
            }
        }
        
        if (changed) {
            dataConfig.set(uuid + ".last_known_name", player.getName());
            saveDataConfig();
        }
    }

    public PlayerClass getPlayerClass(Player player) {
        String uuid = player.getUniqueId().toString();
        String className = dataConfig.getString(uuid + ".class", "NONE");
        try {
            return PlayerClass.valueOf(className);
        } catch (IllegalArgumentException e) {
            return PlayerClass.NONE;
        }
    }

    public void setPlayerClass(Player player, PlayerClass playerClass) {
        String uuid = player.getUniqueId().toString();
        dataConfig.set(uuid + ".class", playerClass.name());
        dataConfig.set(uuid + ".last_known_name", player.getName()); // 관리자 식별용
        saveDataConfig();
        updatePlayerPrefix(player);
    }

    public int getPlayerClassLevel(Player player) {
        return getStoredLevel(player, getPlayerClass(player));
    }

    public int getStoredLevel(Player player, PlayerClass targetClass) {
        if (targetClass == PlayerClass.NONE)
            return 0;
        String uuid = player.getUniqueId().toString();
        return dataConfig.getInt(uuid + ".levels." + targetClass.name(), 0);
    }

    public String getMessage(String key, String def) {
        if (messagesConfig == null) return ChatColor.translateAlternateColorCodes('&', def);
        String msg = messagesConfig.getString(key);
        if (msg == null) {
            // config.yml의 하위 호환성 유지
            msg = mainConfig.getString("messages." + key, def);
        }
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    public void setPlayerClassLevel(Player player, int level) {
        setStoredLevel(player, getPlayerClass(player), level);
    }

    public void setStoredLevel(Player player, PlayerClass targetClass, int level) {
        if (targetClass == PlayerClass.NONE)
            return;
        String uuid = player.getUniqueId().toString();
        dataConfig.set(uuid + ".levels." + targetClass.name(), level);
        dataConfig.set(uuid + ".last_known_name", player.getName());
        saveDataConfig();

        // 이제 마크 레벨은 건드리지 않고 접두어만 업데이트
        updatePlayerPrefix(player);
    }

    public void playLevelUpEffects(Player player, int newLevel) {
        // 1. 개인 채팅 메시지
        String msg = getMessage("job.upgrade_success", "&d레벨을 &f{level}&d(으)로 올렸습니다!");
        player.sendMessage(msg.replace("{level}", String.valueOf(newLevel)));

        // 2. 폭죽 효과
        if (newLevel < 10) {
            spawnFirework(player, org.bukkit.Color.PURPLE);
        } else {
            for (int i = 0; i < 5; i++) {
                spawnFirework(player,
                        org.bukkit.Color.fromRGB(random.nextInt(256), random.nextInt(256), random.nextInt(256)));
            }

            // 3. 10레벨 달성 시 전체 공지
            org.bukkit.Bukkit.broadcastMessage(org.bukkit.ChatColor.AQUA + "========================================");
            org.bukkit.Bukkit.broadcastMessage(
                    org.bukkit.ChatColor.GOLD + "[경축] " + org.bukkit.ChatColor.WHITE + player.getName() +
                            org.bukkit.ChatColor.YELLOW + "님이 " + org.bukkit.ChatColor.GREEN + "10레벨" +
                            org.bukkit.ChatColor.YELLOW + "을 달성하여 " + org.bukkit.ChatColor.RED + getJobTitle(getPlayerClass(player), 10) + org.bukkit.ChatColor.YELLOW + "가 되었습니다!");
            org.bukkit.Bukkit.broadcastMessage(org.bukkit.ChatColor.AQUA + "========================================");
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
        String uuid = player.getUniqueId().toString();
        return dataConfig.getInt(uuid + ".hunger_reset_ticks", 0);
    }

    public void setHungerBaseTicks(Player player, int ticks) {
        String uuid = player.getUniqueId().toString();
        dataConfig.set(uuid + ".hunger_reset_ticks", ticks);
        saveDataConfig();
    }

    public boolean isAdventurerSaturationActive(Player player) {
        if (getPlayerClass(player) != PlayerClass.ADVENTURER || getPlayerClassLevel(player) < 10) return false;
        
        long now = System.currentTimeMillis();
        long lastHit = adventurerCombatCooldowns.getOrDefault(player.getUniqueId(), 0L);
        int cooldownSec = mainConfig.getInt("abilities.adventurer.saturation_cooldown", 30);
        
        return (now - lastHit >= cooldownSec * 1000L);
    }

    public void triggerAdventurerCombatCooldown(Player player) {
        if (getPlayerClass(player) != PlayerClass.ADVENTURER || getPlayerClassLevel(player) < 10) return;
        
        adventurerCombatCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        
        // 포션 효과 제거
        player.removePotionEffect(PotionEffectType.SATURATION);
        
        // 기존 재지급 작업이 있다면 취소
        if (saturationTasks.containsKey(player.getUniqueId())) {
            saturationTasks.get(player.getUniqueId()).cancel();
        }
        
        // 10초(설정값) 뒤에 포션 재지급하는 스케줄러 시작
        int cooldownSec = mainConfig.getInt("abilities.adventurer.saturation_cooldown", 10);
        org.bukkit.scheduler.BukkitTask task = new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && getPlayerClass(player) == PlayerClass.ADVENTURER && getPlayerClassLevel(player) >= 10) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, Integer.MAX_VALUE, 0, false, false));
                    player.sendMessage(getMessage("skill.adventurer_saturation_active", "&a[포화] &f능력이 다시 활성화되었습니다."));
                }
                saturationTasks.remove(player.getUniqueId());
            }
        }.runTaskLater(plugin, cooldownSec * 20L);
        
        saturationTasks.put(player.getUniqueId(), task);
    }

    public void cleanupPlayerData(Player player) {
        java.util.UUID uuid = player.getUniqueId();
        if (saturationTasks.containsKey(uuid)) {
            saturationTasks.get(uuid).cancel();
            saturationTasks.remove(uuid);
        }
        adventurerCombatCooldowns.remove(uuid);
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

    public int getUpgradeCost(int currentLevel) {
        java.util.List<Integer> costs = mainConfig.getIntegerList("costs.upgrade");
        if (costs.isEmpty() || currentLevel < 1 || currentLevel >= 10)
            return 0;
        return costs.get(currentLevel - 1);
    }

    public double getMageTntMultiplier(int level) {
        java.util.List<Double> multipliers = mainConfig.getDoubleList("cooldowns.mage_tnt_damage_levels");
        if (multipliers.isEmpty()) return 0.33;
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
        return mainConfig.getDouble("drops.torn_ticket_chance", 0.05);
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

    public int getMageWandCooldown() {
        return mainConfig.getInt("cooldowns.mage_wand", 15);
    }

    public int getMageSpellbookCooldown() {
        return mainConfig.getInt("cooldowns.mage_spellbook", 30);
    }

    public double getMageCooldownMultiplier(int level) {
        java.util.List<Double> reductions = mainConfig.getDoubleList("abilities.mage.cooldown_reduction_levels");
        if (reductions.isEmpty()) return 1.0;
        int index = Math.max(0, Math.min(level - 1, reductions.size() - 1));
        return reductions.get(index);
    }

    public int getWarriorDoubleJumpCooldown() {
        return mainConfig.getInt("abilities.warrior.double_jump.cooldown_seconds", 5);
    }

    public double getWarriorArrowStormDamage(int level) {
        java.util.List<Double> damages = mainConfig.getDoubleList("abilities.warrior.arrow_storm.damage_levels");
        if (damages.isEmpty()) return 100.0;
        int index = Math.max(0, Math.min(level - 1, damages.size() - 1));
        return damages.get(index);
    }

    public int getWarriorArrowStormCount() {
        return mainConfig.getInt("abilities.warrior.arrow_storm.arrow_count", 12);
    }

    public int getWarriorArrowStormCooldown() {
        return mainConfig.getInt("abilities.warrior.arrow_storm.cooldown", 20);
    }

    public int getWarriorSlowDuration() {
        return mainConfig.getInt("abilities.warrior.slow_duration_ticks", 40);
    }

    public double getWarriorDamageMultiplier(int level) {
        java.util.List<Double> multipliers = mainConfig.getDoubleList("abilities.warrior.damage_multipliers");
        if (multipliers.isEmpty()) return (level >= 10) ? 0.3 : (level >= 6) ? 0.2 : 0.1;
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

    private int getLevelBasedInt(String path, int level, int fallback) {
        java.util.List<Integer> values = mainConfig.getIntegerList(path);
        if (values.isEmpty()) return fallback;
        int index = Math.max(0, Math.min(level - 1, values.size() - 1));
        return values.get(index);
    }

    public int getAdventurerRegenFieldDurationSeconds(int level) {
        return getLevelBasedInt("abilities.adventurer.regen_field.duration_seconds_levels", level, 6);
    }

    public int getAdventurerRegenFieldAmplifier(int level) {
        return getLevelBasedInt("abilities.adventurer.regen_field.amplifier_levels", level, 0);
    }

    public int getAdventurerRegenFieldCooldownSeconds(int level) {
        return getLevelBasedInt("abilities.adventurer.regen_field.cooldown_seconds_levels", level, 30);
    }

    public double getAdventurerRegenFieldRadius(int level) {
        java.util.List<Double> radii = mainConfig.getDoubleList("abilities.adventurer.regen_field.radius_levels");
        if (radii.isEmpty()) return 4.0;
        int index = Math.max(0, Math.min(level - 1, radii.size() - 1));
        return radii.get(index);
    }

    public int getHighestClassLevel(Player player) {
        int maxLevel = 1;
        for (PlayerClass pClass : PlayerClass.values()) {
            if (pClass == PlayerClass.NONE) continue;
            int lvl = getStoredLevel(player, pClass);
            if (lvl > maxLevel) maxLevel = lvl;
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
        if (level >= 10)      color = ChatColor.RED;     // 마스터: 빨강
        else if (level >= 7)  color = ChatColor.YELLOW;  // 전문/베테랑: 노랑
        else if (level >= 4)  color = ChatColor.BLUE;    // 숙련된: 파랑
        else                  color = ChatColor.GREEN;   // 견습/초보: 초록

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
            return;
        }

        String prefix = getPlayerPrefix(player);

        // 1. 채팅용 (DisplayName)
        player.setDisplayName(prefix + player.getName());

        // 2. 탭 리스트 및 이름표
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

        // 기존 직업 아이템 모두 제거 (모든 슬롯)
        removeOldJobItems(player);

        if (pClass == PlayerClass.NONE)
            return;

        int level = getPlayerClassLevel(player);
        // 새로운 직업 아이템 지급
        if (pClass == PlayerClass.ADVENTURER) {
            int teleportCooldown = mainConfig.getInt("cooldowns.adventurer_teleport", 180);
            safeGiveItem(player, createJobItem(player, Material.DIAMOND_HOE, ChatColor.GREEN + "모험가의 괭이", level));
            safeGiveItem(player, createJobItem(player, Material.DIAMOND_SHOVEL, ChatColor.GREEN + "모험가의 삽", level));
            ItemStack axe = createJobItem(player, Material.DIAMOND_AXE, ChatColor.GREEN + "모험가의 도끼", level);
            if (level >= 10) {
                org.bukkit.inventory.meta.ItemMeta axeMeta = axe.getItemMeta();
                java.util.List<String> lore = new java.util.ArrayList<>(axeMeta.getLore());
                lore.add(0, ChatColor.GOLD + "[패시브] " + ChatColor.WHITE + "죽음의 위기 시 스폰 지점 이동 (쿨타임 " + teleportCooldown + "초)");
                axeMeta.setLore(lore);
                axe.setItemMeta(axeMeta);
            }
            safeGiveItem(player, axe);

            int regenDuration = getAdventurerRegenFieldDurationSeconds(level);
            int regenAmp = getAdventurerRegenFieldAmplifier(level);
            int regenCooldown = getAdventurerRegenFieldCooldownSeconds(level);
            ItemStack regenPotion = createJobItem(player, Material.POTION, ChatColor.GREEN + "모험가의 재생 포션", level);
            org.bukkit.inventory.meta.ItemMeta potionMeta = regenPotion.getItemMeta();
            java.util.List<String> potionLore = new java.util.ArrayList<>(potionMeta.getLore());
            potionLore.add(0, ChatColor.GOLD + "[우클릭] " + ChatColor.WHITE + "재생 필드 전개 (쿨타임 " + regenCooldown + "초)");
            potionLore.add(1, ChatColor.GRAY + "효과: 반경 " + String.format("%.1f", getAdventurerRegenFieldRadius(level)) + "블록, "
                    + regenDuration + "초, 재생 " + (regenAmp + 1));
            potionMeta.setLore(potionLore);
            regenPotion.setItemMeta(potionMeta);
            safeGiveItem(player, regenPotion);
        } else if (pClass == PlayerClass.MAGE) {
            int wandCooldown = mainConfig.getInt("cooldowns.mage_wand", 30);
            int spellCooldown = mainConfig.getInt("cooldowns.mage_spellbook", 15);
            
            ItemStack rod = createJobItem(player, pClass, level);
            org.bukkit.inventory.meta.ItemMeta rodMeta = rod.getItemMeta();
            java.util.List<String> rodLore = new java.util.ArrayList<>(rodMeta.getLore());
            rodLore.add(0, ChatColor.GOLD + "[우클릭] " + ChatColor.WHITE + "마법 TNT 투척 (쿨타임 " + wandCooldown + "초)");
            rodMeta.setLore(rodLore);
            rod.setItemMeta(rodMeta);
            safeGiveItem(player, rod);

            ItemStack spellbook = new ItemStack(Material.ENCHANTED_BOOK);
            org.bukkit.inventory.meta.ItemMeta bookMeta = spellbook.getItemMeta();
            bookMeta.setDisplayName(ChatColor.LIGHT_PURPLE + "마법서 (Lv." + level + ")");
            bookMeta.setLore(Arrays.asList(
                    ChatColor.GOLD + "[우클릭] " + ChatColor.WHITE + "전방 공간 도약 (쿨타임 " + spellCooldown + "초)",
                    ChatColor.GRAY + "이 아이템은 귀속되어 버릴 수 없습니다.",
                    ChatColor.GRAY + "추가 인챈트가 불가능합니다.",
                    ChatColor.DARK_GRAY + "[직업 전용 아이템]"
            ));
            bookMeta.setUnbreakable(true);
            bookMeta.getPersistentDataContainer().set(jobItemKey, PersistentDataType.BYTE, (byte) 1);
            bookMeta.getPersistentDataContainer().set(ownerItemKey, PersistentDataType.STRING, player.getUniqueId().toString());
            spellbook.setItemMeta(bookMeta);
            safeGiveItem(player, spellbook);
        } else if (pClass == PlayerClass.WARRIOR) {
            safeGiveItem(player, createJobItem(player, pClass, level));
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
                safeGiveItem(player, shield);
            }
            // 3레벨 이상일 때 애로우 스톰용 '전사의 화살' 지급
            if (level >= 3) {
                int stormCd = mainConfig.getInt("abilities.warrior.arrow_storm.cooldown", 20);
                ItemStack stormArrow = createJobItem(player, Material.ARROW, ChatColor.GOLD + "전사의 화살", level);
                org.bukkit.inventory.meta.ItemMeta arrowMeta = stormArrow.getItemMeta();
                if (arrowMeta != null) {
                    java.util.List<String> arrowLore = new java.util.ArrayList<>(arrowMeta.getLore());
                    arrowLore.add(0, ChatColor.GOLD + "[우클릭] " + ChatColor.WHITE + "애로우 스톰 발동 (쿨타임 " + stormCd + "초)");
                    arrowMeta.setLore(arrowLore);
                    stormArrow.setItemMeta(arrowMeta);
                }
                safeGiveItem(player, stormArrow);
            }
        } else {
            safeGiveItem(player, createJobItem(player, pClass, level));
        }

        // 전 직업 공용 곡괭이 지급
        // 공통 도구(곡괭이) 지급 - 모든 직업 중 가장 높은 레벨 기준
        int highestLevel = getHighestClassLevel(player);
        safeGiveItem(player, createCommonPickaxe(player, pClass, highestLevel));
    }

    private void safeGiveItem(Player player, ItemStack item) {
        // 인벤토리에 공간이 없으면 바닥에 드랍
        if (!player.getInventory().addItem(item).isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
            player.sendMessage(getMessage("job.inventory_full", "&c인벤토리가 가득 차서 아이템을 발밑에 떨어뜨렸습니다!"));
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
                ChatColor.AQUA + "유저 간 거래 가능"
        ));
        meta.getPersistentDataContainer().set(tornTicketKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createLevelUpTicket() {
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "직업 레벨 상승권");
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + "이 책을 우클릭하면",
                ChatColor.GRAY + "원하는 직업의 레벨을",
                ChatColor.GRAY + "비용 없이 1 올릴 수 있다.",
                "",
                ChatColor.AQUA + "유저 간 거래 가능"
        ));
        meta.getPersistentDataContainer().set(levelUpTicketKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isJobItem(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return false;
        return item.getItemMeta().getPersistentDataContainer().has(jobItemKey, PersistentDataType.BYTE);
    }

    private void removeOldJobItems(Player player) {
        // 모든 슬롯 (인벤토리 + 장비 + 왼손) 검사
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (isJobItem(item)) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    private ItemStack createJobItem(Player player, PlayerClass pClass, int level) {
        Material mat = Material.AIR;
        String name = "";

        ItemStack item = null;
        switch (pClass) {
            case WARRIOR:
                if (level == 1)
                    mat = Material.STONE_SWORD;
                else if (level < 4)
                    mat = Material.IRON_SWORD;
                else if (level < 8)
                    mat = Material.DIAMOND_SWORD;
                else
                    mat = Material.NETHERITE_SWORD;
                name = ChatColor.RED + "전사의 검";
                item = new ItemStack(mat);
                addWarriorEnchants(item, level);
                break;
            case MAGE:
                mat = Material.BLAZE_ROD;
                name = ChatColor.LIGHT_PURPLE + "마법사의 지팡이";
                item = new ItemStack(mat);
                // 인챈트 제거됨 (기본 공격력만 가짐)
                break;
            default:
                return new ItemStack(Material.AIR);
        }
        return finalizeJobItem(player, item, name, level);
    }

    private ItemStack createJobItem(Player player, Material baseMat, String name, int level) {
        Material actualMat = baseMat;
        // 모험가 도구 재질 결정
        if (level >= 8) {
            if (baseMat == Material.DIAMOND_HOE) actualMat = Material.NETHERITE_HOE;
            else if (baseMat == Material.DIAMOND_SHOVEL) actualMat = Material.NETHERITE_SHOVEL;
            else if (baseMat == Material.DIAMOND_AXE) actualMat = Material.NETHERITE_AXE;
        } else if (level < 4) {
            if (baseMat == Material.DIAMOND_HOE) actualMat = Material.IRON_HOE;
            else if (baseMat == Material.DIAMOND_SHOVEL) actualMat = Material.IRON_SHOVEL;
            else if (baseMat == Material.DIAMOND_AXE) actualMat = Material.IRON_AXE;
        }

        ItemStack item = new ItemStack(actualMat);
        // 모험가 도구(괭이/삽/도끼)에만 인챈트 적용
        if (baseMat == Material.DIAMOND_HOE || baseMat == Material.DIAMOND_SHOVEL || baseMat == Material.DIAMOND_AXE) {
            addFarmerEnchants(item, level);
        }
        return finalizeJobItem(player, item, name, level);
    }

    private ItemStack createCommonPickaxe(Player player, PlayerClass pClass, int level) {
        Material mat;
        if (level >= 8) mat = Material.NETHERITE_PICKAXE;
        else if (level >= 4) mat = Material.DIAMOND_PICKAXE;
        else if (level >= 2) mat = Material.IRON_PICKAXE;
        else mat = Material.STONE_PICKAXE;

        String name = "";
        switch (pClass) {
            case WARRIOR: name = ChatColor.RED + "전사의 곡괭이"; break;
            case MAGE: name = ChatColor.LIGHT_PURPLE + "마법사의 곡괭이"; break;
            case ADVENTURER: name = ChatColor.GREEN + "모험가의 곡괭이"; break;
            default: return new ItemStack(Material.AIR);
        }

        ItemStack item = new ItemStack(mat);
        // 효율: 레벨10(V), 레벨8(IV), 레벨6(III), 레벨4(II), 레벨2(I)
        if (level >= 10) item.addUnsafeEnchantment(Enchantment.EFFICIENCY, 5);
        else if (level >= 8) item.addUnsafeEnchantment(Enchantment.EFFICIENCY, 4);
        else if (level >= 6) item.addUnsafeEnchantment(Enchantment.EFFICIENCY, 3);
        else if (level >= 4) item.addUnsafeEnchantment(Enchantment.EFFICIENCY, 2);
        else if (level >= 2) item.addUnsafeEnchantment(Enchantment.EFFICIENCY, 1);

        // 행운: 레벨9(III), 레벨6(II), 레벨3(I)
        if (level >= 9) item.addUnsafeEnchantment(Enchantment.FORTUNE, 3);
        else if (level >= 6) item.addUnsafeEnchantment(Enchantment.FORTUNE, 2);
        else if (level >= 3) item.addUnsafeEnchantment(Enchantment.FORTUNE, 1);

        return finalizeJobItem(player, item, name, level);
    }

    private ItemStack finalizeJobItem(Player player, ItemStack item, String name, int level) {
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name + " (Lv." + level + ")");
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "이 아이템은 귀속되어 버릴 수 없습니다.",
                    ChatColor.GRAY + "추가 인챈트가 불가능합니다.",
                    ChatColor.DARK_GRAY + "[직업 전용 아이템]"));
            meta.setUnbreakable(true); // 수선 대신 파괴 불가 설정
            meta.getPersistentDataContainer().set(jobItemKey, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(ownerItemKey, PersistentDataType.STRING, player.getUniqueId().toString());
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

        // 행운: Lv4(1), Lv8(2), Lv10(3)
        if (level >= 10)
            item.addUnsafeEnchantment(Enchantment.FORTUNE, 3);
        else if (level >= 8)
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
            healthAttr.getModifiers().forEach(mod -> {
                if (mod.getKey().equals(HEALTH_MODIFIER_KEY)) {
                    healthAttr.removeModifier(mod);
                }
            });
        }
        
        org.bukkit.attribute.AttributeInstance attackAttr = player
                .getAttribute(org.bukkit.attribute.Attribute.ATTACK_DAMAGE);
        if (attackAttr != null) {
            attackAttr.getModifiers().forEach(mod -> {
                if (mod.getKey().equals(ATTACK_MODIFIER_KEY) || mod.getKey().equals(STRENGTH_COMPENSATION_KEY)) {
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
                        HEALTH_MODIFIER_KEY, extra, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.ANY));
                }
            }

            // 전사 신속: Lv4부터 신속 I 고정
            if (level >= 4) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 0, false, false));
            }

            // 공격력 시스템: UI용 포션 효과 + 실제 데미지 배율(스칼라)
            if (attackAttr != null) {
                int strengthAmp = (level >= 10) ? 2 : (level >= 6) ? 1 : 0; // Strength 3, 2, 1
                double scalarMultiplier = getWarriorDamageMultiplier(level);
                
                // 1. UI용 포션 부여 (데미지 0 상태를 만들기 위해 보정 필요)
                player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, strengthAmp, false, false));
                
                // 2. 포션 고정 데미지 상쇄 (힘1=+3, 힘2=+6, 힘3=+9)
                double compensation = -(3.0 * (strengthAmp + 1));
                attackAttr.addModifier(new org.bukkit.attribute.AttributeModifier(
                    STRENGTH_COMPENSATION_KEY, compensation, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.ANY));
                
                // 3. 실제 배율(스칼라) 적용
                attackAttr.addModifier(new org.bukkit.attribute.AttributeModifier(
                    ATTACK_MODIFIER_KEY, scalarMultiplier, org.bukkit.attribute.AttributeModifier.Operation.ADD_SCALAR, org.bukkit.inventory.EquipmentSlotGroup.ANY));
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
            }

            // 농부: 체력 2레벨당 1칸(2 HP) 증가
            if (healthAttr != null) {
                double extra = (level / 2) * 2.0;
                if (extra > 0) {
                    healthAttr.addModifier(new org.bukkit.attribute.AttributeModifier(
                        HEALTH_MODIFIER_KEY, extra, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.ANY));
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
                player.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, Integer.MAX_VALUE, 0, false, false));
            }

            // 신속: Lv2(1), Lv6(2), Lv9(3)
            if (level >= 2) {
                int speedAmp = 0;
                if (level >= 9) speedAmp = 2; // Speed 3
                else if (level >= 6) speedAmp = 1; // Speed 2
                player.addPotionEffect(
                        new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, speedAmp, false, false));
            }
        }
    }
}
