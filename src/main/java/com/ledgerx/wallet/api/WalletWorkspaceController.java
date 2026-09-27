package com.ledgerx.wallet.api;

import com.ledgerx.access.OwnerContext;
import com.ledgerx.access.OwnerContextResolver;
import com.ledgerx.access.OwnerIdentityException;
import com.ledgerx.api.MoneyResponse;
import com.ledgerx.ledger.application.LedgerBalanceQueryService;
import com.ledgerx.ledger.domain.AccountStatus;
import com.ledgerx.ledger.domain.LedgerAccount;
import com.ledgerx.ledger.persistence.LedgerAccountRepository;
import com.ledgerx.money.CurrencyCode;
import com.ledgerx.wallet.domain.OwnerStatus;
import com.ledgerx.wallet.domain.OwnerType;
import com.ledgerx.wallet.domain.WalletOwner;
import com.ledgerx.wallet.persistence.WalletOwnerRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Owner-scoped wallet identity and derived balances for the workbench. */
@RestController
@RequestMapping("/api/v1/me")
public class WalletWorkspaceController {

  private final OwnerContextResolver ownerContextResolver;
  private final WalletOwnerRepository ownerRepository;
  private final LedgerAccountRepository accountRepository;
  private final LedgerBalanceQueryService balanceQueryService;

  public WalletWorkspaceController(
      OwnerContextResolver ownerContextResolver,
      WalletOwnerRepository ownerRepository,
      LedgerAccountRepository accountRepository,
      LedgerBalanceQueryService balanceQueryService) {
    this.ownerContextResolver = ownerContextResolver;
    this.ownerRepository = ownerRepository;
    this.accountRepository = accountRepository;
    this.balanceQueryService = balanceQueryService;
  }

  @GetMapping
  @Transactional(readOnly = true)
  public WorkspaceResponse find(
      @RequestHeader(value = "X-LedgerX-Owner-Id", required = false) String ownerHeader,
      @AuthenticationPrincipal Jwt authenticatedToken) {
    OwnerContext context = ownerContextResolver.resolve(ownerHeader, authenticatedToken);
    WalletOwner owner =
        ownerRepository
            .findById(context.ownerId())
            .orElseThrow(() -> new OwnerIdentityException("wallet owner was not found"));
    List<WalletResponse> wallets =
        accountRepository.findAllByOwnerIdOrderByCreatedAtAsc(owner.id()).stream()
            .map(account -> toResponse(account))
            .toList();
    return new WorkspaceResponse(owner.id(), owner.ownerType(), owner.status(), wallets);
  }

  private WalletResponse toResponse(LedgerAccount account) {
    return new WalletResponse(
        account.id(),
        account.currency(),
        account.status(),
        MoneyResponse.from(balanceQueryService.balanceOf(account)));
  }

  public record WorkspaceResponse(
      UUID ownerId, OwnerType ownerType, OwnerStatus status, List<WalletResponse> wallets) {}

  public record WalletResponse(
      UUID walletId, CurrencyCode currency, AccountStatus status, MoneyResponse balance) {}
}
