package com.paynova.account;

import com.paynova.idempotency.IdempotencyKeys;
import com.paynova.idempotency.IdempotencyService;
import com.paynova.idempotency.RequestHash;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * §9 API #3: simulated top-up. Sandbox funds — no real monetary value.
 * Idempotency-Key is mandatory since Step 3; the same key also serves as the ledger
 * reference_id, forming two lines of defense against duplicates: request-level
 * (idempotency_records) plus ledger-level (uq_ledger_business).
 */
@RestController
@RequestMapping("/api/wallets")
public class WalletController {

    public record TopUpRequest(@NotNull Long amount_cents) {}

    private final AccountService accountService;
    private final IdempotencyService idempotencyService;

    public WalletController(AccountService accountService, IdempotencyService idempotencyService) {
        this.accountService = accountService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/top-ups")
    public ResponseEntity<Map<String, Object>> topUp(
            @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String keyHeader,
            @Valid @RequestBody TopUpRequest request,
            Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        UUID key = IdempotencyKeys.parse(keyHeader);
        String hash = RequestHash.of("POST", "/api/wallets/top-ups", request);

        IdempotencyService.Result result = idempotencyService.execute(userId, key, hash, () -> {
            Account wallet = accountService.topUp(userId, request.amount_cents(), key.toString());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("account_id", wallet.getId());
            body.put("balance_cents", wallet.getBalance());
            body.put("currency", wallet.getCurrency());
            return new IdempotencyService.Result(201, body, String.valueOf(wallet.getId()));
        });
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
