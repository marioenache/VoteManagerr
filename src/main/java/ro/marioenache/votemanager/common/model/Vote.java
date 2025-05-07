package ro.marioenache.votemanager.common.model;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Represents a vote in the VoteManager system
 */
public class Vote {
    private final UUID uuid;
    private final String playerName;
    private final String serviceName;
    private final String server;
    private final LocalDateTime timestamp;
    private boolean processed;
    private boolean cached;

    /**
     * Create a new vote
     * 
     * @param uuid The UUID of the player (can be null for votes cast before player joined)
     * @param playerName The name of the player
     * @param serviceName The name of the vote service
     * @param server The server the vote is for
     */
    public Vote(UUID uuid, String playerName, String serviceName, String server) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.serviceName = serviceName;
        this.server = server;
        this.timestamp = LocalDateTime.now();
        this.processed = false;
        this.cached = false;
    }

    /**
     * Create a new vote with all properties
     * 
     * @param uuid The UUID of the player (can be null for votes cast before player joined)
     * @param playerName The name of the player
     * @param serviceName The name of the vote service
     * @param server The server the vote is for
     * @param timestamp The timestamp of the vote
     * @param processed Whether the vote has been processed
     * @param cached Whether the vote is cached
     */
    public Vote(UUID uuid, String playerName, String serviceName, String server, 
                LocalDateTime timestamp, boolean processed, boolean cached) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.serviceName = serviceName;
        this.server = server;
        this.timestamp = timestamp;
        this.processed = processed;
        this.cached = cached;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getServer() {
        return server;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public boolean isProcessed() {
        return processed;
    }

    public void setProcessed(boolean processed) {
        this.processed = processed;
    }

    public boolean isCached() {
        return cached;
    }

    public void setCached(boolean cached) {
        this.cached = cached;
    }

    @Override
    public String toString() {
        return "Vote{" +
                "uuid=" + uuid +
                ", playerName='" + playerName + '\'' +
                ", serviceName='" + serviceName + '\'' +
                ", server='" + server + '\'' +
                ", timestamp=" + timestamp +
                ", processed=" + processed +
                ", cached=" + cached +
                '}';
    }
}