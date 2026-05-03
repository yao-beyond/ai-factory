# Codex Review Checklist

## Architecture
- Matches IMPLEMENTATION_PLAN.md
- No unexpected module boundary changes
- Backward compatibility is preserved

## Trading System Risk
- Funding rate precision and rounding
- Liquidation price and margin calculation
- Idempotency for order / settlement operations
- Concurrency and transaction boundaries
- BigDecimal scale and rounding mode explicitly defined

## Security
- No leaked tokens
- No unsafe logging of PII / auth / trade secrets
- Input validation and authorization checks

## Testing
- Unit tests for pure calculation
- Integration tests for DB / Redis / Kafka boundaries
- Regression tests for reported issue
