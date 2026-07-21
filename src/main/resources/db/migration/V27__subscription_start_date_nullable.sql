-- subscription.start_date benarkan NULL.
-- NULL = tiada had bawah; engine guna logik default (effStart = later(account.start, sub.start),
-- kalau dua-dua NULL maka tiada had bawah — jana untuk mana-mana period dalam ufuk).
-- Rujuk docs/domain/billing-rules.md §5.
ALTER TABLE account_subscription MODIFY COLUMN start_date DATE NULL;
