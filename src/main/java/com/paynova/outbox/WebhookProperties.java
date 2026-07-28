package com.paynova.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Webhook target and signing configuration. Mutable setters are intentional: integration tests must repoint to a random port at runtime. */
@Component
@ConfigurationProperties(prefix = "paynova.webhook")
public class WebhookProperties {

    private String url = "http://localhost:8080/api/webhooks/mock-merchant";
    private String secret = "sandbox-webhook-secret-no-real-value";

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
}
