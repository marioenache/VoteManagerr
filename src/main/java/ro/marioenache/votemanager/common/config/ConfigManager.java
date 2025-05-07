package ro.marioenache.votemanager.common.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Abstract config manager for platform-specific implementations
 */
public abstract class ConfigManager {
    protected final File dataFolder;

    /**
     * Create a new config manager
     * 
     * @param dataFolder The data folder to store config files in
     */
    public ConfigManager(File dataFolder) {
        this.dataFolder = dataFolder;
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
    }

    /**
     * Save a default config file if it doesn't exist
     * 
     * @param resourcePath The path to the resource in the jar
     * @param file The file to save to
     * @param replace Whether to replace the file if it exists
     */
    protected void saveResource(String resourcePath, File file, boolean replace) {
        if (file.exists() && !replace) {
            return;
        }
        
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            
            Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Save all default config files
     */
    public abstract void saveDefaultConfigs();

    /**
     * Reload all config files
     */
    public abstract void reloadConfigs();
}