-- VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE, which doesn't block reads or writes, so it
-- runs in its own migration rather than inside V14's exclusive lock. V14 refused to commit while
-- any order was REFUNDING, so every existing row passes.

ALTER TABLE orders
    VALIDATE CONSTRAINT orders_known_state,
    VALIDATE CONSTRAINT orders_legal_transition,
    VALIDATE CONSTRAINT orders_completed_fully_captured;
