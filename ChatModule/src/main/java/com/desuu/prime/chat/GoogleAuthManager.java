package com.desuu.prime.chat;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.AccessToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class GoogleAuthManager {
    private static final Logger logger = LoggerFactory.getLogger(GoogleAuthManager.class);
    private static GoogleAuthManager instance;
    private GoogleCredentials credentials;
    private volatile AccessToken currentToken;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private GoogleAuthManager() { /* private constructor for singleton */ }

    /** Initialize on first use */
    public static synchronized GoogleAuthManager getInstance() {
        if (instance == null) {
            instance = new GoogleAuthManager();
            instance.initCredentials();
        }
        return instance;
    }

    private void initCredentials() {
        try {
            // Load ADC (uses default service account on GCP if available)
            credentials = GoogleCredentials.getApplicationDefault();
            if (credentials.createScopedRequired()) {
                // Ensure Cloud Platform scope for Vertex AI:contentReference[oaicite:5]{index=5}
                credentials = credentials.createScoped(
                        Collections.singleton("https://www.googleapis.com/auth/cloud-platform"));
            }
            // Fetch initial token
            refreshToken();
            // Schedule token refresh 5 minutes before expiry (or every 55 minutes as fallback)
            long delay = 55; // minutes
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    refreshToken();
                } catch (Exception ex) {
                    logger.error("Error refreshing Google Cloud access token", ex);
                }
            }, delay, delay, TimeUnit.MINUTES);
        } catch (IOException e) {
            logger.error("Failed to initialize Google ADC credentials", e);
        }
    }

    /** Force refresh the token immediately */
    private synchronized void refreshToken() throws IOException {
        // Request a new access token using the service account credentials
        currentToken = credentials.refreshAccessToken();
        if (currentToken != null) {
            logger.info("Fetched new GCP access token, expires at {}",
                    currentToken.getExpirationTime());
        }
    }

    /** Get the current access token string to use in API calls */
    public static String getAccessToken() {
        GoogleAuthManager mgr = getInstance();
        AccessToken token = mgr.currentToken;
        return (token != null) ? token.getTokenValue() : null;
    }
}
