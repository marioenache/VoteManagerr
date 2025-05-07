package ro.marioenache.votemanager.velocity.listener;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import ro.marioenache.votemanager.common.model.VoteRequest;
import ro.marioenache.votemanager.velocity.VoteManagerVelocity;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Votifier listener implementation that supports both V1 (RSA) and V2 (JSON) protocol formats
 */
public class VotifierListener {
    private final VoteManagerVelocity plugin;
    private static final MinecraftChannelIdentifier VOTIFIER_CHANNEL = 
            MinecraftChannelIdentifier.from("votifierplus:main");
    
    // Pattern to match VotifierPlus log format
    private static final Pattern VOTE_PATTERN = Pattern.compile(
        "\\[votifierplus\\]: Received vote record -> Vote \\(from:([^ ]+) .* username:([^ ]+) .*\\)"
    );
    
    private ServerSocket serverSocket;
    private final ExecutorService threadPool;
    private KeyPair keyPair;
    private volatile boolean running = true;
    
    private File keysFolder;
    private File publicKeyFile;
    private File privateKeyFile;
    
    private int port = 25567;
    private String host = "0.0.0.0";
    
    private static final Gson gson = new Gson();

    /**
     * Create a new vote listener
     * 
     * @param plugin The plugin instance
     */
    public VotifierListener(VoteManagerVelocity plugin) {
        this.plugin = plugin;
        this.threadPool = Executors.newCachedThreadPool();
        
        // Load configuration
        loadConfiguration();
        
        // Setup keys directory
        setupKeysDirectory();
        
        // Load or generate key pair
        this.keyPair = loadOrGenerateKeyPair();
        
        // Start vote listener server
        try {
            start();
        } catch (Exception e) {
            plugin.getLogger().info("Could not start vote listener server: " + e.getMessage());
            if (plugin.isDebugEnabled()) {
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Load votifier configuration from the config
     */
    private void loadConfiguration() {
        // Get configuration from velocity config manager
        this.port = plugin.getConfigManager().getVotifierPort();
        this.host = plugin.getConfigManager().getVotifierHost();
        
        plugin.getLogger().info("Votifier configuration: host=" + host + ", port=" + port);
    }
    
    /**
     * Setup the keys directory
     */
    private void setupKeysDirectory() {
        // Create keys folder if it doesn't exist
        keysFolder = new File(plugin.getDataDirectory().toFile(), "votifier");
        if (!keysFolder.exists()) {
            keysFolder.mkdirs();
        }
        
        publicKeyFile = new File(keysFolder, "public.key");
        privateKeyFile = new File(keysFolder, "private.key");
        
        plugin.getLogger().info("Keys directory: " + keysFolder.getAbsolutePath());
    }
    
    /**
     * Load existing key pair or generate a new one
     * 
     * @return The key pair
     */
    private KeyPair loadOrGenerateKeyPair() {
        // Check if key files exist
        if (publicKeyFile.exists() && privateKeyFile.exists()) {
            try {
                // Load existing keys
                plugin.getLogger().info("Found existing key files, attempting to load...");
                return loadKeyPair();
            } catch (Exception e) {
                plugin.getLogger().info("Could not load existing key pair: " + e.getMessage());
                plugin.getLogger().info("Generating new key pair...");
                if (plugin.isDebugEnabled()) {
                    e.printStackTrace();
                }
            }
        } else {
            plugin.getLogger().info("Key files not found, generating new key pair...");
        }
        
        // Generate new key pair
        KeyPair pair = generateKeyPair();
        
        // Save key pair
        try {
            saveKeyPair(pair);
        } catch (Exception e) {
            plugin.getLogger().info("Could not save key pair: " + e.getMessage());
            if (plugin.isDebugEnabled()) {
                e.printStackTrace();
            }
        }
        
        return pair;
    }
    
    /**
     * Generate a new key pair for vote verification
     * 
     * @return The key pair
     */
    private KeyPair generateKeyPair() {
        try {
            plugin.getLogger().info("Generating new RSA key pair for Votifier");
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            KeyPair pair = keyPairGenerator.generateKeyPair();
            
            plugin.getLogger().info("Successfully generated new RSA key pair");
            
            return pair;
        } catch (NoSuchAlgorithmException e) {
            plugin.getLogger().info("Could not generate key pair: " + e.getMessage());
            if (plugin.isDebugEnabled()) {
                e.printStackTrace();
            }
            throw new RuntimeException("Failed to generate key pair", e);
        }
    }
    
    /**
     * Save the key pair to files
     * 
     * @param keyPair The key pair to save
     * @throws IOException If the keys cannot be saved
     */
    private void saveKeyPair(KeyPair keyPair) throws IOException {
        plugin.getLogger().info("Saving RSA key pair to " + keysFolder.getAbsolutePath());
        
        // Save public key
        try (FileOutputStream fos = new FileOutputStream(publicKeyFile)) {
            byte[] encoded = keyPair.getPublic().getEncoded();
            String b64 = Base64.getEncoder().encodeToString(encoded);
            fos.write(b64.getBytes(StandardCharsets.UTF_8));
            plugin.getLogger().info("Public key saved to: " + publicKeyFile.getAbsolutePath());
        }
        
        // Save private key
        try (FileOutputStream fos = new FileOutputStream(privateKeyFile)) {
            byte[] encoded = keyPair.getPrivate().getEncoded();
            String b64 = Base64.getEncoder().encodeToString(encoded);
            fos.write(b64.getBytes(StandardCharsets.UTF_8));
            plugin.getLogger().info("Private key saved to: " + privateKeyFile.getAbsolutePath());
        }
        
        // Set read-only permissions for private key
        boolean readOnly = privateKeyFile.setReadOnly();
        plugin.getLogger().info("Set private key file to read-only: " + readOnly);
        
        plugin.getLogger().info("Successfully saved RSA key pair");
    }
    
    /**
     * Load the key pair from files
     * 
     * @return The loaded key pair
     * @throws Exception If the keys cannot be loaded
     */
    private KeyPair loadKeyPair() throws Exception {
        plugin.getLogger().info("Loading RSA key pair from " + keysFolder.getAbsolutePath());
        
        // Load public key
        String publicKeyContent = Files.readString(publicKeyFile.toPath(), StandardCharsets.UTF_8).trim();
        byte[] encodedPublicKey = Base64.getDecoder().decode(publicKeyContent);
        
        // Load private key
        String privateKeyContent = Files.readString(privateKeyFile.toPath(), StandardCharsets.UTF_8).trim();
        byte[] encodedPrivateKey = Base64.getDecoder().decode(privateKeyContent);
        
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        
        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(encodedPublicKey);
        PublicKey publicKey = keyFactory.generatePublic(publicKeySpec);
        
        PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(encodedPrivateKey);
        PrivateKey privateKey = keyFactory.generatePrivate(privateKeySpec);
        
        plugin.getLogger().info("Successfully loaded RSA key pair");
        
        return new KeyPair(publicKey, privateKey);
    }
    
    /**
     * Start the vote listener server
     * 
     * @throws IOException If the server socket cannot be created
     */
    public void start() throws IOException {
        plugin.getLogger().info("Starting vote listener server...");
        
        if (serverSocket != null && !serverSocket.isClosed()) {
            plugin.getLogger().info("Closing existing server socket");
            serverSocket.close();
        }
        
        // Create new server socket
        InetSocketAddress address = new InetSocketAddress(host, port);
        serverSocket = new ServerSocket();
        serverSocket.bind(address);
        
        plugin.getLogger().info("Vote listener server started on " + host + ":" + port);
        
        // Start server thread
        threadPool.submit(() -> {
            plugin.getLogger().info("Vote listener thread started");
            while (running && !serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    String clientAddress = socket.getInetAddress().getHostAddress();
                    plugin.getLogger().info("Received connection from " + clientAddress);
                    
                    socket.setSoTimeout(5000); // 5 second timeout
                    
                    // Handle socket in a separate thread
                    threadPool.submit(() -> handleVoteConnection(socket));
                } catch (IOException e) {
                    if (running) {
                        plugin.getLogger().info("Error accepting vote connection: " + e.getMessage());
                        if (plugin.isDebugEnabled()) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            plugin.getLogger().info("Vote listener thread stopped");
        });
    }
    
    /**
     * Stop the vote listener server
     */
    public void stop() {
        plugin.getLogger().info("Stopping vote listener server...");
        running = false;
        
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                plugin.getLogger().info("Server socket closed");
            }
        } catch (IOException e) {
            plugin.getLogger().info("Error closing vote listener server: " + e.getMessage());
            if (plugin.isDebugEnabled()) {
                e.printStackTrace();
            }
        }
        
        threadPool.shutdown();
        plugin.getLogger().info("Thread pool shutdown initiated");
    }
    
    /**
     * Handle a vote connection
     * 
     * @param socket The socket to handle
     */
    private void handleVoteConnection(Socket socket) {
        String clientAddress = socket.getInetAddress().getHostAddress();
        plugin.getLogger().info("Processing vote connection from " + clientAddress);
        
        try {
            // Initialize input/output streams
            InputStream inputStream = socket.getInputStream();
            OutputStream outputStream = socket.getOutputStream();
            
            // Send votifier greeting - must specify "VOTIFIER 1"
            String greeting = "VOTIFIER 1";
            outputStream.write((greeting + "\r\n").getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            plugin.getLogger().info("Sent greeting: " + greeting);
            
            // Prepare to read the 256-byte vote block (RSA encrypted)
            byte[] voteBlock = new byte[256];
            
            // Try to read the entire vote block
            int totalRead = 0;
            while (totalRead < voteBlock.length) {
                int bytesRead = inputStream.read(voteBlock, totalRead, voteBlock.length - totalRead);
                if (bytesRead == -1) {
                    break; // End of stream
                }
                totalRead += bytesRead;
                plugin.getLogger().info("Read " + bytesRead + " bytes, total: " + totalRead);
            }
            
            // Only process if we received the full block
            if (totalRead == 256) {
                plugin.getLogger().info("Received complete vote block, attempting to decrypt");
                
                // Decrypt the vote block using RSA
                byte[] decrypted;
                try {
                    decrypted = decrypt(voteBlock, keyPair.getPrivate());
                    plugin.getLogger().info("Successfully decrypted vote block");
                } catch (Exception e) {
                    plugin.getLogger().info("Failed to decrypt vote block: " + e.getMessage());
                    if (plugin.isDebugEnabled()) {
                        e.printStackTrace();
                    }
                    return;
                }
                
                // Parse the decrypted data (format: "VOTE\nservice\nusername\naddress\ntimestamp\n")
                String decryptedString = new String(decrypted, StandardCharsets.UTF_8);
                String[] voteData = decryptedString.split("\n");
                
                if (voteData.length >= 5 && "VOTE".equals(voteData[0])) {
                    String serviceName = voteData[1];
                    String username = voteData[2];
                    String address = voteData[3];
                    String timestamp = voteData[4];
                    
                    plugin.getLogger().info("Decrypted vote: service=" + serviceName + 
                                           ", username=" + username + 
                                           ", address=" + address + 
                                           ", timestamp=" + timestamp);
                    
                    // Create and process the vote
                    VoteRequest voteRequest = new VoteRequest(serviceName, username, address, timestamp);
                    processVote(voteRequest);
                    
                    // Send a simple OK response with proper error handling
                    try {
                        outputStream.write("OK\r\n".getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                    } catch (IOException e) {
                        plugin.getLogger().info("Failed to send OK response: " + e.getMessage());
                        // Continue processing the vote even if we can't send the response
                        if (plugin.isDebugEnabled()) {
                            e.printStackTrace();
                        }
                    }
                } else {
                    plugin.getLogger().info("Malformed vote data: " + decryptedString);
                }
            } else {
                plugin.getLogger().info("Incomplete vote block received: " + totalRead + " bytes");
            }
        } catch (Exception e) {
            plugin.getLogger().info("Error handling vote connection: " + e.getMessage());
            if (plugin.isDebugEnabled()) {
                e.printStackTrace();
            }
        } finally {
            try {
                socket.close();
                plugin.getLogger().info("Connection closed for client " + clientAddress);
            } catch (IOException e) {
                plugin.getLogger().info("Error closing socket: " + e.getMessage());
            }
        }
    }
    
    /**
     * Decrypt data using RSA
     * 
     * @param data The data to decrypt
     * @param privateKey The private key to use
     * @return The decrypted data
     * @throws Exception If decryption fails
     */
    private byte[] decrypt(byte[] data, PrivateKey privateKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return cipher.doFinal(data);
    }
    
    /**
     * Process a vote request
     * 
     * @param voteRequest The vote request
     */
    private void processVote(VoteRequest voteRequest) {
        plugin.processVotifierVote(voteRequest.getServiceName(), voteRequest.getUsername());
    }
    
    /**
     * Listen for plugin messages from VotifierPlus for backward compatibility
     */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (VOTIFIER_CHANNEL.equals(event.getIdentifier())) {
            try {
                ByteArrayInputStream in = new ByteArrayInputStream(event.getData());
                DataInputStream dataIn = new DataInputStream(in);
                
                // Read the message
                String message = dataIn.readUTF();
                
                if (plugin.isDebugEnabled()) {
                    plugin.getLogger().info("Received VotifierPlus message: " + message);
                }
                
                // Parse the vote information using regex
                Matcher matcher = VOTE_PATTERN.matcher(message);
                if (matcher.find()) {
                    String serviceName = matcher.group(1);
                    String username = matcher.group(2);
                    
                    plugin.getLogger().info("Extracted vote: service=" + serviceName + ", username=" + username);
                    
                    // Process the vote
                    plugin.processVotifierVote(serviceName, username);
                } else {
                    plugin.getLogger().warning("Could not parse vote from message: " + message);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error processing vote: " + e.getMessage());
                if (plugin.isDebugEnabled()) {
                    e.printStackTrace();
                }
            }
        }
    }
    
    /**
     * Process a vote directly (for API or testing)
     * 
     * @param serviceName The name of the voting service
     * @param username The name of the player who voted
     */
    public void processVote(String serviceName, String username) {
        plugin.processVotifierVote(serviceName, username);
    }
    
    /**
     * Get the public key for vote verification
     * 
     * @return The public key
     */
    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }
    
    /**
     * Get the public key as a base64 encoded string
     * 
     * @return The public key as a base64 encoded string
     */
    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }
    
    /**
     * Get the port the vote listener server is listening on
     * 
     * @return The port
     */
    public int getPort() {
        return port;
    }
    
    /**
     * Set the port the vote listener server is listening on
     * 
     * @param port The port
     * @throws IOException If the server socket cannot be restarted
     */
    public void setPort(int port) throws IOException {
        this.port = port;
        
        // Restart the server socket with the new port
        restart();
    }
    
    /**
     * Get the host the vote listener server is listening on
     * 
     * @return The host
     */
    public String getHost() {
        return host;
    }
    
    /**
     * Set the host the vote listener server is listening on
     * 
     * @param host The host
     * @throws IOException If the server socket cannot be restarted
     */
    public void setHost(String host) throws IOException {
        this.host = host;
        
        // Restart the server socket with the new host
        restart();
    }
    
    /**
     * Restart the vote listener server
     * 
     * @throws IOException If the server socket cannot be restarted
     */
    private void restart() throws IOException {
        stop();
        start();
    }
    
    /**
     * Get the location of the keys folder
     * 
     * @return The keys folder
     */
    public File getKeysFolder() {
        return keysFolder;
    }
}