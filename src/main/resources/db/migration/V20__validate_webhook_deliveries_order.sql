-- Validates V19's CHECK under SHARE UPDATE EXCLUSIVE, which lets deliveries be written and sent
-- while the existing rows are scanned.
ALTER TABLE webhook_deliveries VALIDATE CONSTRAINT webhook_deliveries_order;
