package com.paynova.account;

import com.paynova.ledger.LedgerEntry;
import com.paynova.ledger.LedgerEntryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** §9 API #4/#5: balance and ledger transaction history (read-only). */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final LedgerEntryRepository entryRepository;

    public AccountController(AccountService accountService, LedgerEntryRepository entryRepository) {
        this.accountService = accountService;
        this.entryRepository = entryRepository;
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(Authentication authentication) {
        Account wallet = accountService.walletOf((Long) authentication.getPrincipal());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("account_id", wallet.getId());
        body.put("name", wallet.getName());
        body.put("currency", wallet.getCurrency());
        body.put("balance_cents", wallet.getBalance());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/me/transactions")
    public ResponseEntity<Map<String, Object>> transactions(Authentication authentication,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        Account wallet = accountService.walletOf((Long) authentication.getPrincipal());
        List<LedgerEntry> entries = entryRepository.findByAccountIdOrderByIdDesc(
                wallet.getId(), PageRequest.of(page, Math.min(size, 100)));
        List<Map<String, Object>> items = entries.stream().map(e -> {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("transaction_id", e.getTransactionId());
            item.put("direction", e.getDirection().name());
            item.put("amount_cents", e.getAmountCents());
            item.put("currency", e.getCurrency().trim());
            item.put("created_at", e.getCreatedAt());
            return item;
        }).toList();
        return ResponseEntity.ok(Map.of("page", page, "items", items));
    }
}
