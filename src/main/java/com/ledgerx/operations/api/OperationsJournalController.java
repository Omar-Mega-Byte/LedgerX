package com.ledgerx.operations.api;

import com.ledgerx.ledger.api.LedgerJournalController;
import com.ledgerx.ledger.api.LedgerJournalController.JournalResponse;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator-only journal lookup; access is guarded by the production filter chain. */
@RestController
@RequestMapping("/api/v1/operations/ledger-transactions")
public class OperationsJournalController {

  private final LedgerJournalController journalController;

  public OperationsJournalController(LedgerJournalController journalController) {
    this.journalController = journalController;
  }

  @GetMapping("/{transactionId}")
  @Transactional(readOnly = true)
  public JournalResponse find(@PathVariable UUID transactionId) {
    return journalController.findForOperator(transactionId);
  }
}
