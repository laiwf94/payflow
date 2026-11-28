-- Each tender records the routing decision that chose its provider: the id of the rule that
-- matched it, or 'default' when none did (payflow.routing). The provider column has said where a
-- tender's money is since V30; this says why, so an operator can tell a tender a rule sent to the
-- second provider from one that went there by default, after the rules have changed.
--
-- A constant default makes the new column a catalog change with no table rewrite, and every
-- existing tender, and every tender an older binary's insert trigger writes during a rolling
-- upgrade, gets 'default', which is what happened to it. The format check is added NOT VALID
-- under the brief ACCESS EXCLUSIVE lock of ADD COLUMN and validated in V36, without blocking
-- writes.

ALTER TABLE payments
    ADD COLUMN routing_rule text NOT NULL DEFAULT 'default',
    ADD CONSTRAINT payments_routing_rule_format
        CHECK (routing_rule ~ '^[a-z][a-z0-9_-]{0,63}$') NOT VALID;
