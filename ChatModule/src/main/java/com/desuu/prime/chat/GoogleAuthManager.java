package com.desuu.prime.chat;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class GoogleAuthManager {
    private static final Logger logger = LoggerFactory.getLogger(GoogleAuthManager.class);
    private static GoogleAuthManager instance;

    private final GoogleCredentials credentials;
    private final AtomicReference<AccessToken> currentToken = new AtomicReference<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     * Private constructor to enforce singleton pattern.
     * @param credentials The loaded Google credentials.
     */
    private GoogleAuthManager(GoogleCredentials credentials) {
        this.credentials = credentials;
        this.refreshToken(); // Fetch initial token
        // Schedule token refresh 5 minutes before expiry
        long delay = 55; // minutes
        this.scheduler.scheduleAtFixedRate(this::refreshToken, delay, delay, TimeUnit.MINUTES);
    }

    /**
     * Initializes the singleton instance of the GoogleAuthManager.
     * This method must be called once at application startup.
     *
     * @param credentialsPath Optional path to a service account JSON file. If null or blank,
     *                        Application Default Credentials (ADC) will be used.
     * @throws IOException If loading credentials fails.
     */
    public static synchronized void init(String credentialsPath) throws IOException {
        if (instance != null) {
            logger.warn("GoogleAuthManager has already been initialized.");
            return;
        }

        GoogleCredentials creds;
        if (credentialsPath != null && !credentialsPath.isBlank()) {
            // Load credentials from the specified file path
            logger.info("Loading Google credentials from path: {}", credentialsPath);
            try (FileInputStream fis = new FileInputStream(credentialsPath)) {
                creds = GoogleCredentials.fromStream(fis);
            }
        } else {
            // Fall back to Application Default Credentials
            logger.info("Loading Google Application Default Credentials.");
            creds = GoogleCredentials.getApplicationDefault();
        }

        // Ensure the necessary scope for Vertex AI is present
        if (creds.createScopedRequired()) {
            creds = creds.createScoped(Collections.singleton("https://www.googleapis.com/auth/cloud-platform"));
        }

        instance = new GoogleAuthManager(creds);
    }

    /**
     * Gets the singleton instance. Throws if init() has not been called.
     */
    public static GoogleAuthManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("GoogleAuthManager has not been initialized. Call init() from your main method.");
        }
        return instance;
    }

    /**
     * Refreshes the access token and stores it.
     */
    private void refreshToken() {
        try {
            logger.debug("Refreshing Google Cloud access token...");
            credentials.refresh();
            AccessToken newToken = credentials.getAccessToken();
            this.currentToken.set(newToken);
            if (newToken != null) {
                logger.info("Fetched new GCP access token, expires at {}", newToken.getExpirationTime());
            }
        } catch (IOException e) {
            logger.error("Error refreshing Google Cloud access token", e);
        }
    }

    /**
     * Gets the current, valid access token string.
     * @return The token string, or null if not available.
     */
    public static String getAccessToken() {
        AccessToken token = getInstance().currentToken.get();
        return (token != null) ? token.getTokenValue() : null;
    }
}