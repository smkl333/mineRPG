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
    private final String AWAKENING_TITLE_PREFIX = ChatColor.DARK_PURPLE + "전사 각성: ";
    private final String COMMON_AWAKENING_TITLE = ChatColor.GOLD + "공통 각성";
    private final String JOB_CHANGE_CONFIRM_TITLE_PREFIX = ChatColor.DARK_AQUA + "전직 확인: ";
    private final String AWAKENING_CONFIRM_TITLE_PREFIX = ChatColor.DARK_PURPLE + "각성 해금 확인";

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

        // 허기 리셋 (슬롯 21) - 활성화된 경우에만 표시
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
            inv.setItem(21, resetHungerBtn);
        }

        // 공통 각성 (슬롯 22)
        int highestLevel = classManager.getHighestClassLevel(player);
        boolean canAwaken = highestLevel >= 10;
        ItemStack commonAwakeningBtn = new ItemStack(canAwaken ? Material.NETHERITE_PICKAXE : Material.BARRIER);
        ItemMeta commonMeta = commonAwakeningBtn.getItemMeta();
        commonMeta.setDisplayName((canAwaken ? ChatColor.GOLD : ChatColor.RED) + "공통 각성 능력 해금");
        List<String> commonLore = new ArrayList<>();
        if (canAwaken) {
            commonLore.add(ChatColor.GRAY + "모든 직업에 적용되는 특수한");
            commonLore.add(ChatColor.GRAY + "능력들을 해금할 수 있습니다.");
        } else {
            commonLore.add(ChatColor.RED + "해금 조건: 아무 직업이나 10레벨 달성");
            commonLore.add(ChatColor.GRAY + "(현재 최고 레벨: " + highestLevel + ")");
        }
        commonMeta.setLore(commonLore);
        commonAwakeningBtn.setItemMeta(commonMeta);
        inv.setItem(22, commonAwakeningBtn);

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
            ItemStack upBtn = createItem(Material.GOLD_INGOT, ChatColor.YELLOW + targetClass.getDisplayName() + " 레벨 업",
                    targetClass);
            ItemMeta meta = upBtn.getItemMeta();
            meta.setLore(Arrays.asList(
                    ChatColor.YELLOW + "현재 레벨: " + storedLevel,
                    ChatColor.RED + "비용: " + cost + " 레벨",
                    ChatColor.GRAY + "클릭하여 업그레이드 확인 메뉴를 엽니다."));
            upBtn.setItemMeta(meta);
            inv.setItem(13, upBtn);
        } else if (targetClass == PlayerClass.WARRIOR && storedLevel >= 10 && storedLevel < 13) {
            // 전사 각성 버튼
            int awkCost = classManager.getAwakeningLevelCost();
            ItemStack awkBtn = createItem(Material.NETHER_STAR, ChatColor.LIGHT_PURPLE + "전사 각성 능력 해금", targetClass);
            ItemMeta meta = awkBtn.getItemMeta();
            int stage = 0;
            if (classManager.isWarriorGeumgang(storedLevel))
                stage++;
            if (classManager.isWarriorLifesteal(storedLevel))
                stage++;
            meta.setLore(Arrays.asList(
                    ChatColor.YELLOW + "현재 각성한 개수: " + stage,
                    ChatColor.RED + "비용: " + awkCost + " 레벨 또는 각성권 1개",
                    ChatColor.GRAY + "클릭하여 각성 메뉴를 엽니다."));
            awkBtn.setItemMeta(meta);
            inv.setItem(13, awkBtn);
        } else if (targetClass == PlayerClass.MAGE && storedLevel >= 10 && storedLevel < 13) {
            // 마법사 각성 버튼
            int awkCost = classManager.getAwakeningLevelCost();
            ItemStack awkBtn = createItem(Material.NETHER_STAR, ChatColor.LIGHT_PURPLE + "마법사 각성 능력 해금", targetClass);
            ItemMeta meta = awkBtn.getItemMeta();
            int stage = 0;
            if (classManager.isMageBlackHole(storedLevel))
                stage++;
            if (classManager.isMageSurge(storedLevel))
                stage++;

            meta.setLore(Arrays.asList(
                    ChatColor.YELLOW + "현재 각성한 개수: " + stage,
                    ChatColor.RED + "비용: " + awkCost + " 레벨 또는 각성권 1개",
                    ChatColor.GRAY + "클릭하여 각성 메뉴를 엽니다."));
            awkBtn.setItemMeta(meta);
            inv.setItem(13, awkBtn);
        } else if (targetClass == PlayerClass.ADVENTURER && storedLevel >= 10 && storedLevel < 13) {
            // 모험가 각성 버튼
            int awkCost = classManager.getAwakeningLevelCost();
            ItemStack awkBtn = createItem(Material.NETHER_STAR, ChatColor.LIGHT_PURPLE + "모험가 각성 능력 해금", targetClass);
            ItemMeta meta = awkBtn.getItemMeta();
            int stage = 0;
            if (classManager.isAdventurerValor(storedLevel))
                stage++;
            if (classManager.isAdventurerSurvival(storedLevel))
                stage++;

            meta.setLore(Arrays.asList(
                    ChatColor.YELLOW + "현재 각성한 개수: " + stage,
                    ChatColor.RED + "비용: " + awkCost + " 레벨 또는 각성권 1개",
                    ChatColor.GRAY + "클릭하여 각성 메뉴를 엽니다."));
            awkBtn.setItemMeta(meta);
            inv.setItem(13, awkBtn);
        } else {
            inv.setItem(13, createItem(Material.BEDROCK, ChatColor.RED + "최대 레벨 도달"));
        }

        // 3. 뒤로가기
        inv.setItem(15, createItem(Material.BARRIER, ChatColor.RED + "뒤로가기"));

        player.openInventory(inv);
    }

    // 최종 확인 메뉴: YES / NO
    private void openConfirmMenu(Player player, PlayerClass targetClass) {
        Inventory inv = Bukkit.createInventory(null, 9, CONFIRM_TITLE_PREFIX + targetClass.getDisplayName());

        ItemStack bg = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 9; i++)
            inv.setItem(i, bg);

        int level = classManager.getStoredLevel(player, targetClass);
        int cost = classManager.getUpgradeCost(level);

        inv.setItem(3, createItem(Material.LIME_WOOL, ChatColor.GREEN + "YES - 업그레이드 (" + cost + " 레벨)", targetClass));

        ItemStack info = createItem(Material.GOLD_INGOT, ChatColor.YELLOW + targetClass.getDisplayName() + " 레벨 업",
                targetClass);
        inv.setItem(4, info);

        inv.setItem(5, createItem(Material.RED_WOOL, ChatColor.RED + "NO - 취소", targetClass));

        player.openInventory(inv);
    }

    private void openJobChangeConfirmMenu(Player player, PlayerClass targetClass) {
        Inventory inv = Bukkit.createInventory(null, 9, JOB_CHANGE_CONFIRM_TITLE_PREFIX + targetClass.getDisplayName());
        ItemStack bg = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 9; i++)
            inv.setItem(i, bg);

        int cost = classManager.getJobChangeCost();
        inv.setItem(3, createItem(Material.LIME_WOOL, ChatColor.GREEN + "YES - 전직하기 (" + cost + " 레벨)", targetClass));

        ItemStack info = createItem(Material.ENDER_PEARL, ChatColor.AQUA + targetClass.getDisplayName() + " (으)로 전직",
                targetClass);
        inv.setItem(4, info);

        inv.setItem(5, createItem(Material.RED_WOOL, ChatColor.RED + "NO - 취소", targetClass));
        player.openInventory(inv);
    }

    private void openAwakeningConfirmMenu(Player player, PlayerClass targetClass, int nextLevel, String awkName,
            Material icon) {
        Inventory inv = Bukkit.createInventory(null, 9, AWAKENING_CONFIRM_TITLE_PREFIX);
        ItemStack bg = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 9; i++)
            inv.setItem(i, bg);

        String data = (targetClass == null ? "COMMON" : targetClass.name()) + ":" + nextLevel;
        ItemStack yes = createItem(Material.LIME_WOOL, ChatColor.GREEN + "YES - " + awkName + " 해금", null);
        ItemMeta meta = yes.getItemMeta();
        meta.getPersistentDataContainer().set(classManager.getGuiTargetClassKey(),
                org.bukkit.persistence.PersistentDataType.STRING, data);
        yes.setItemMeta(meta);
        inv.setItem(3, yes);

        ItemStack infoItem = createItem(icon, ChatColor.GOLD + awkName, null);
        if (icon == Material.POTION) {
            ItemMeta infoMeta = infoItem.getItemMeta();
            if (infoMeta instanceof org.bukkit.inventory.meta.PotionMeta) {
                ((org.bukkit.inventory.meta.PotionMeta) infoMeta).setColor(org.bukkit.Color.RED);
                infoItem.setItemMeta(infoMeta);
            }
        }
        inv.setItem(4, infoItem);

        ItemStack no = createItem(Material.RED_WOOL, ChatColor.RED + "NO - 취소", null);
        ItemMeta noMeta = no.getItemMeta();
        noMeta.getPersistentDataContainer().set(classManager.getGuiTargetClassKey(),
                org.bukkit.persistence.PersistentDataType.STRING, data);
        no.setItemMeta(noMeta);
        inv.setItem(5, no);
        player.openInventory(inv);
    }

    public void openAwakeningMenu(Player player, PlayerClass pClass) {
        int level = classManager.getStoredLevel(player, pClass);
        int stage = 0;
        if (pClass == PlayerClass.WARRIOR) {
            if (classManager.isWarriorGeumgang(level))
                stage++;
            if (classManager.isWarriorLifesteal(level))
                stage++;
        } else if (pClass == PlayerClass.MAGE) {
            if (classManager.isMageBlackHole(level))
                stage++;
            if (classManager.isMageSurge(level))
                stage++;
        } else if (pClass == PlayerClass.ADVENTURER) {
            if (classManager.isAdventurerValor(level))
                stage++;
            if (classManager.isAdventurerSurvival(level))
                stage++;
        }

        String stageSuffix = (stage > 0) ? " (+" + stage + ")" : "";
        Inventory inv = Bukkit.createInventory(null, 27,
                AWAKENING_TITLE_PREFIX + pClass.getDisplayName() + stageSuffix);

        ItemStack bg = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++)
            inv.setItem(i, bg);

        if (pClass == PlayerClass.WARRIOR) {
            boolean hasGeumgang = classManager.isWarriorGeumgang(level);
            boolean hasLifesteal = classManager.isWarriorLifesteal(level);
            int awkCost = classManager.getAwakeningLevelCost();

            // 1. 금강불괴 (레벨 11 or 13)
            int geumgangDur = classManager.getWarriorGeumgangDurationSeconds();
            int geumgangReflect = (int) (classManager.getWarriorGeumgangReflect() * 100);

            ItemStack geumgang = createItem(Material.BELL,
                    (hasGeumgang ? ChatColor.GREEN : ChatColor.GOLD) + "각성: 금강불괴", PlayerClass.WARRIOR);
            ItemMeta gMeta = geumgang.getItemMeta();
            if (hasGeumgang) {
                gMeta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                gMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            List<String> gLore = new ArrayList<>();
            gLore.add(ChatColor.GRAY + "종 아이템 사용 시 발동됩니다.");
            gLore.add(ChatColor.GRAY + "" + geumgangDur + "초간 무적 + 데미지 " + geumgangReflect + "% 반사");
            if (hasGeumgang) {
                gLore.add(ChatColor.GREEN + "[해금 완료]");
            } else {
                gLore.add(ChatColor.RED + "비용: " + awkCost + " 레벨 또는 각성권 1개");
            }
            gMeta.setLore(gLore);
            geumgang.setItemMeta(gMeta);
            inv.setItem(11, geumgang);

            // 2. 피흡 점프 (레벨 12 or 13)
            int lifeDur = classManager.getWarriorLifestealDurationSeconds();
            int lifePct = (int) (classManager.getWarriorLifestealPercent() * 100);

            ItemStack lifesteal = createItem(Material.NETHERITE_SWORD,
                    (hasLifesteal ? ChatColor.GREEN : ChatColor.GOLD) + "각성: 피흡 점프", PlayerClass.WARRIOR);
            ItemMeta lMeta = lifesteal.getItemMeta();
            if (hasLifesteal) {
                lMeta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                lMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            List<String> lLore = new ArrayList<>();
            lLore.add(ChatColor.GRAY + "더블 점프 후 " + lifeDur + "초간 공격 시");
            lLore.add(ChatColor.GRAY + "준 데미지의 " + lifePct + "%를 회복합니다.");
            if (hasLifesteal) {
                lLore.add(ChatColor.GREEN + "[해금 완료]");
            } else {
                lLore.add(ChatColor.RED + "비용: " + awkCost + " 레벨 또는 각성권 1개");
            }
            lMeta.setLore(lLore);
            lifesteal.setItemMeta(lMeta);
            inv.setItem(15, lifesteal);
        } else if (pClass == PlayerClass.MAGE) {
            boolean hasBlackHole = classManager.isMageBlackHole(level);
            int awkCost = classManager.getAwakeningLevelCost();

            // 1. 블랙홀 (레벨 11 or 13)
            double bhRadius = classManager.getMageBlackHoleRadius();
            int bhDmg = (int) ((classManager.getMageBlackHoleDamageMultiplier() - 1.0) * 100);
            double bhCdInc = classManager.getMageBlackHoleCooldownIncrease();

            ItemStack blackHole = createItem(Material.TNT,
                    (hasBlackHole ? ChatColor.GREEN : ChatColor.GOLD) + "각성: 블랙홀", PlayerClass.MAGE);
            ItemMeta bMeta = blackHole.getItemMeta();
            if (hasBlackHole) {
                bMeta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                bMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            List<String> bLore = new ArrayList<>();
            bLore.add(ChatColor.GRAY + "TNT 소환 시 주변 적을 " + (int) bhRadius + "블록 내에서 끌어당깁니다.");
            bLore.add(ChatColor.GRAY + "TNT 데미지 +" + bhDmg + "% / 쿨타임 +" + (int) bhCdInc + "초");
            if (hasBlackHole) {
                bLore.add(ChatColor.GREEN + "[해금 완료]");
            } else {
                bLore.add(ChatColor.RED + "비용: " + awkCost + " 레벨 또는 각성권 1개");
            }
            bMeta.setLore(bLore);
            blackHole.setItemMeta(bMeta);
            inv.setItem(11, blackHole);

            // 2. 원소 폭주 (레벨 12 or 13)
            boolean hasSurge = classManager.isMageSurge(level);
            double surgeCd = classManager.getMageSurgeCooldownSeconds();
            int surgeRed = (int) (classManager.getMageSurgeCooldownReduction() * 100);

            ItemStack surge = createItem(Material.LIGHTNING_ROD,
                    (hasSurge ? ChatColor.GREEN : ChatColor.GOLD) + "각성: 원소 폭주", PlayerClass.MAGE);
            ItemMeta sMeta = surge.getItemMeta();
            if (hasSurge) {
                sMeta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                sMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            List<String> sLore = new ArrayList<>();
            sLore.add(ChatColor.GRAY + "마법서를 들고 F키를 누르면 발동됩니다.");
            sLore.add(ChatColor.GRAY + "10초간 스킬 쿨타임 " + surgeRed + "% 감소 (쿨타임: " + (int) surgeCd + "초)");
            if (hasSurge) {
                sLore.add(ChatColor.GREEN + "[해금 완료]");
            } else {
                sLore.add(ChatColor.RED + "비용: " + awkCost + " 레벨 또는 각성권 1개");
            }
            sMeta.setLore(sLore);
            surge.setItemMeta(sMeta);
            inv.setItem(15, surge);
        } else if (pClass == PlayerClass.ADVENTURER) {
            boolean hasValor = classManager.isAdventurerValor(level);
            boolean hasSurvival = classManager.isAdventurerSurvival(level);
            int awkCost = classManager.getAwakeningLevelCost();

            // 1. 용맹 (레벨 11 or 13)
            ItemStack valor = createItem(Material.POTION,
                    (hasValor ? ChatColor.GREEN : ChatColor.GOLD) + "각성: 용맹", PlayerClass.ADVENTURER);
            ItemMeta vMeta = valor.getItemMeta();
            if (vMeta instanceof org.bukkit.inventory.meta.PotionMeta) {
                ((org.bukkit.inventory.meta.PotionMeta) vMeta).setColor(org.bukkit.Color.RED);
            }
            if (hasValor) {
                vMeta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                vMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            List<String> vLore = new ArrayList<>();
            vLore.add(ChatColor.GRAY + "모험가의 재생 포션을 들고 F키를 누르면 자신과 주변 플레이어에게");
            double dmgMultiplier = classManager.getAdventurerValorDamageMultiplier();
            vLore.add(ChatColor.GRAY + "피해량 " + (int) (dmgMultiplier * 100 - 100) + "% 증가 및 피해량의"
                    + (int) (classManager.getAdventurerValorLifestealPercent() * 100) + "% 흡혈 버프를 부여합니다.");
            double cooltime = classManager.getAdventurerValorCooldown();
            double duration = classManager.getAdventurerValorDuration();
            vLore.add(ChatColor.GRAY + "(" + (int) duration + "초 지속, 쿨타임 " + (int) cooltime + "초)");
            if (hasValor) {
                vLore.add(ChatColor.GREEN + "[해금 완료]");
            } else {
                vLore.add(ChatColor.RED + "비용: " + awkCost + " 레벨 또는 각성권 1개");
            }
            vMeta.setLore(vLore);
            valor.setItemMeta(vMeta);
            inv.setItem(11, valor);

            // 2. 생존 전문가 (레벨 12 or 13)
            ItemStack survival = createItem(Material.GOLDEN_APPLE,
                    (hasSurvival ? ChatColor.GREEN : ChatColor.GOLD) + "각성: 강화된 파동", PlayerClass.ADVENTURER);
            ItemMeta sMeta = survival.getItemMeta();
            if (hasSurvival) {
                sMeta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                sMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            List<String> sLore = new ArrayList<>();
            sLore.add(ChatColor.GRAY + "파동 안에 들어가면 " + ChatColor.RED + "모든 디버프" + ChatColor.GRAY + "가 해제되고");
            int buffDur = classManager.getAdventurerSurvivalBuffDuration();
            sLore.add(ChatColor.GRAY + "보호막과 각종 이로운 효과를 " + buffDur + "초간 얻습니다.");
            int survivalCdInc = classManager.getAdventurerSurvivalCooldownIncrease();
            sLore.add(ChatColor.RED + "(기본 쿨타임 +" + survivalCdInc + "초 증가)");
            if (hasSurvival) {
                sLore.add(ChatColor.GREEN + "[해금 완료]");
            } else {
                sLore.add(ChatColor.RED + "비용: " + awkCost + " 레벨 또는 각성권 1개");
            }
            sMeta.setLore(sLore);
            survival.setItemMeta(sMeta);
            inv.setItem(15, survival);
        }

        inv.setItem(22, createItem(Material.BARRIER, ChatColor.RED + "뒤로가기"));

        player.openInventory(inv);
    }

    public void openCommonAwakeningMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, COMMON_AWAKENING_TITLE);

        ItemStack bg = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++)
            inv.setItem(i, bg);

        boolean has2x2 = classManager.hasCommonAwakening(player, "mining_2x2");
        int awkCost = classManager.getAwakeningLevelCost();

        // 2x2 채굴
        ItemStack mining2x2 = createItem(Material.DIAMOND_PICKAXE,
                (has2x2 ? ChatColor.GREEN : ChatColor.GOLD) + "공통 각성: 2x2 채굴", null);
        ItemMeta mMeta = mining2x2.getItemMeta();
        if (has2x2) {
            mMeta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            mMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        }
        List<String> mLore = new ArrayList<>();
        mLore.add(ChatColor.GRAY + "곡괭이로 채굴 시 2x2 범위를 파괴합니다.");
        if (has2x2) {
            mLore.add(ChatColor.GREEN + "[해금 완료]");
        } else {
            mLore.add(ChatColor.RED + "비용: " + awkCost + " 레벨 또는 각성권 1개");
        }
        mMeta.setLore(mLore);
        mining2x2.setItemMeta(mMeta);
        inv.setItem(13, mining2x2);

        inv.setItem(22, createItem(Material.BARRIER, ChatColor.RED + "뒤로가기"));

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
        // job level ui
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        boolean isCurrent = (targetClass == currentClass);
        int storedLevel = classManager.getStoredLevel(player, targetClass);
        String levelStr = "Lv." + storedLevel;
        if (storedLevel > 10) {
            int stage = 0;
            if (targetClass == PlayerClass.WARRIOR) {
                if (classManager.isWarriorGeumgang(storedLevel))
                    stage++;
                if (classManager.isWarriorLifesteal(storedLevel))
                    stage++;
            } else if (targetClass == PlayerClass.MAGE) {
                if (classManager.isMageBlackHole(storedLevel))
                    stage++;
                if (classManager.isMageSurge(storedLevel))
                    stage++;
            } else if (targetClass == PlayerClass.ADVENTURER) {
                if (classManager.isAdventurerValor(storedLevel))
                    stage++;
                if (classManager.isAdventurerSurvival(storedLevel))
                    stage++;
            }
            levelStr = "Lv.10 (+" + stage + ")";
        }

        meta.setDisplayName((isCurrent ? ChatColor.GREEN : ChatColor.YELLOW) + targetClass.getDisplayName() + " ("
                + levelStr + ")");

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
                if (storedLevel >= 4) {
                    int hearts = (storedLevel >= 10 ? 3 : (storedLevel >= 7 ? 2 : 1));
                    lore.add(ChatColor.WHITE + "- 추가 체력: " + ChatColor.GREEN + "+" + hearts + "칸");
                }

                if (storedLevel >= 3) {
                    double stormCd = classManager.getWarriorArrowStormCooldown(storedLevel);
                    lore.add(ChatColor.GOLD + "- 애로우 스톰: 사방으로 화살 발사 (" + ChatColor.YELLOW
                            + String.format("%.1f", stormCd) + "초" + ChatColor.GOLD + ")");
                }

                if (storedLevel >= 8) {
                    double jumpCd = classManager.getWarriorDoubleJumpCooldown();
                    lore.add(ChatColor.GOLD + "- 더블 점프: 전방 도약 (" + ChatColor.YELLOW + String.format("%.1f", jumpCd)
                            + "초" + ChatColor.GOLD + ")");
                }

                if (storedLevel >= 5) {
                    int slowDur = classManager.getWarriorSlowDuration() / 20;
                    int slowPct = (int) (classManager.getWarriorSlowPercentage(storedLevel) * 100);
                    lore.add(ChatColor.GOLD + "- 타격 둔화: " + ChatColor.YELLOW + "" + slowPct + "% " + ChatColor.WHITE
                            + "(" + slowDur + "초)");
                }

                if (storedLevel >= 4) {
                    lore.add(ChatColor.GOLD + "- 타격 시 이동 속도 증가");
                }
                if (storedLevel >= 6) {
                    double shieldCd = classManager.getWarriorShieldCooldown();
                    lore.add(ChatColor.GOLD + "- 전사의 방패: 방어 후 돌진 (" + ChatColor.YELLOW + String.format("%.1f", shieldCd)
                            + "초" + ChatColor.GOLD + ")");
                }

                // 각성 단계 및 스킬 표시
                int stage = 0;
                if (classManager.isWarriorGeumgang(storedLevel))
                    stage++;
                if (classManager.isWarriorLifesteal(storedLevel))
                    stage++;

                if (stage > 0) {
                    lore.add("");
                    lore.add(ChatColor.LIGHT_PURPLE + "[각성 능력 - +" + stage + "단계]");

                    if (classManager.isWarriorGeumgang(storedLevel)) {
                        int geumgangDur = classManager.getWarriorGeumgangDurationSeconds();
                        int geumgangReflect = (int) (classManager.getWarriorGeumgangReflect() * 100);
                        lore.add(ChatColor.WHITE + "- 금강불괴: " + ChatColor.YELLOW + geumgangDur + "초 무적, "
                                + geumgangReflect + "% 반사");
                    }
                    if (classManager.isWarriorLifesteal(storedLevel)) {
                        int lifeDur = classManager.getWarriorLifestealDurationSeconds();
                        int lifePct = (int) (classManager.getWarriorLifestealPercent() * 100);
                        lore.add(ChatColor.GOLD + "- 각성: 피흡 점프 (" + ChatColor.YELLOW + lifeDur + "초간 " + lifePct + "%"
                                + ChatColor.GOLD
                                + ")");
                    }
                }
                break;

            case MAGE:
                double tntMultiplier = classManager.getMageTntMultiplier(storedLevel);
                int tntPct = (int) (tntMultiplier * 100);

                double cdMultiplier = classManager.getMageCooldownMultiplier(storedLevel);
                int cdReduction = (int) ((1.0 - cdMultiplier) * 100);

                lore.add(ChatColor.GOLD + "- 지팡이: 마법 TNT 투척 (" + ChatColor.YELLOW + "데미지 " + tntPct + "%"
                        + ChatColor.WHITE + ")");
                lore.add(ChatColor.GOLD + "- 균열의 서: 전방 공간 도약");
                if (storedLevel >= 5) {
                    lore.add(ChatColor.GOLD + "- 화염구: 화염 지대를 만드는 투사체");
                }
                if (storedLevel >= 8) {
                    lore.add(ChatColor.GOLD + "- 서리 도약: 주변 적 둔화 및 도약");
                }
                if (cdReduction > 0) {
                    lore.add(ChatColor.GOLD + "- 스킬 쿨타임: " + ChatColor.GREEN + "-" + cdReduction + "%");
                }

                // 마법사 각성 단계
                if (storedLevel > 10) {
                    int mStage = 0;
                    if (classManager.isMageBlackHole(storedLevel))
                        mStage++;
                    if (classManager.isMageSurge(storedLevel))
                        mStage++;

                    if (mStage > 0) {
                        lore.add("");
                        lore.add(ChatColor.LIGHT_PURPLE + "[각성 능력 - +" + mStage + "단계]");
                        if (classManager.isMageBlackHole(storedLevel)) {
                            int bhDmg = (int) ((classManager.getMageBlackHoleDamageMultiplier() - 1.0) * 100);
                            lore.add(ChatColor.GOLD + "- 각성: 블랙홀 (TNT 데미지 +" + bhDmg + "% & 끌어당김)");
                        }
                        if (classManager.isMageSurge(storedLevel)) {
                            int surgeRed = (int) (classManager.getMageSurgeCooldownReduction() * 100);
                            lore.add(ChatColor.GOLD + "- 각성: 원소 폭주 (쿨타임 -" + surgeRed + "%)");
                        }
                    }
                }

                lore.add(ChatColor.GRAY + "  (소환한 TNT 데미지에 면역)");
                lore.add("");
                lore.add(ChatColor.RED + "[제한] 낚시 불가");
                break;

            case ADVENTURER:
                if (storedLevel < 5) {
                    lore.add(ChatColor.DARK_GRAY + "- 약화 I (5레벨에 제거됨)");
                }

                int minExp = classManager.getAdventurerHarvestExpMin();
                int maxExp = classManager.getAdventurerHarvestExpMax();
                lore.add(ChatColor.WHITE + "- 특수 채집: 판자/작물 추가 드랍");
                lore.add(ChatColor.WHITE + "- 수확 경험치: 농사시 추가 경험치 (" + ChatColor.GREEN + minExp + "~" + maxExp
                        + ChatColor.WHITE + ")");
                if (storedLevel >= 3) {
                    lore.add(ChatColor.WHITE + "- 약탈: 동물에게만 적용");
                }
                int extraHearts = (Math.min(storedLevel, 10) / 2);
                if (extraHearts > 0) {
                    lore.add(ChatColor.WHITE + "- 추가 체력: " + ChatColor.GREEN + "+" + extraHearts + "칸");
                }

                double expMult = classManager.getFarmerExpMultiplier();
                int totalExpPct = (int) (expMult * storedLevel * 100.);
                lore.add(ChatColor.WHITE + "- 기본 경험치 획득량: " + ChatColor.GREEN + "+" + totalExpPct + "%");

                double animalExpMult = classManager.getAdventurerAnimalExpMultiplier();
                int animalExpPct = (int) ((animalExpMult - 1.0) * 100);
                lore.add(ChatColor.WHITE + "- 동물 사냥 경험치: " + ChatColor.GREEN + "+" + animalExpPct + "%");
                lore.add(ChatColor.WHITE + "- 동물 사냥 시 약탈 효과 적용");
                lore.add(ChatColor.GOLD + "- 재생 포션: 주변 재생 필드 전개");
                if (storedLevel >= 10) {
                    int satCd = classManager.getAdventurerSaturationCooldown();
                    lore.add(ChatColor.GOLD + "- 포만감 " + ChatColor.GRAY + "(피격 시 쿨타임 " + satCd + "초)");
                    lore.add(ChatColor.GOLD + "- 위기탈출: 위기 시 스폰지점 자동 텔레포트");
                    int mStage = 0;
                    if (targetClass == PlayerClass.ADVENTURER) {
                        if (classManager.isAdventurerValor(storedLevel))
                            mStage++;
                        if (classManager.isAdventurerSurvival(storedLevel))
                            mStage++;
                    }
                    lore.add(ChatColor.LIGHT_PURPLE + "[각성 능력 - +" + mStage + "단계]");
                    if (classManager.isAdventurerValor(storedLevel)) {
                        double valorDmg = classManager.getAdventurerValorDamageMultiplier();
                        int valorPct = (int) (valorDmg * 100 - 100);
                        lore.add(ChatColor.GOLD + "- 각성: 용맹 " + ChatColor.GRAY + "(데미지 +" + valorPct + "% 및 흡혈)");
                    }
                    if (classManager.isAdventurerSurvival(storedLevel)) {
                        lore.add(ChatColor.GOLD + "- 각성: 강화된 파동 " + ChatColor.GRAY + "(강화된 치유 파동 및 낙하 피해 면역)");
                    }
                }
                break;
            default:
                break;
        }
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(classManager.getGuiTargetClassKey(),
                org.bukkit.persistence.PersistentDataType.STRING, targetClass.name());
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
            meta.getPersistentDataContainer().set(classManager.getGuiTargetClassKey(),
                    org.bukkit.persistence.PersistentDataType.STRING, targetClass.name());
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
                && !title.startsWith(CONFIRM_TITLE_PREFIX) && !title.startsWith(AWAKENING_TITLE_PREFIX)
                && !title.equals(COMMON_AWAKENING_TITLE) && !title.startsWith(JOB_CHANGE_CONFIRM_TITLE_PREFIX)
                && !title.startsWith(AWAKENING_CONFIRM_TITLE_PREFIX)) {
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
                String targetClassName = clickedItem.getItemMeta().getPersistentDataContainer()
                        .get(classManager.getGuiTargetClassKey(), org.bukkit.persistence.PersistentDataType.STRING);
                if (targetClassName != null) {
                    try {
                        PlayerClass targetClass = PlayerClass.valueOf(targetClassName);
                        openSubMenu(player, targetClass);
                        return;
                    } catch (Exception e) {
                    }
                }
            }

            if (slot == 21 && classManager.isHungerSystemEnabled()) {
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

            if (slot == 22) {
                if (classManager.getHighestClassLevel(player) < 10) {
                    player.sendMessage(ChatColor.RED + "공통 각성은 한 개 이상의 직업이 10레벨을 달성해야 이용 가능합니다!");
                    return;
                }
                // 공통 각성 메뉴 열기
                openCommonAwakeningMenu(player);
            }
        }
        // 2. 서브 메뉴 처리 (직업 관리)
        else if (title.startsWith(SUB_TITLE_PREFIX)) {
            ItemStack clickedItem = event.getCurrentItem();
            PlayerClass targetClass = null;
            if (clickedItem != null && clickedItem.hasItemMeta()) {
                String targetClassName = clickedItem.getItemMeta().getPersistentDataContainer()
                        .get(classManager.getGuiTargetClassKey(), org.bukkit.persistence.PersistentDataType.STRING);
                if (targetClassName != null) {
                    try {
                        targetClass = PlayerClass.valueOf(targetClassName);
                    } catch (Exception e) {
                    }
                }
            }

            if (slot == 11 && targetClass != null) {
                // 전직
                if (classManager.getPlayerClass(player) == targetClass)
                    return;
                openJobChangeConfirmMenu(player, targetClass);
            } else if (slot == 13 && targetClass != null) {
                // 업그레이드 클릭 시 확인 메뉴로
                int level = classManager.getStoredLevel(player, targetClass);
                if (level >= 10) {
                    if (level < 13) {
                        openAwakeningMenu(player, targetClass);
                    }
                    return;
                }
                openConfirmMenu(player, targetClass);
            } else if (slot == 15) {
                openJobGui(player);
            }
        }
        // 3. 각성 메뉴 처리
        else if (title.startsWith(AWAKENING_TITLE_PREFIX)) {
            // 아이템에서 대상 클래스 가져오기
            PlayerClass pClass = null;
            ItemStack infoItem = event.getInventory().getItem(11);
            if (infoItem != null && infoItem.hasItemMeta()) {
                String targetClassName = infoItem.getItemMeta().getPersistentDataContainer()
                        .get(classManager.getGuiTargetClassKey(), org.bukkit.persistence.PersistentDataType.STRING);
                if (targetClassName != null) {
                    try {
                        pClass = PlayerClass.valueOf(targetClassName);
                    } catch (Exception e) {
                    }
                }
            }
            if (pClass == null)
                return;

            if (slot == 22) {
                openSubMenu(player, pClass);
                return;
            }

            int currentLevel = classManager.getStoredLevel(player, pClass);
            int nextLevel = -1;

            if (pClass == PlayerClass.WARRIOR) {
                if (slot == 11) { // 금강불괴
                    if (classManager.isWarriorGeumgang(currentLevel))
                        return;
                    nextLevel = (currentLevel == 12) ? 13 : 11;
                } else if (slot == 15) { // 피흡 점프
                    if (classManager.isWarriorLifesteal(currentLevel))
                        return;
                    nextLevel = (currentLevel == 11) ? 13 : 12;
                }
            } else if (pClass == PlayerClass.MAGE) {
                if (slot == 11) { // 블랙홀
                    if (classManager.isMageBlackHole(currentLevel))
                        return;
                    nextLevel = (currentLevel == 12) ? 13 : 11;
                } else if (slot == 15) { // 원소 폭주
                    if (classManager.isMageSurge(currentLevel))
                        return;
                    nextLevel = (currentLevel == 11) ? 13 : 12;
                }
            } else if (pClass == PlayerClass.ADVENTURER) {
                if (slot == 11) { // 용맹
                    if (classManager.isAdventurerValor(currentLevel))
                        return;
                    nextLevel = (currentLevel == 12) ? 13 : 11;
                } else if (slot == 15) { // 생존 전문가
                    if (classManager.isAdventurerSurvival(currentLevel))
                        return;
                    nextLevel = (currentLevel == 11) ? 13 : 12;
                }
            }

            if (nextLevel != -1) {
                String awkName = ChatColor.stripColor(event.getCurrentItem().getItemMeta().getDisplayName());
                if (awkName.startsWith("각성: "))
                    awkName = awkName.substring(4);
                Material iconMat = event.getCurrentItem().getType();
                openAwakeningConfirmMenu(player, pClass, nextLevel, awkName, iconMat);
            }
        }
        // 3. 확인 메뉴 처리
        else if (title.startsWith(CONFIRM_TITLE_PREFIX)) {
            ItemStack clickedItem = event.getCurrentItem();
            PlayerClass targetClass = null;
            if (clickedItem != null && clickedItem.hasItemMeta()) {
                String targetClassName = clickedItem.getItemMeta().getPersistentDataContainer()
                        .get(classManager.getGuiTargetClassKey(), org.bukkit.persistence.PersistentDataType.STRING);
                if (targetClassName != null) {
                    try {
                        targetClass = PlayerClass.valueOf(targetClassName);
                    } catch (Exception e) {
                    }
                }
            }

            if (slot == 3 && targetClass != null) {
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
                classManager.playLevelUpEffects(player, targetClass, nextLevel);

                classManager.applyClassBuffs(player);
                openSubMenu(player, targetClass);
            } else if (slot == 5 && targetClass != null) {
                // NO
                openSubMenu(player, targetClass);
            }
        }
        // 전직 확인 메뉴 처리
        else if (title.startsWith(JOB_CHANGE_CONFIRM_TITLE_PREFIX)) {
            ItemStack clickedItem = event.getCurrentItem();
            PlayerClass targetClass = null;
            if (clickedItem != null && clickedItem.hasItemMeta()) {
                String targetClassName = clickedItem.getItemMeta().getPersistentDataContainer()
                        .get(classManager.getGuiTargetClassKey(), org.bukkit.persistence.PersistentDataType.STRING);
                if (targetClassName != null) {
                    try {
                        targetClass = PlayerClass.valueOf(targetClassName);
                    } catch (Exception e) {
                    }
                }
            }

            if (slot == 3 && targetClass != null) {
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
            } else if (slot == 5 && targetClass != null) {
                openSubMenu(player, targetClass);
            }
        }
        // 각성 확인 메뉴 처리
        else if (title.startsWith(AWAKENING_CONFIRM_TITLE_PREFIX)) {
            ItemStack clickedItem = event.getCurrentItem();
            String data = null;
            if (clickedItem != null && clickedItem.hasItemMeta()) {
                data = clickedItem.getItemMeta().getPersistentDataContainer()
                        .get(classManager.getGuiTargetClassKey(), org.bukkit.persistence.PersistentDataType.STRING);
            }

            if (slot == 3 && data != null) {
                String[] parts = data.split(":");
                String className = parts[0];
                int nextLevel = Integer.parseInt(parts[1]);

                ItemStack ticketItem = null;
                for (ItemStack item : player.getInventory().getContents()) {
                    if (item != null && classManager.getAwakeningTicketKey() != null
                            && item.getItemMeta() != null
                            && item.getItemMeta().getPersistentDataContainer().has(classManager.getAwakeningTicketKey(),
                                    org.bukkit.persistence.PersistentDataType.BYTE)) {
                        ticketItem = item;
                        break;
                    }
                }

                int awkCost = classManager.getAwakeningLevelCost();
                boolean paid = false;

                if (ticketItem != null) {
                    ticketItem.setAmount(ticketItem.getAmount() - 1);
                    paid = true;
                } else if (player.getLevel() >= awkCost) {
                    player.setLevel(player.getLevel() - awkCost);
                    paid = true;
                }

                if (paid) {
                    player.sendMessage(ChatColor.GREEN + "각성 능력을 해금했습니다!");
                    if (className.equals("COMMON")) {
                        if (nextLevel == 999) {
                            classManager.setCommonAwakening(player, "mining_2x2", true);
                            classManager.syncJobItem(player, classManager.getPlayerClass(player));
                            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                        }
                    } else {
                        PlayerClass targetClass = PlayerClass.valueOf(className);
                        classManager.setStoredLevel(player, targetClass, nextLevel);
                        classManager.syncJobItem(player, targetClass);
                        classManager.playLevelUpEffects(player, targetClass, nextLevel);
                        classManager.applyClassBuffs(player);
                    }
                    player.closeInventory();
                } else {
                    player.sendMessage(ChatColor.RED + "각성 비용이 부족합니다! (" + awkCost + " 레벨 또는 각성권)");
                }
            } else if (slot == 5 && data != null) {
                String className = data.contains(":") ? data.split(":")[0] : data;
                if (className.equals("COMMON")) {
                    openCommonAwakeningMenu(player);
                } else {
                    try {
                        PlayerClass targetClass = PlayerClass.valueOf(className);
                        openAwakeningMenu(player, targetClass);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        // 4. 공통 각성 메뉴 처리
        else if (title.equals(COMMON_AWAKENING_TITLE)) {
            if (slot == 22) {
                openJobGui(player);
                return;
            }

            if (slot == 13) { // 2x2 채굴
                if (classManager.hasCommonAwakening(player, "mining_2x2"))
                    return;

                openAwakeningConfirmMenu(player, null, 999, "2x2 채굴", Material.DIAMOND_PICKAXE);
            }
        }
    }
}
