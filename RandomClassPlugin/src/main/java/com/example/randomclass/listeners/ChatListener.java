package com.example.randomclass.listeners;

import com.example.randomclass.ClassManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public class ChatListener implements Listener {

    private final ClassManager classManager;

    public ChatListener(ClassManager classManager) {
        this.classManager = classManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        String prefix = classManager.getPlayerPrefix(event.getPlayer());
        if (prefix.isEmpty()) return;

        // 다른 플러그인의 설정을 무시하고 우리 접두사를 강제로 추가
        // 기본 포맷: <[Prefix] PlayerName> Message
        // 화살괄호 < > 를 제거하고 깔끔하게 '접두사 이름: 메시지' 형태로 설정
        event.setFormat(prefix + "%1$s: %2$s");
    }
}
