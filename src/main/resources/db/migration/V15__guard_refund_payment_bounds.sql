-- Serialize refund inserts for one payment even when a writer bypasses the application service.
-- The service already takes this payment-row lock before posting its compensating journal.
CREATE FUNCTION enforce_refund_payment_bounds()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    paid_amount NUMERIC;
    paid_currency VARCHAR(3);
    paid_merchant_wallet_id UUID;
    paid_payer_wallet_id UUID;
    paid_journal_id UUID;
    refunded_amount NUMERIC;
BEGIN
    SELECT amount, currency, merchant_wallet_account_id, payer_wallet_account_id,
           ledger_transaction_id
    INTO paid_amount, paid_currency, paid_merchant_wallet_id, paid_payer_wallet_id,
         paid_journal_id
    FROM payments
    WHERE id = NEW.payment_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN NEW; -- The existing foreign key rejects an unknown payment.
    END IF;

    IF NEW.merchant_wallet_account_id IS DISTINCT FROM paid_merchant_wallet_id
        OR NEW.payer_wallet_account_id IS DISTINCT FROM paid_payer_wallet_id
        OR NEW.currency IS DISTINCT FROM paid_currency THEN
        RAISE EXCEPTION 'refund participants differ from payment'
            USING ERRCODE = '23514';
    END IF;

    SELECT COALESCE(SUM(amount), 0)
    INTO refunded_amount
    FROM refunds
    WHERE payment_id = NEW.payment_id;

    IF refunded_amount + NEW.amount > paid_amount THEN
        RAISE EXCEPTION 'refund total exceeds payment amount'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.ledger_transaction_id = paid_journal_id THEN
        RAISE EXCEPTION 'refund cannot reuse payment journal'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER refunds_payment_bounds_guard
BEFORE INSERT ON refunds
FOR EACH ROW
EXECUTE FUNCTION enforce_refund_payment_bounds();
