package ro.marioenache.votemanager.common.model;

/**
 * Represents a vote request from a voting service
 */
public class VoteRequest {
    private final String serviceName;
    private final String username;
    private final String address;
    private final String timeStamp;

    /**
     * Create a new vote request
     * 
     * @param serviceName The name of the vote service
     * @param username The name of the player
     * @param address The address of the voting service
     * @param timeStamp The timestamp of the vote
     */
    public VoteRequest(String serviceName, String username, String address, String timeStamp) {
        this.serviceName = serviceName;
        this.username = username;
        this.address = address;
        this.timeStamp = timeStamp;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getUsername() {
        return username;
    }

    public String getAddress() {
        return address;
    }

    public String getTimeStamp() {
        return timeStamp;
    }

    @Override
    public String toString() {
        return "VoteRequest{" +
                "serviceName='" + serviceName + '\'' +
                ", username='" + username + '\'' +
                ", address='" + address + '\'' +
                ", timeStamp='" + timeStamp + '\'' +
                '}';
    }
}