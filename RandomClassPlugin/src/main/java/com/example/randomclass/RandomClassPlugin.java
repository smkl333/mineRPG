package com.example.randomclass;

import com.example.randomclass.listeners.ChatListener;
import com.example.randomclass.listeners.ClassAbilityListener;
import com.example.randomclass.listeners.ExpListener;
import com.example.randomclass.listeners.HungerListener;
import com.example.randomclass.listeners.JobGuiListener;
import com.example.randomclass.listeners.JobItemListener;
import com.example.randomclass.listeners.PlayerJoinListener;
import com.example.randomclass.listeners.BlockBreakListener;
import com.example.randomclass.listeners.LevelUpTicketListener;
import com.example.randomclass.listeners.MageAbilityListener;
import com.example.randomclass.listeners.BossListener;
import com.example.randomclass.managers.BossManager;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

public class RandomClassPlugin extends JavaPlugin {

    private ClassManager classManager;
    private JobGuiListener jobGuiListener;
    private ClassAbilityListener classAbilityListener;
    private MageAbilityListener mageAbilityListener;
    private BossManager bossManager;
    private final java.util.Set<java.util.UUID> rollingPlayers = new java.util.HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig(); // 기본 config.yml 생성 (이미 있으면 건너뜀)
        
        this.classManager = new ClassManager(this);
        this.jobGuiListener = new JobGuiListener(classManager);
        this.classAbilityListener = new ClassAbilityListener(this, classManager);
        this.mageAbilityListener = new MageAbilityListener(this, classManager);

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this, classManager), this);
        getServer().getPluginManager().registerEvents(jobGuiListener, this);
        getServer().getPluginManager().registerEvents(new HungerListener(classManager), this);
        getServer().getPluginManager().registerEvents(classAbilityListener, this);
        getServer().getPluginManager().registerEvents(new ExpListener(classManager), this);
        getServer().getPluginManager().registerEvents(new JobItemListener(classManager), this);
        getServer().getPluginManager().registerEvents(new ChatListener(classManager), this);
        getServer().getPluginManager().registerEvents(new BlockBreakListener(classManager, this), this);
        getServer().getPluginManager().registerEvents(new LevelUpTicketListener(classManager), this);
        getServer().getPluginManager().registerEvents(mageAbilityListener, this);

        this.bossManager = new BossManager(this);
        getServer().getPluginManager().registerEvents(new BossListener(bossManager, this), this);

        com.example.randomclass.managers.StatusDisplayManager statusManager = new com.example.randomclass.managers.StatusDisplayManager(this, classManager);
        getCommand("status").setExecutor(statusManager);

        classManager.registerRecipes();

        // 5분마다 (6000틱) 주기적으로 플레이어 캐시 및 데이터 저장
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                if (classManager != null) {
                    classManager.saveAllProfiles();
                }
            }
        }.runTaskTimer(this, 6000L, 6000L);

        getLogger().info("ClassRPG Plugin has been enabled!");
    }

    public ClassAbilityListener getClassAbilityListener() { return classAbilityListener; }
    public MageAbilityListener getMageAbilityListener() { return mageAbilityListener; }
    public ClassManager getClassManager() { return classManager; }
    public JobGuiListener getJobGuiListener() { return jobGuiListener; }

    @Override
    public void onDisable() {
        rollingPlayers.clear();
        if (bossManager != null) {
            bossManager.cleanup();
        }
        if (classManager != null) {
            classManager.saveAllProfiles();
        }
        getLogger().info("ClassRPG Plugin has been disabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;

        if (command.getName().equalsIgnoreCase("class")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!player.hasPermission("randomclass.admin")) {
                    player.sendMessage(classManager.getMessage("admin.no_permission", "&c권한이 없습니다."));
                    return true;
                }
                classManager.reloadConfigs();

                if (bossManager != null) {
                    bossManager.loadConfig();
                }

                sender.sendMessage(classManager.getMessage("admin.reload_success", "&aClassRPG 설정 및 데이터가 성공적으로 리로드되었습니다!"));
                return true;
            }

            // 관리자용 직업 초기화 기능 추가
            if (args.length > 1 && args[0].equalsIgnoreCase("reset")) {
                if (!player.hasPermission("randomclass.admin")) {
                    player.sendMessage(classManager.getMessage("admin.no_permission", "&c권한이 없습니다."));
                    return true;
                }
                Player target = org.bukkit.Bukkit.getPlayer(args[1]);
                if (target == null) {
                    player.sendMessage(classManager.getMessage("admin.player_not_found", "&c플레이어를 찾을 수 없습니다."));
                    return true;
                }
                classManager.setPlayerClass(target, PlayerClass.NONE);
                classManager.applyClassBuffs(target);
                target.sendMessage(classManager.getMessage("admin.reset_by_admin", "&e관리자에 의해 직업이 초기화되었습니다."));
                player.sendMessage(classManager.getMessage("admin.reset_success", "&a{player}님의 직업을 초기화했습니다.")
                        .replace("{player}", target.getName()));
                return true;
            }

            // 보스 관리 명령어
            if (args.length > 1 && args[0].equalsIgnoreCase("boss")) {
                if (!player.hasPermission("randomclass.admin")) {
                    player.sendMessage(classManager.getMessage("admin.no_permission", "&c권한이 없습니다."));
                    return true;
                }
                
                if (args[1].equalsIgnoreCase("spawn")) {
                    bossManager.spawnBoss();
                    player.sendMessage(ChatColor.GREEN + "보스를 스폰했습니다.");
                } else if (args[1].equalsIgnoreCase("remove")) {
                    bossManager.cleanup();
                    player.sendMessage(ChatColor.YELLOW + "보스를 제거했습니다.");
                } else {
                    player.sendMessage(ChatColor.RED + "사용법: /class boss <spawn|remove>");
                }
                return true;
            }

            // 아이템 지급 명령어
            if (args.length > 0) {
                String sub = args[0].toLowerCase();
                if (sub.equals("levelupbook") || sub.equals("leveluppiece") || sub.equals("awakening") || sub.equals("invensave")) {
                    if (!player.hasPermission("randomclass.admin")) {
                        player.sendMessage(classManager.getMessage("admin.no_permission", "&c권한이 없습니다."));
                        return true;
                    }

                    int amount = 1;
                    if (args.length > 1) {
                        try {
                            amount = Integer.parseInt(args[1]);
                        } catch (NumberFormatException e) {
                            player.sendMessage(ChatColor.RED + "올바른 수량을 입력해주세요.");
                            return true;
                        }
                    }

                    ItemStack itemToGive = null;
                    String itemName = "";
                    if (sub.equals("levelupbook")) {
                        itemToGive = classManager.createLevelUpTicket();
                        itemName = "직업 레벨 상승권";
                    } else if (sub.equals("leveluppiece")) {
                        itemToGive = classManager.createTornTicket();
                        itemName = "직업 레벨 상승권 조각";
                    } else if (sub.equals("awakening")) {
                        itemToGive = classManager.createAwakeningTicket();
                        itemName = "각성권";
                    } else if (sub.equals("invensave")) {
                        itemToGive = classManager.createInventorySaveTicket();
                        itemName = "인벤토리 세이브권";
                    }

                    if (itemToGive != null) {
                        itemToGive.setAmount(amount);
                        player.getInventory().addItem(itemToGive);
                        player.sendMessage(ChatColor.GREEN + itemName + " " + amount + "개를 지급했습니다.");
                    }
                    return true;
                }
            }

            PlayerClass pClass = classManager.getPlayerClass(player);
            int level = classManager.getPlayerClassLevel(player);
            player.sendMessage(classManager.getMessage("job.current_job", "&6현재 직업: &f{job}")
                    .replace("{job}", pClass.getDisplayName()) + " (Lv." + level + ")");
            return true;
        }

        if (command.getName().equalsIgnoreCase("job")) {
            PlayerClass currentClass = classManager.getPlayerClass(player);

            if (currentClass != PlayerClass.NONE) {
                jobGuiListener.openJobGui(player);
                return true;
            }

            if (rollingPlayers.contains(player.getUniqueId())) {
                player.sendMessage(classManager.getMessage("job.already_rolling", "&c이미 직업을 뽑는 중입니다!"));
                return true;
            }

            rollingPlayers.add(player.getUniqueId());
            new BukkitRunnable() {
                int count = 3;

                @Override
                public void run() {
                    if (!player.isOnline()) {
                        rollingPlayers.remove(player.getUniqueId());
                        this.cancel();
                        return;
                    }
                    if (count > 0) {
                        player.sendTitle(ChatColor.YELLOW + String.valueOf(count), 
                            classManager.getMessage("job.rolling_subtitle", "&a직업을 뽑는 중..."), 10, 20, 10);
                        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                        count--;
                    } else {
                        this.cancel();
                        rollingPlayers.remove(player.getUniqueId());
                        PlayerClass newClass = classManager.assignRandomClass(player);
                        player.sendTitle(ChatColor.GOLD + newClass.getDisplayName(), 
                            classManager.getMessage("job.assigned_subtitle", "&f직업이 결정되었습니다!"), 10, 60, 20);
                        player.sendMessage(classManager.getMessage("job.assigned_message", "&a축하합니다! 당신의 직업은 &f{job}&a 입니다!")
                                .replace("{job}", newClass.getDisplayName()));
                        player.setGameMode(GameMode.SURVIVAL);
                        classManager.applyClassBuffs(player);

                        org.bukkit.entity.Firework fw = (org.bukkit.entity.Firework) player.getWorld().spawnEntity(player.getLocation(), org.bukkit.entity.EntityType.FIREWORK_ROCKET);
                        FireworkMeta fwm = fw.getFireworkMeta();
                        fwm.addEffect(FireworkEffect.builder().withColor(Color.YELLOW, Color.ORANGE).withFlicker().with(org.bukkit.FireworkEffect.Type.BALL_LARGE).build());
                        fwm.setPower(1);
                        fw.setFireworkMeta(fwm);
                    }
                }
            }.runTaskTimer(this, 0L, 20L);

            return true;
        }
        return false;
    }

    @Override
    public java.util.List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        java.util.List<String> completions = new java.util.ArrayList<>();
        if (command.getName().equalsIgnoreCase("class")) {
            if (args.length == 1) {
                completions.add("reload");
                if (sender.hasPermission("randomclass.admin")) {
                    completions.add("reset");
                    completions.add("boss");
                    completions.add("levelupbook");
                    completions.add("leveluppiece");
                    completions.add("awakening");
                    completions.add("invensave");
                }
            } else if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
                for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                    if (p != null) completions.add(p.getName());
                }
            } else if (args.length == 2 && args[0].equalsIgnoreCase("boss")) {
                if (sender.hasPermission("randomclass.admin")) {
                    completions.add("spawn");
                    completions.add("remove");
                }
            }
        }
        return completions;
    }
}
