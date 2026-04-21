package com.forbium.headOfExit;

import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class HeadOfExit extends JavaPlugin implements Listener {

    private HeadManager headManager;
    private LangManager langManager;

    @Override
    public void onEnable() {
        this.headManager = new HeadManager(this);
        getServer().getPluginManager().registerEvents(new HeadListener(this, headManager), this);
        this.langManager = new LangManager(this);

        saveDefaultConfig();
        saveResource("lang/ru.yml", false);
        saveResource("lang/en.yml", false);
    }

    @Override
    public void onDisable() {
        headManager.saveData();
    }

    public HeadManager getHeadManager() {
        return headManager;
    }
    public LangManager getLang() { return langManager; }
}