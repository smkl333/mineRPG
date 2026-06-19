package com.example.randomclass.managers;

import com.example.randomclass.ClassManager;
import com.example.randomclass.PlayerClass;
import com.example.randomclass.RandomClassPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scoreboard.*;

import java.util.ArrayList;
import java.util.List;

public class StatusDisplayManager implements CommandExecutor, org.bukkit.event.Listener {

    private final RandomClassPlugin plugin;
    private final ClassManager classManager;
    private final java.util.Map<java.util.UUID, List<String>> lastSentLines = new java.util.HashMap<>();

    public StatusDisplayManager(RandomClassPlugin plugin, ClassManager classManager) {
        this.plugin = plugin;
        this.classManager = classManager;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        // 실시간 갱신 태스크 시작 (1초마다)
        startUpdateTask();
    }

    @org.bukkit.event.EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        lastSentLines.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player))
            return true;
        Player player = (Player) sender;

        boolean enabled = classManager.toggleStatusDisplay(player);
        if (enabled) {
            player.sendMessage(classManager.getMessage("system.status_enabled", "&a[상태창] 실시간 상태 정보 표시를 활성화했습니다."));
        } else {
            player.sendMessage(classManager.getMessage("system.status_disabled", "&c[상태창] 실시간 상태 정보 표시를 비활성화했습니다."));
            // 스코어보드 제거
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            lastSentLines.remove(player.getUniqueId());
        }
        return true;
    }

    private void startUpdateTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player != null && player.isOnline()) {
                    if (classManager.isStatusDisplayEnabled(player.getUniqueId())) {
                        updateScoreboard(player);
                    }
                }
            }
        }, 0L, 20L);
    }

    private void updateScoreboard(Player player) {
        if (player == null || !player.isOnline())
            return;
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        Scoreboard board = player.getScoreboard();

        boolean isNewBoard = false;
        if (board == manager.getMainScoreboard()) {
            board = manager.getNewScoreboard();
            isNewBoard = true;
        }

        Objective obj = board.getObjective("status");
        if (obj == null) {
            obj = board.registerNewObjective("status", Criteria.DUMMY, ChatColor.AQUA + "[ 상태창 ]");
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        List<String> lines = new ArrayList<>();
        PlayerClass pClass = classManager.getPlayerClass(player);
        int level = classManager.getPlayerClassLevel(player);

        lines.add(ChatColor.WHITE + "직업: " + ChatColor.YELLOW + pClass.getDisplayName());
        lines.add(ChatColor.WHITE + "레벨: " + ChatColor.YELLOW + "Lv." + level);
        
        org.bukkit.Location loc = player.getLocation();
        lines.add(ChatColor.WHITE + "좌표: " + ChatColor.GRAY + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
        
        lines.add(ChatColor.GRAY + " "); // 공백

        boolean hasEffects = false;
        for (PotionEffect effect : player.getActivePotionEffects()) {
            String name = getPotionName(effect.getType());
            int duration = effect.getDuration() / 20;

            String amplifier = "";
            if (effect.getAmplifier() > 0) {
                amplifier = " " + (effect.getAmplifier() + 1);
            }

            // 1시간(3600초) 미만일 때만 시간 표시
            if (duration > 0 && duration < 3600) {
                String timeStr = String.format("%02d:%02d", duration / 60, duration % 60);
                lines.add(ChatColor.WHITE + name + amplifier + ": " + ChatColor.GRAY + timeStr);
            } else {
                lines.add(ChatColor.WHITE + name + amplifier);
            }
            hasEffects = true;
        }

        // 용맹 커스텀 버프 표시
        if (classManager.hasValorBuff(player)) {
            long currentTime = System.currentTimeMillis();
            long auraEndTime = classManager.getValorAuraEndTime(player); // 시전자의 오우라 종료 시간
            long displayEndTime;

            if (currentTime < auraEndTime) {
                // 시전자 본인인 경우: 전체 오우라 남은 시간 표시
                displayEndTime = auraEndTime;
            } else {
                // 아군(수혜자)이거나, 오우라 종료 후 잔여 버프 시간인 경우: 1초 단위 갱신 시간 표시
                displayEndTime = classManager.getValorBuffEndTime(player);
            }

            long remainingMillis = displayEndTime - currentTime;
            // 0.1초라도 남아있으면 1초로 올림 처리
            int duration = (int) Math.ceil(Math.max(0, remainingMillis) / 1000.0);
            
            String timeStr = String.format("%02d:%02d", duration / 60, duration % 60);
            lines.add(ChatColor.GOLD + "용맹: " + ChatColor.GRAY + timeStr);
            hasEffects = true;
        }

        if (!hasEffects) {
            lines.add(ChatColor.GRAY + "활성화된 효과 없음");
        }

        // 이전 스코어라인 리셋 (깜빡임 최소화)
        List<String> oldLines = lastSentLines.get(player.getUniqueId());
        if (oldLines != null) {
            for (String oldLine : oldLines) {
                board.resetScores(oldLine);
            }
        }

        // 스코어 등록
        int scoreValue = lines.size();
        for (String line : lines) {
            Score score = obj.getScore(line);
            score.setScore(scoreValue--);
        }

        lastSentLines.put(player.getUniqueId(), lines);

        if (isNewBoard) {
            player.setScoreboard(board);
        }
    }

    private String getPotionName(org.bukkit.potion.PotionEffectType type) {
        if (type.equals(org.bukkit.potion.PotionEffectType.SPEED))
            return "신속";
        if (type.equals(org.bukkit.potion.PotionEffectType.SLOWNESS))
            return "구속";
        if (type.equals(org.bukkit.potion.PotionEffectType.HASTE))
            return "성급함";
        if (type.equals(org.bukkit.potion.PotionEffectType.MINING_FATIGUE))
            return "채굴 피로";
        if (type.equals(org.bukkit.potion.PotionEffectType.STRENGTH))
            return "힘";
        if (type.equals(org.bukkit.potion.PotionEffectType.INSTANT_HEALTH))
            return "즉시 치유";
        if (type.equals(org.bukkit.potion.PotionEffectType.INSTANT_DAMAGE))
            return "즉시 피해";
        if (type.equals(org.bukkit.potion.PotionEffectType.JUMP_BOOST))
            return "점프 강화";
        if (type.equals(org.bukkit.potion.PotionEffectType.NAUSEA))
            return "멀미";
        if (type.equals(org.bukkit.potion.PotionEffectType.REGENERATION))
            return "재생";
        if (type.equals(org.bukkit.potion.PotionEffectType.RESISTANCE))
            return "저항";
        if (type.equals(org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE))
            return "화염 저항";
        if (type.equals(org.bukkit.potion.PotionEffectType.WATER_BREATHING))
            return "수중 호흡";
        if (type.equals(org.bukkit.potion.PotionEffectType.INVISIBILITY))
            return "투명";
        if (type.equals(org.bukkit.potion.PotionEffectType.BLINDNESS))
            return "실명";
        if (type.equals(org.bukkit.potion.PotionEffectType.NIGHT_VISION))
            return "야간 투시";
        if (type.equals(org.bukkit.potion.PotionEffectType.HUNGER))
            return "허기";
        if (type.equals(org.bukkit.potion.PotionEffectType.WEAKNESS))
            return "약함";
        if (type.equals(org.bukkit.potion.PotionEffectType.POISON))
            return "독";
        if (type.equals(org.bukkit.potion.PotionEffectType.WITHER))
            return "위더";
        if (type.equals(org.bukkit.potion.PotionEffectType.HEALTH_BOOST))
            return "생명력 강화";
        if (type.equals(org.bukkit.potion.PotionEffectType.ABSORPTION))
            return "흡수";
        if (type.equals(org.bukkit.potion.PotionEffectType.SATURATION))
            return "포화";
        if (type.equals(org.bukkit.potion.PotionEffectType.GLOWING))
            return "발광";
        if (type.equals(org.bukkit.potion.PotionEffectType.LEVITATION))
            return "공중 부양";
        if (type.equals(org.bukkit.potion.PotionEffectType.LUCK))
            return "피해 흡수";
        if (type.equals(org.bukkit.potion.PotionEffectType.UNLUCK))
            return "불운";
        if (type.equals(org.bukkit.potion.PotionEffectType.SLOW_FALLING))
            return "느린 낙하";
        if (type.equals(org.bukkit.potion.PotionEffectType.CONDUIT_POWER))
            return "전달체의 힘";
        if (type.equals(org.bukkit.potion.PotionEffectType.DOLPHINS_GRACE))
            return "돌고래의 가호";
        if (type.equals(org.bukkit.potion.PotionEffectType.BAD_OMEN))
            return "흉조";
        if (type.equals(org.bukkit.potion.PotionEffectType.HERO_OF_THE_VILLAGE))
            return "마을의 영웅";

        // 최신 버전 호환을 위한 toString 기반 처리
        String typeStr = type.toString().toLowerCase();
        if (typeStr.contains(":")) {
            return typeStr.split(":")[1].replace("]", "");
        }
        return typeStr;
    }
}
