package com.forbium.headOfExit;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

public class LangManager {

    private final HeadOfExit plugin;
    private FileConfiguration lang;

    public LangManager(HeadOfExit plugin) {
        this.plugin = plugin;
        load();
    }

    private void load() {
        String language = plugin.getConfig().getString("language", "en");

        // Копируем файл из ресурсов если его нет
        File langFile = new File(plugin.getDataFolder(), "lang/" + language + ".yml");
        if (!langFile.exists()) {
            langFile.getParentFile().mkdirs();
            plugin.saveResource("lang/" + language + ".yml", false);
        }

        lang = YamlConfiguration.loadConfiguration(langFile);
    }

    // Получить строку по ключу
    public String get(String key) {
        return lang.getString(key, "§c[Missing: " + key + "]");
    }

    // Получить строку с заменой плейсхолдеров
    public String get(String key, String... placeholders) {
        String message = get(key);
        // placeholders передаются парами: "ключ", "значение"
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            message = message.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return message;
    }
}