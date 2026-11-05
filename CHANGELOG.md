# Changelog

## 1.0.0 — 2026-11-06

First stable release. Release notes: [docs/releases/1.0.0.md](docs/releases/1.0.0.md).

- Gateway: native OpenAI (Chat Completions, Responses, Embeddings, Images, Audio, Rerank), Anthropic Messages and Gemini
  endpoints, converted to and from OpenAI-compatible, Azure OpenAI, Anthropic, Gemini, AWS Bedrock and Vertex AI upstreams
  in any direction, streaming and non-streaming.
- Pricing anchored to the official price: site markup, per-group and per-user "N% of official" negotiated prices,
  long-context tiers, per-request pricing; the charge is tokens × the displayed unit price, rounded per item.
- Channel cost (official discount, upstream pricing API, per-model manual) with a margin gate that rejects prices below cost,
  a price health check and a profit dashboard; scheduled official price sync with operator approval and price history.
- Two-phase ledger (reserve → settle) with crash recovery and a manual review queue; frozen pricing snapshots.
- Routing: priority and weight, failover on upstream errors including "model not found", key auto-disable and cooldown,
  scheduled probes, 10-minute request ceiling for long answers.
- Billing groups decided by the API key; API keys shown once and stored hashed; upstream keys encrypted at rest.
- Top-up by admins, redemption codes and pluggable online payment; menu- and button-level permissions with preset roles.
