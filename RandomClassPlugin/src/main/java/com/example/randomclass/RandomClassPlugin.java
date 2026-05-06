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
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitRunnable;

public class RandomClassPlugin extends JavaPlugin {

    private ClassManager classManager;
    private JobGuiListener jobGuiListener;
    private ClassAbilityListener classAbilityListener;
    private MageAbilityListener mageAbilityListener;
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

        classManager.registerRecipes();

        getLogger().info("ClassRPG Plugin has been enabled!");
    }

    public ClassAbilityListener getClassAbilityListener() { return classAbilityListener; }
    public MageAbilityListener getMageAbilityListener() { return mageAbilityListener; }

    @Override
    public void onDisable() {
        rollingPlayers.clear();
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
                
                for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                    if (p != null) classManager.applyClassBuffs(p);
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
                if (sender.hasPermission("randomclass.admin")) completions.add("reset");
            } else if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
                for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                    if (p != null) completions.add(p.getName());
                }
            }
        }
        return completions;
    }
}
