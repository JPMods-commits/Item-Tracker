package net.jpmods.itemtracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConfigStore {
    private static final Logger LOG = LoggerFactory.getLogger("Item Tracker");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("itemtracker.json");
    private static Settings current = load();
    private ConfigStore() {}

    public static Settings current() { return current; }
    public static Settings copy() { return GSON.fromJson(GSON.toJson(current), Settings.class); }

    private static Settings load() {
        if(Files.isRegularFile(FILE)) {
            try {
                Settings result = GSON.fromJson(Files.readString(FILE), Settings.class);
                if(result != null) { result.normalize(); return result; }
            } catch(IOException | RuntimeException e) {
                LOG.warn("Could not read itemtracker.json; using defaults", e);
            }
        }
        return new Settings();
    }

    public static boolean apply(Settings value) {
        value.normalize();
        Path temporary = null;
        try {
            Files.createDirectories(FILE.getParent());
            temporary = Files.createTempFile(FILE.getParent(), "itemtracker-", ".tmp");
            Files.writeString(temporary, GSON.toJson(value) + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch(java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
            current = value;
            ItemTrackerClient.potions().applyOptions(value.potion);
            ItemTrackerClient.diasmp().applyOptions(value.diasmp);
            return true;
        } catch(IOException | RuntimeException e) {
            LOG.error("Could not save Item Tracker settings", e);
            return false;
        } finally {
            if(temporary != null) try { Files.deleteIfExists(temporary); } catch(IOException ignored) {}
        }
    }
}
