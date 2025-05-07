package ro.marioenache.votemanager.common.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class to fetch UUIDs for player names and validate them
 */
public class UUIDFetcher {
    private static final String PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/";
    
    private static final Map<String, UUID> nameToUuidCache = new ConcurrentHashMap<>();
    private static final Map<UUID, String> uuidToNameCache = new ConcurrentHashMap<>();
    
    private static final Logger logger = Logger.getLogger("UUIDFetcher");

    /**
     * Get a UUID for a player name
     * 
     * @param name The player name
     * @return The UUID or null if not found
     */
    public static UUID getUUID(String name) {
        // Check cache first
        if (nameToUuidCache.containsKey(name.toLowerCase())) {
            return nameToUuidCache.get(name.toLowerCase());
        }
        
        try {
            URL url = new URL(PROFILE_URL + name);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            
            if (connection.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    
                    String jsonResponse = response.toString();
                    // Extract id from json response
                    String id = extractId(jsonResponse);
                    
                    if (id != null) {
                        UUID uuid = fromDashedUUID(id);
                        // Cache the result
                        nameToUuidCache.put(name.toLowerCase(), uuid);
                        uuidToNameCache.put(uuid, name);
                        return uuid;
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to fetch UUID for " + name, e);
        }
        
        return null;
    }

    /**
     * Get a player name for a UUID
     * 
     * @param uuid The UUID
     * @return The player name or null if not found
     */
    public static String getName(UUID uuid) {
        // Check cache first
        if (uuidToNameCache.containsKey(uuid)) {
            return uuidToNameCache.get(uuid);
        }
        
        // Not supported directly by the API, would need to use other methods
        return null;
    }

    /**
     * Check if a UUID is valid for a player name
     * 
     * @param name The player name
     * @param uuid The UUID to check
     * @return True if the UUID is valid for the player name
     */
    public static boolean isValidUUID(String name, UUID uuid) {
        UUID fetchedUUID = getUUID(name);
        return fetchedUUID != null && fetchedUUID.equals(uuid);
    }

    /**
     * Asynchronously get a UUID for a player name
     * 
     * @param name The player name
     * @return A CompletableFuture that will be completed with the UUID or null if not found
     */
    public static CompletableFuture<UUID> getUUIDAsync(String name) {
        return CompletableFuture.supplyAsync(() -> getUUID(name));
    }

    /**
     * Add a UUID to the cache
     * 
     * @param name The player name
     * @param uuid The UUID
     */
    public static void cacheUUID(String name, UUID uuid) {
        nameToUuidCache.put(name.toLowerCase(), uuid);
        uuidToNameCache.put(uuid, name);
    }

    /**
     * Clear the UUID cache
     */
    public static void clearCache() {
        nameToUuidCache.clear();
        uuidToNameCache.clear();
    }

    /**
     * Extract the ID from a JSON response
     * 
     * @param json The JSON response
     * @return The ID or null if not found
     */
    private static String extractId(String json) {
        // Simple JSON parsing to extract ID
        int idIndex = json.indexOf("\"id\":\"");
        if (idIndex != -1) {
            int startIndex = idIndex + 6;
            int endIndex = json.indexOf("\"", startIndex);
            if (endIndex != -1) {
                return json.substring(startIndex, endIndex);
            }
        }
        return null;
    }

    /**
     * Convert a dashed UUID string to a UUID object
     * 
     * @param id The UUID string without dashes
     * @return The UUID object
     */
    private static UUID fromDashedUUID(String id) {
        return UUID.fromString(
            id.replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                                "$1-$2-$3-$4-$5")
        );
    }
}