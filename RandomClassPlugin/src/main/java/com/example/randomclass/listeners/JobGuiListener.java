package com.example.randomclass.listeners;

import com.example.randomclass.ClassManager;
import com.example.randomclass.PlayerClass;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class JobGuiListener implements Listener {

    private final ClassManager classManager;

    private final String MAIN_TITLE = ChatColor.DARK_BLUE + "직업 선택 및 관리";
    private final String SUB_TITLE_PREFIX = ChatColor.DARK_GREEN + "직업 관리: ";
    private final String CONFIRM_TITLE_PREFIX = ChatColor.DARK_RED + "업그레이드 확인: ";

    public JobGuiListener(ClassManager classManager) {
        this.classManager = classManager;
    }

    // 메인 메뉴: 3개의 직업 아이콘
    public void openJobGui(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, MAIN_TITLE);

        PlayerClass currentClass = classManager.getPlayerClass(player);

        ItemStack bg = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++)
            inv.setItem(i, bg);

        inv.setItem(10, createJobPreviewItem(player, PlayerClass.WARRIOR, currentClass));
        inv.setItem(13, createJobPreviewItem(player, PlayerClass.MAGE, currentClass));
        inv.setItem(16, createJobPreviewItem(player, PlayerClass.ADVENTURER, currentClass));

        // 허기 리셋 (슬롯 22) - 활성화된 경우에만 표시
        if (classManager.isHungerSystemEnabled()) {
            ItemStack resetHungerBtn = new ItemStack(Material.APPLE);
            ItemMeta resetMeta = resetHungerBtn.getItemMeta();
            resetMeta.setDisplayName(ChatColor.GOLD + "허기 진행도 초기화");
            double multiplier = classManager.getHungerMultiplier(player);
            int resetCost = classManager.getHungerResetCost();
            resetMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + "현재 허기 배율: " + ChatColor.YELLOW + String.format("%.2f", multiplier) + "배",
                    ChatColor.RED + "비용: " + resetCost + " 레벨",
                    ChatColor.GRAY + "허기 배율을 1.0배로 초기화합니다."));
            resetHungerBtn.setItemMeta(resetMeta);
            inv.setItem(22, resetHungerBtn);
        }

        player.openInventory(inv);
    }

    // 하위 메뉴: 특정 직업 관리 (전직 / 업그레이드 선택)
    private void openSubMenu(Player player, PlayerClass targetClass) {
        Inventory inv = Bukkit.createInventory(null, 27, SUB_TITLE_PREFIX + targetClass.getDisplayName());

        ItemStack bg = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++)
            inv.setItem(i, bg);

        PlayerClass currentClass = classManager.getPlayerClass(player);
        int storedLevel = classManager.getStoredLevel(player, targetClass);
        boolean isCurrent = (targetClass == currentClass);

        // 1. 전직 버튼 (이미 해당 직업이면 활성화X)
        if (!isCurrent) {
            int switchCost = classManager.getJobChangeCost();
            ItemStack switchBtn = createItem(Material.ENDER_PEARL,
                    ChatColor.AQUA + targetClass.getDisplayName() + " (으)로 전직", targetClass);
            ItemMeta meta = switchBtn.getItemMeta();
            meta.setLore(Arrays.asList(ChatColor.RED + "비용: " + switchCost + " 레벨", ChatColor.GRAY + "해당 직업으로 변경합니다."));
            switchBtn.setItemMeta(meta);
            inv.setItem(11, switchBtn);
        } else {
            inv.setItem(11, createItem(Material.LIME_STAINED_GLASS_PANE, ChatColor.GREEN + "현재 사용 중인 직업"));
        }

        // 2. 업그레이드 버튼
        if (storedLevel < 10) {
            int cost = classManager.getUpgradeCost(storedLevel);
            ItemStack upBtn = createItem(Material.EMERALD, ChatColor.GREEN + targetClass.getDisplayName() + " 업그레이드", targetClass);
            ItemMeta meta = upBtn.getItemMeta();
            meta.setLore(Arrays.asList(
                    ChatColor.WHITE + "Lv." + storedLevel + " -> Lv." + (storedLevel + 1),
                    ChatColor.RED + "비용: " + cost + " 레벨",
                    ChatColor.GRAY + "클릭 시 확인 메뉴로 이동합니다."));
            upBtn.setItemMeta(meta);
            inv.setItem(13, upBtn);
        } else {
            inv.setItem(13, createItem(Material.BEDROCK, ChatColor.RED + "최대 레벨 도달"));
        }

        // 3. 뒤로가기
        inv.setItem(15, createItem(Material.BARRIER, ChatColor.RED + "뒤로가기"));

        player.openInventory(inv);
    }

    // 최종 확인 메뉴: YES / NO
    private void openConfirmMenu(Player player, PlayerClass targetClass) {
        Inventory inv = Bukkit.createInventory(null, 27, CONFIRM_TITLE_PREFIX + targetClass.getDisplayName());

        ItemStack bg = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++)
            inv.setItem(i, bg);

        int level = classManager.getStoredLevel(player, targetClass);
        int cost = classManager.getUpgradeCost(level);

        ItemStack yes = createItem(Material.LIME_WOOL, ChatColor.GREEN + "네, 업그레이드 하겠습니다.", targetClass);
        ItemMeta yesMeta = yes.getItemMeta();
        yesMeta.setLore(Arrays.asList(ChatColor.RED + "비용: " + cost + " 레벨"));
        yes.setItemMeta(yesMeta);
        inv.setItem(11, yes);

        inv.setItem(13,
                createItem(Material.BOOK, ChatColor.GOLD + targetClass.getDisplayName() + " Lv." + (level + 1)));

        inv.setItem(15, createItem(Material.RED_WOOL, ChatColor.RED + "아니요, 취소하겠습니다.", targetClass));

        player.openInventory(inv);
    }

    private ItemStack createJobPreviewItem(Player player, PlayerClass targetClass, PlayerClass currentClass) {
        Material mat = Material.BARRIER;
        List<String> lore = new ArrayList<>();
        switch (targetClass) {
            case WARRIOR:
                mat = Material.IRON_SWORD;
                break;
            case MAGE:
                mat = Material.BLAZE_ROD;
                break;
            case ADVENTURER:
                mat = Material.COMPASS;
                break;
            default:
                break;
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        boolean isCurrent = (targetClass == currentClass);
        int storedLevel = classManager.getStoredLevel(player, targetClass);

        meta.setDisplayName((isCurrent ? ChatColor.GREEN : ChatColor.YELLOW) + targetClass.getDisplayName() + " (Lv."
                + storedLevel + ")");

        lore.add(ChatColor.GRAY + "클릭하여 이 직업을 관리합니다.");
        if (isCurrent)
            lore.add(ChatColor.GREEN + "[현재 직업]");
        lore.add("");
        lore.add(ChatColor.AQUA + "[현재 활성화된 효과]");

        int highestLevel = classManager.getHighestClassLevel(player);
        if (highestLevel >= 7) {
            lore.add(ChatColor.WHITE + "- 야간 투시 (최고 레벨 7 이상)");
        }

        switch (targetClass) {
            case WARRIOR:
                if (storedLevel >= 4)
                    lore.add(ChatColor.WHITE + "- 추가 체력: " + ChatColor.RED + "+"
                            + (storedLevel >= 10 ? "3" : (storedLevel >= 7 ? "2" : "1")) + "칸");
                if (storedLevel >= 4)
                    lore.add(ChatColor.WHITE + "- 신속 I");
                if (storedLevel >= 3)
                    lore.add(ChatColor.WHITE + "- 힘 " + (storedLevel >= 9 ? "III" : (storedLevel >= 6 ? "II" : "I")));
                if (storedLevel >= 8)
                    lore.add(ChatColor.WHITE + "- 저항 I");
                if (storedLevel >= 10) {
                    lore.add(ChatColor.WHITE + "- 재생 I");
                    lore.add(ChatColor.GOLD + "- 타격 시 몬스터 둔화 II");
                    lore.add(ChatColor.GOLD + "- 더블 점프: 공중에서 한 번 더 도약");
                }
                if (storedLevel >= 5)
                    lore.add(ChatColor.GOLD + "- 타격 시 몬스터 이동속도 감소");
                lore.add("");
                lore.add(ChatColor.RED + "[제한] 낚시 불가");
                break;
            case MAGE:
                double tntMultiplier = classManager.getMageTntMultiplier(storedLevel);
                int tntPct = (int) (tntMultiplier * 100);
                
                double cdMultiplier = classManager.getMageCooldownMultiplier(storedLevel);
                int cdReduction = (int) ((1.0 - cdMultiplier) * 100);

                lore.add(ChatColor.WHITE + "- 지팡이: 마법 TNT 투척 (" + ChatColor.YELLOW + "데미지 " + tntPct + "%" + ChatColor.WHITE + ")");
                lore.add(ChatColor.WHITE + "- 마법서: 전방 공간 도약");
                if (cdReduction > 0) {
                    lore.add(ChatColor.WHITE + "- 스킬 쿨타임: " + ChatColor.GREEN + "-" + cdReduction + "%");
                }
                lore.add(ChatColor.GRAY + "  (소환한 TNT 데미지에 면역)");
                lore.add("");
                lore.add(ChatColor.RED + "[제한] 낚시 불가");
                break;
            case ADVENTURER:
                if (storedLevel < 5)
                    lore.add(ChatColor.DARK_GRAY + "- 약화 I (5레벨에 제거됨)");
                lore.add(ChatColor.WHITE + "- 재생 포션: 우클릭 시 주변 재생 필드 전개");
                lore.add(ChatColor.WHITE + "- 특수 채집: 판자/작물 추가 드랍");
                lore.add(ChatColor.WHITE + "- 농작물/사탕수수 수확 시 경험치 획득");
                lore.add(ChatColor.WHITE + "- 추가 체력: " + ChatColor.RED + "+" + (storedLevel / 2) + "칸");
                lore.add(ChatColor.WHITE + "- 경험치 획득량: " + ChatColor.GREEN + "+" + (storedLevel * 10) + "%");
                if (storedLevel >= 5)
                    lore.add(ChatColor.WHITE + "- 저항 " + (storedLevel >= 10 ? "II" : "I"));
                if (storedLevel >= 10) {
                    lore.add(ChatColor.WHITE + "- 포만감 (배고픔 소모 없음)");
                    lore.add(ChatColor.GOLD + "- 위기 시 스폰지점 자동 텔레포트");
                }
                if (storedLevel >= 2)
                    lore.add(ChatColor.WHITE + "- 신속 " + (storedLevel >= 9 ? "III" : (storedLevel >= 6 ? "II" : "I")));
                break;
            default:
                break;
        }

        meta.setLore(lore);
        meta.getPersistentDataContainer().set(classManager.getGuiTargetClassKey(), org.bukkit.persistence.PersistentDataType.STRING, targetClass.name());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createItem(Material mat, String name) {
        return createItem(mat, name, null);
    }

    private ItemStack createItem(Material mat, String name, PlayerClass targetClass) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (targetClass != null) {
            meta.getPersistentDataContainer().set(classManager.getGuiTargetClassKey(), org.bukkit.persistence.PersistentDataType.STRING, targetClass.name());
        }
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (title == null)
            return;

        // 우리 플러그인의 GUI인 경우에만 처리
        if (!title.equals(MAIN_TITLE) && !title.startsWith(SUB_TITLE_PREFIX)
                && !title.startsWith(CONFIRM_TITLE_PREFIX)) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player))
            return;
        Player player = (Player) event.getWhoClicked();
        event.setCancelled(true);
        int slot = event.getRawSlot();

        // 1. 메인 메뉴 처리
        if (title.equals(MAIN_TITLE)) {
            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem != null && clickedItem.hasItemMeta()) {
                String targetClassName = clickedItem.getItemMeta().getPersistentDataContainer().get(classManager.getGuiTargetClassKey(), org.bukkit.persistence.PersistentDataType.STRING);
                if (targetClassName != null) {
                    try {
                        PlayerClass targetClass = PlayerClass.valueOf(targetClassName);
                        openSubMenu(player, targetClass);
                        return;
                    } catch (Exception e) {}
                }
            }

            if (slot == 22 && classManager.isHungerSystemEnabled()) {
                // 허기 리셋
                int cost = classManager.getHungerResetCost();
                if (player.getLevel() < cost) {
                    player.sendMessage(classManager.getMessage("job.level_low", "&c레벨이 부족합니다!"));
                    return;
                }
                player.setLevel(player.getLevel() - cost);
                classManager.setHungerBaseTicks(player, player.getStatistic(Statistic.PLAY_ONE_MINUTE));
                player.sendMessage(ChatColor.GREEN + "허기 진행도가 초기화되었습니다!");
                player.closeInventory();
            }
        }
        // 2. 서브 메뉴 처리 (직업 관리)
        else if (title.startsWith(SUB_TITLE_PREFIX)) {
            ItemStack clickedItem = event.getCurrentItem();
            PlayerClass targetClass = null;
            if (clickedItem != null && clickedItem.hasItemMeta()) {
                String targetClassName = clickedItem.getItemMeta().getPersistentDataContainer().get(classManager.getGuiTargetClassKey(), org.bukkit.persistence.PersistentDataType.STRING);
                if (targetClassName != null) {
                    try { targetClass = PlayerClass.valueOf(targetClassName); } catch (Exception e) {}
                }
            }

            if (slot == 11 && targetClass != null) {
                // 전직
                if (classManager.getPlayerClass(player) == targetClass)
                    return;
                int cost = classManager.getJobChangeCost();
                if (player.getLevel() < cost) {
                    player.sendMessage(ChatColor.RED + "레벨이 부족합니다!");
                    return;
                }
                player.setLevel(player.getLevel() - cost);
                classManager.setPlayerClass(player, targetClass);
                player.sendMessage(classManager.getMessage("job.change_success", "&a{job}(으)로 전직했습니다!")
                        .replace("{job}", targetClass.getDisplayName()));
                classManager.applyClassBuffs(player);
                openJobGui(player);
            } else if (slot == 13 && targetClass != null) {
                // 업그레이드 클릭 시 확인 메뉴로
                int level = classManager.getStoredLevel(player, targetClass);
                if (level >= 10)
                    return;
                openConfirmMenu(player, targetClass);
            } else if (slot == 15) {
                openJobGui(player);
            }
        }
        // 3. 확인 메뉴 처리
        else if (title.startsWith(CONFIRM_TITLE_PREFIX)) {
            ItemStack clickedItem = event.getCurrentItem();
            PlayerClass targetClass = null;
            if (clickedItem != null && clickedItem.hasItemMeta()) {
                String targetClassName = clickedItem.getItemMeta().getPersistentDataContainer().get(classManager.getGuiTargetClassKey(), org.bukkit.persistence.PersistentDataType.STRING);
                if (targetClassName != null) {
                    try { targetClass = PlayerClass.valueOf(targetClassName); } catch (Exception e) {}
                }
            }

            if (slot == 11 && targetClass != null) {
                // YES
                int level = classManager.getStoredLevel(player, targetClass);
                int cost = classManager.getUpgradeCost(level);
                if (player.getLevel() < cost) {
                    player.sendMessage(classManager.getMessage("job.level_low", "&c레벨이 부족합니다!"));
                    openSubMenu(player, targetClass);
                    return;
                }
                player.setLevel(player.getLevel() - cost);

                int nextLevel = level + 1;
                classManager.setStoredLevel(player, targetClass, nextLevel);
                classManager.playLevelUpEffects(player, nextLevel);

                classManager.applyClassBuffs(player);
                openSubMenu(player, targetClass);
            } else if (slot == 15 && targetClass != null) {
                // NO
                openSubMenu(player, targetClass);
            }
        }
    }
}
