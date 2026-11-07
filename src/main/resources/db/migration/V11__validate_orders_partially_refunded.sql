-- VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE, which doesn't block reads or writes, so it
-- runs in its own migration rather than inside V10's exclusive lock.

ALTER TABLE orders
    VALIDATE CONSTRAINT orders_known_state,
    VALIDATE CONSTRAINT orders_legal_transition,
    VALIDATE CONSTRAINT orders_completed_fully_captured,
    VALIDATE CONSTRAINT orders_partially_refunded_in_part,
    VALIDATE CONSTRAINT orders_terminal_unscheduled,
    VALIDATE CONSTRAINT orders_terminal_unleased;
