package com.ytliquid;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Matches the labels YouTube uses on its bottom navigation tabs.
 *
 * <p>YouTube's view classes are obfuscated and change between releases, but the
 * tab labels/content descriptions stay localised versions of the same five
 * destinations. Matching them is the most version-proof way to find the bar.
 */
public final class NavLabels {

    private static final Set<String> LABELS = new HashSet<>(Arrays.asList(
            // Home
            "home", "beranda", "accueil", "inicio", "startseite", "start",
            "главная", "додому", "головна",
            "首页", "ホーム", "홈",
            // Shorts
            "shorts", "short",
            // Subscriptions
            "subscriptions", "subscription", "langganan", "abonnements",
            "abonniert", "suscripciones", "подписки", "підписки", "abonnements",
            "订阅", "チャンネル登録", "구독",
            // You / profile
            "you", "anda", "profil", "profile", "vous", "tu",
            "вы", "ви", "ты", "プロフィール", "あなた",
            // Create
            "create", "buat", "créer", "crear", "создать", "створити",
            "作成", "만들기",
            // Library
            "library", "koleksi", "bibliothèque", "biblioteca",
            "библиотека", "бібліотека", "媒体库", "ライブラリ",
            // WhatsApp
            "chats", "chat", "obrolan",
            "updates", "update", "status",
            "communities", "community", "komunitas",
            "calls", "call", "panggilan"
    ));

    private NavLabels() {
    }

    public static boolean isNavLabel(String text) {
        if (text == null) return false;
        String s = text.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return false;
        if (LABELS.contains(s)) return true;

        // Tolerate trailing punctuation such as "Home." or "Home, selected".
        String stripped = s.replaceAll("[\\s\\p{Punct}]+$", "").replaceAll("^[\\s\\p{Punct}]+", "");
        if (LABELS.contains(stripped)) return true;

        // Tolerate suffixes such as "Home tab" / "Beranda, tab".
        int space = stripped.indexOf(' ');
        if (space > 0 && LABELS.contains(stripped.substring(0, space))) return true;

        return false;
    }
}
