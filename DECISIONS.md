# Engineering Decisions

## Assumptions

- The Pricing API's 4xx responses (other than 429) are treated as **permanent** failures for the
  given request (e.g. unknown product, malformed input): retrying the exact same request would not
  change the outcome. 429 and 5xx, plus connection/timeout errors, are treated as **temporary**.
- "A stable ID as soon as the order is accepted" means the order is persisted **before** any pricing
  attempt is made, so the id survives regardless of what the Pricing API does afterwards.
- A store re-submitting the *same logical order* is out of scope for this exercise: there is no
  idempotency key on `POST /api/orders` in the starter contract, and the CR explicitly frames the
  problem as "the store must not be forced to resubmit," which we solve by making the *first*
  accepted request durable and retried automatically, not by deduplicating repeated submissions.
- "Enough information to understand later why an order remained unconfirmed" is satisfied with a
  human-readable `pricingFailureReason`, an attempt counter, and (while pending) the next scheduled
  attempt time - exposed via the API and the dashboard. A production system would want a structured,
  timestamped history instead of a single last-reason string (see "What I would change").
- Retrying is safe to do automatically for GET-only, side-effect-free pricing lookups. No idempotency
  concerns on the provider side since `/v1/prices/{productId}` is a read.

## Important decisions and trade-offs

- **Order states**: `PENDING_PRICING` -> `CONFIRMED` or `PRICING_FAILED`. Kept to three states
  instead of e.g. separate "retrying" / "awaiting first attempt" states, because the distinction
  the customer actually asked for (CR item 6) is "priced / not yet priced / needs attention" - a
  finer state machine would add complexity without adding decisions a store or support user needs
  to make.
- **First attempt is synchronous, inside the `POST /api/orders` request**, with a short timeout
  (2s connect / 3s read). This keeps the common happy path simple (`CONFIRMED` in the same
  response) while still satisfying "a temporary problem must not force resubmission," because a
  failure of that first attempt does not fail the request - it just leaves the order
  `PENDING_PRICING`. The trade-off is that `POST /api/orders` latency is coupled to the Pricing
  API's response time on the happy path; see "100x traffic" below for what I'd change.
- **Background retry via `@Scheduled` polling** (fixed delay, default 5s) instead of a proper job
  queue. It's the smallest mechanism that satisfies "orders survive an outage of several minutes"
  for a single-instance, in-memory exercise. It does not scale to multiple instances (see
  limitations) and is not durable across restarts (in-memory repository).
- **Exponential backoff, capped, with a max automatic attempt count** (default 5 attempts, 5s/10s/
  20s/40s/60s...). Prevents hammering a struggling provider while bounding how long an order stays
  silently pending before a human is alerted via `PRICING_FAILED`.
- **Explicit manual retry** (`POST /api/orders/{id}/retry-pricing`, and a "Retry now" button in the
  dashboard, both plain HTML form submissions) is allowed on any non-`CONFIRMED` order and is not
  subject to the backoff timer or the max-attempts cap - a human already decided to intervene, so
  the system should not second-guess that with its own rate limiting.
- **4xx vs 5xx classification lives in the pricing client, not the service.** `OrderService` only
  ever sees two exception types (`PricingTemporarilyUnavailableException`,
  `PricingRejectedException`); it doesn't know or care about HTTP status codes. This keeps the
  retry/backoff policy testable without an HTTP layer (see `OrderServiceTest`, which uses a scripted
  in-memory `PricingClient` fake instead of mocking HTTP).
- **Local price catalog removed entirely** rather than kept as a fallback. The CR is explicit that
  the external API is now the source of truth; silently falling back to stale local prices during
  an outage would violate CR item 7 (never present pricing as successful when it wasn't) even more
  directly than doing nothing.

## Pricing API observations that influenced the design

- The contract documents `4XX`/`5XX` only generically (no explicit list of which codes mean what),
  which is why the classification above is a judgment call, not something read off the spec.
  I treated 429 as temporary because it is semantically about *rate*, not about the request being
  wrong, even though it is a 4xx.
- The response body only carries `amount` as a string (not a typed number) and a `validUntil` on
  the quote; the current design does not yet act on `validUntil` (see limitations) - it stores the
  `quoteId` for traceability but treats every successful quote as immediately final for the order.

## Browser UI and operational feedback

- Status is communicated with **text labels first** ("Confirmed" / "Pending pricing" / "Needs
  attention"), not color alone, plus a small glyph as a secondary cue - satisfying the
  "don't communicate by color alone" constraint.
- The "Pricing detail" column exposes: attempt count, next automatic retry time (while pending), and
  the last failure reason (while pending or failed) - visible to a support user deciding whether to
  wait or intervene.
- Deliberately **not** exposed to the store/support user: internal exception messages, stack traces,
  or the raw HTTP status from the provider - only a short, human-written reason string. A support
  user needs "why," not implementation detail; a store user arguably needs even less, but the
  exercise's dashboard serves both audiences, so I kept it terse rather than splitting into two UIs.
- "Retry now" is shown for every order that is not yet `CONFIRMED` (both `PENDING_PRICING` and
  `PRICING_FAILED`), since there is no harm in letting someone nudge a still-pending order sooner
  than its scheduled backoff.

## Known limitations

- In-memory repository: state is lost on restart, and the scheduler only works correctly with a
  single application instance (two instances would each retry the same orders independently).
- No idempotency key on order creation: a genuine duplicate HTTP submission (e.g. a store's own
  retry logic firing twice) creates two orders. Out of scope per the assumptions above, but a real
  gap.
- `validUntil` on the price quote is not enforced; a `CONFIRMED` order keeps whatever price was
  quoted even if, in theory, that quote would have since expired.
- No authentication/authorization on the retry endpoint or the dashboard.
- The scheduler processes all due orders on every tick with no batching/limit; fine at this scale,
  not fine at high order volume.

## What I would change for production

- Replace the in-JVM `@Scheduled` poller with a durable, at-least-once job (outbox pattern or a
  real queue/scheduler), so retries survive restarts and work correctly with multiple instances.
- Persist orders in a real database with a status index, instead of the in-memory map, and add an
  idempotency key on `POST /api/orders`.
- Add circuit-breaking around the Pricing API (e.g. Resilience4j) so that during a full outage the
  application stops making synchronous first-attempt calls on the hot path and defers everything to
  the background job, instead of paying the connect/read timeout on every new order.
- Add metrics/alerting on `PRICING_FAILED` counts and on how long orders stay `PENDING_PRICING`, so
  an outage is visible operationally, not just in the per-order dashboard.
- Add authn/authz to the dashboard and the retry endpoint.

## AI-assisted work and validation

- This solution was built with Claude as a coding assistant, working from the challenge's own
  `README.md` and `CHANGE_REQUEST.md` as the spec, after reading the entire starter codebase
  (domain, service, repository, both controllers, template, existing tests, and the Pricing API
  OpenAPI contract) first.
- I directed the design decisions above (state model, sync-first-attempt-then-background-retry,
  4xx/5xx classification, explicit manual retry) rather than accepting a default; the AI proposed an
  initial version of the retry/backoff mechanics and the HTTP error classification, which I reviewed
  against the OpenAPI contract and adjusted.
- I could not run `mvn test` in the environment where this was drafted (no Maven/network access to
  Maven Central there), so **run `mvn test` locally before submitting** to confirm everything
  compiles and passes; fix anything that doesn't compile against your local Boot 3.4.4 setup, and
  say so honestly in the video if you had to adjust anything.
