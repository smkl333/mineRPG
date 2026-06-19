package com.example.randomclass.listeners;

import com.example.randomclass.ClassManager;
import com.example.randomclass.PlayerClass;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;

public class LevelUpTicketListener implements Listener {

    private final ClassManager classManager;
    private final String GUI_TITLE = ChatColor.DARK_PURPLE + "직업 레벨 상승권 사용";
    private final String AWAKENING_GUI_TITLE = ChatColor.GOLD + "각성권 사용: 대상 선택";
    private final String CONFIRM_GUI_TITLE = ChatColor.RED + "정말 사용하시겠습니까?";

    public LevelUpTicketListener(ClassManager classManager) {
        this.classManager = classManager;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Player player = event.getPlayer();
            ItemStack item = player.getInventory().getItemInMainHand();

            if (item != null && item.hasItemMeta()) {
                // 직업 레벨 상승권
                if (item.getItemMeta().getPersistentDataContainer().has(classManager.getLevelUpTicketKey(),
                        PersistentDataType.BYTE)) {
                    event.setCancelled(true);
                    openLevelUpGui(player);
                }
                // 각성권
                else if (item.getItemMeta().getPersistentDataContainer().has(classManager.getAwakeningTicketKey(),
                        PersistentDataType.BYTE)) {
                    event.setCancelled(true);
                    if (classManager.getHighestClassLevel(player) < 10) {
                        player.sendMessage(ChatColor.RED + "각성권은 최소 한 개의 직업이 10레벨을 달성해야 사용할 수 있습니다!");
                        return;
                    }
                    openAwakeningSelectGui(player);
                }
            }
        }
    }

    private void openLevelUpGui(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, GUI_TITLE);

        ItemStack bg = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta bgMeta = bg.getItemMeta();
        bgMeta.setDisplayName(" ");
        bg.setItemMeta(bgMeta);
        for (int i = 0; i < 27; i++)
            inv.setItem(i, bg);

        inv.setItem(10, createJobIcon(PlayerClass.WARRIOR));
        inv.setItem(13, createJobIcon(PlayerClass.MAGE));
        inv.setItem(16, createJobIcon(PlayerClass.ADVENTURER));

        player.openInventory(inv);
    }

    private void openAwakeningSelectGui(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, AWAKENING_GUI_TITLE);

        ItemStack bg = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta bgMeta = bg.getItemMeta();
        bgMeta.setDisplayName(" ");
        bg.setItemMeta(bgMeta);
        for (int i = 0; i < 27; i++)
            inv.setItem(i, bg);

        inv.setItem(10, createAwakeningIcon(player, PlayerClass.WARRIOR));
        inv.setItem(13, createAwakeningIcon(player, PlayerClass.MAGE));
        inv.setItem(16, createAwakeningIcon(player, PlayerClass.ADVENTURER));

        // 공통 각성 버튼 (인덱스 22)
        ItemStack commonAwk = new ItemStack(Material.NETHER_STAR);
        ItemMeta commonMeta = commonAwk.getItemMeta();
        commonMeta.setDisplayName(ChatColor.GOLD + "공통 각성");
        commonMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "모든 직업이 공통으로 사용할 수 있는",
                ChatColor.GRAY + "각성 능력을 해금합니다.",
                ChatColor.YELLOW + "클릭하여 공통 각성 메뉴를 엽니다."
        ));
        commonMeta.getPersistentDataContainer().set(classManager.getGuiTargetClassKey(), PersistentDataType.STRING, "COMMON");
        commonAwk.setItemMeta(commonMeta);
        inv.setItem(22, commonAwk);

        player.openInventory(inv);
    }

    private ItemStack createAwakeningIcon(Player player, PlayerClass pClass) {
        Material mat = Material.BARRIER;
        switch (pClass) {
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
        int level = classManager.getStoredLevel(player, pClass);
        boolean canAwaken = level >= 10;

        int stage = 0;
        if (pClass == PlayerClass.WARRIOR) {
            if (classManager.isWarriorGeumgang(level)) stage++;
            if (classManager.isWarriorLifesteal(level)) stage++;
        } else if (pClass == PlayerClass.MAGE) {
            if (classManager.isMageBlackHole(level)) stage++;
            if (classManager.isMageSurge(level)) stage++;
        } else if (pClass == PlayerClass.ADVENTURER) {
            if (classManager.isAdventurerValor(level)) stage++;
            if (classManager.isAdventurerSurvival(level)) stage++;
        }

        meta.setDisplayName((canAwaken ? ChatColor.GOLD : ChatColor.GRAY) + pClass.getDisplayName() + " 각성");
        meta.setLore(Arrays.asList(
                ChatColor.YELLOW + "현재 각성한 개수: " + stage,
                canAwaken ? ChatColor.YELLOW + "클릭하여 각성 메뉴를 엽니다." : ChatColor.RED + "해당 직업 10레벨 달성 시 가능"));
        meta.getPersistentDataContainer().set(classManager.getGuiTargetClassKey(), PersistentDataType.STRING,
                pClass.name());
        item.setItemMeta(meta);
        return item;
    }

    private void openConfirmGui(Player player, PlayerClass targetClass) {
        Inventory inv = Bukkit.createInventory(null, 9, CONFIRM_GUI_TITLE);

        // 배경
        ItemStack bg = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta bgMeta = bg.getItemMeta();
        bgMeta.setDisplayName(" ");
        bg.setItemMeta(bgMeta);
        for (int i = 0; i < 9; i++)
            inv.setItem(i, bg);

        // 예 (Yes) - 3번 슬롯
        ItemStack yes = new ItemStack(Material.LIME_WOOL);
        ItemMeta yesMeta = yes.getItemMeta();
        yesMeta.setDisplayName(ChatColor.GREEN + "사용하기 (Yes)");
        yesMeta.setLore(Arrays.asList(ChatColor.GRAY + targetClass.getDisplayName() + " 레벨을 올립니다."));
        yesMeta.getPersistentDataContainer().set(classManager.getGuiTargetClassKey(), PersistentDataType.STRING,
                targetClass.name());
        yes.setItemMeta(yesMeta);
        inv.setItem(3, yes);

        // 정보 - 4번 슬롯
        ItemStack info = createJobIcon(targetClass);
        inv.setItem(4, info);

        // 아니오 (No) - 5번 슬롯
        ItemStack no = new ItemStack(Material.RED_WOOL);
        ItemMeta noMeta = no.getItemMeta();
        noMeta.setDisplayName(ChatColor.RED + "취소하기 (No)");
        no.setItemMeta(noMeta);
        inv.setItem(5, no);

        player.openInventory(inv);
    }

    private ItemStack createJobIcon(PlayerClass pClass) {
        Material mat = Material.BARRIER;
        switch (pClass) {
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
        meta.setDisplayName(ChatColor.AQUA + pClass.getDisplayName() + " 레벨업");
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + "클릭 시 레벨 상승권을 소모하여",
                ChatColor.GRAY + "이 직업의 레벨을 1 올립니다."));
        meta.getPersistentDataContainer().set(classManager.getGuiTargetClassKey(), PersistentDataType.STRING,
                pClass.name());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        boolean isMainGui = title.equals(GUI_TITLE);
        boolean isAwakeningGui = title.equals(AWAKENING_GUI_TITLE);
        boolean isConfirmGui = title.equals(CONFIRM_GUI_TITLE);

        if (!isMainGui && !isAwakeningGui && !isConfirmGui)
            return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player))
            return;

        Player player = (Player) event.getWhoClicked();
        ItemStack clickedItem = event.getCurrentItem();

        if (clickedItem == null || !clickedItem.hasItemMeta())
            return;

        if (isMainGui) {
            String className = clickedItem.getItemMeta().getPersistentDataContainer()
                    .get(classManager.getGuiTargetClassKey(), PersistentDataType.STRING);
            if (className != null) {
                try {
                    PlayerClass targetClass = PlayerClass.valueOf(className);
                    openConfirmGui(player, targetClass);
                } catch (Exception ignored) {
                }
            }
        } else if (isAwakeningGui) {
            String className = clickedItem.getItemMeta().getPersistentDataContainer()
                    .get(classManager.getGuiTargetClassKey(), PersistentDataType.STRING);
            if (className != null) {
                com.example.randomclass.RandomClassPlugin plugin = (com.example.randomclass.RandomClassPlugin) Bukkit
                        .getPluginManager().getPlugin("ClassRPG");
                if (plugin == null) return;

                if (className.equals("COMMON")) {
                    plugin.getJobGuiListener().openCommonAwakeningMenu(player);
                    return;
                }

                try {
                    PlayerClass targetClass = PlayerClass.valueOf(className);
                    if (classManager.getStoredLevel(player, targetClass) < 10) {
                        player.sendMessage(ChatColor.RED + targetClass.getDisplayName() + " 직업이 아직 10레벨이 아닙니다!");
                        return;
                    }
                    plugin.getJobGuiListener().openAwakeningMenu(player, targetClass);
                } catch (Exception ignored) {
                }
            }
        } else if (isConfirmGui) {
            if (clickedItem.getType() == Material.LIME_WOOL) {
                String className = clickedItem.getItemMeta().getPersistentDataContainer()
                        .get(classManager.getGuiTargetClassKey(), PersistentDataType.STRING);
                if (className != null) {
                    try {
                        PlayerClass targetClass = PlayerClass.valueOf(className);
                        processLevelUp(player, targetClass);
                    } catch (Exception ignored) {
                    }
                }
            } else if (clickedItem.getType() == Material.RED_WOOL) {
                openLevelUpGui(player); // 취소 시 다시 메인 GUI로
            }
        }
    }

    private void processLevelUp(Player player, PlayerClass targetClass) {
        int currentLevel = classManager.getStoredLevel(player, targetClass);
        if (currentLevel >= 10) {
            player.sendMessage(classManager.getMessage("job.max_level", "&c{job} 직업은 이미 최대 레벨(10)입니다.")
                    .replace("{job}", targetClass.getDisplayName()));
            player.closeInventory();
            return;
        }

        // 인벤토리 전체에서 상승권 1개 탐색 후 제거
        boolean consumed = false;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.hasItemMeta()
                    && stack.getItemMeta().getPersistentDataContainer().has(classManager.getLevelUpTicketKey(),
                            PersistentDataType.BYTE)) {
                stack.setAmount(stack.getAmount() - 1);
                consumed = true;
                break;
            }
        }
        if (!consumed) {
            player.sendMessage(classManager.getMessage("job.no_ticket", "&c직업 레벨 상승권이 인벤토리에 없습니다."));
            player.closeInventory();
            return;
        }

        int nextLevel = currentLevel + 1;
        classManager.setStoredLevel(player, targetClass, nextLevel);
        classManager.playLevelUpEffects(player, targetClass, nextLevel);

        // 공통 패시브 및 직업 아이템을 갱신하기 위해 항상 호출
        classManager.applyClassBuffs(player);

        player.sendMessage(
                classManager.getMessage("job.ticket_use_success", "&d[직업 레벨 상승권] &a{job} 직업이 Lv.{level}(으)로 올랐습니다!")
                        .replace("{job}", targetClass.getDisplayName())
                        .replace("{level}", String.valueOf(nextLevel)));
        player.closeInventory();
    }
}
