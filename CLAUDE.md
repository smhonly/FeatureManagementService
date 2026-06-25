# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

A **docs-only** interview homework repo for an architect-level system-design round at an e-commerce company. There is **no source code, no build system, no tests** — only markdown/HTML design documents.

Original prompt: `docs/Align_Expert_Software_Engineer_R2_Quiz.md` (Feature Management Service, 100+ apps, 10k flags, 100K QPS, 5 areas: caching, SDK, APIs, observability, explainability).

## Current files

| Path                                    | Purpose                                                                                |
|-----------------------------------------|----------------------------------------------------------------------------------------|
| `docs/design.md`                        | **Canonical current design** — English markdown (~40KB, ~580 lines)                    |
| `docs/design.html`                      | Same design, Chinese, with inline SVG diagrams (~55KB)                                 |
| `docs/Align_Expert_Software_Engineer_R2_Quiz.md` | Original prompt — do not edit                                                 |
| `backup/design.md`, `backup/design.html`, `backup/DESIGN.html`, `backup/CLAUDE.md` | Earlier iterations, kept for reference only                                |

The two `docs/` files are the kept-in-sync canonical pair. **If you change the design, update both** (or be explicit about which one is source of truth for that change).

## Working conventions

This project was rejected in a real interview in 2026-06. Tone in this directory is raw — read the project's memory notes before proposing more iterations:

- `feature-management-service-project-context` — what kind of doc was rejected (over-elaborate production-grade vs wanted interview-grade)
- `interview-rejection-2026-06` — sentiment and how to handle revisits
- `doc-over-polishing-2026-06` — **recurring feedback pattern**: I keep over-polishing. Default to simplest Chinese, one idea per sentence, no unexplained abbreviations, no category-noun jargon

**Before any non-trivial edit, ask the user what they're trying to do** — mock-interview prep? Diagnose the rejection? Iterate a section? A new question? Don't assume they want another polish pass.

## The 3 hard problems (what the design hinges on)

The doc deliberately emphasizes these over breadth:

1. **Stable hash bucketing + salt** — local percentage rollout, same user always same bucket, buckets independent across flags
2. **Push + TTL hybrid cache consistency** — Redis Pub/Sub for critical flags (sub-second), polling timer fallback (Pub/Sub can drop messages)
3. **Deterministic replay for explainability** — store flag definition versions (~50–500 MB over 7 years) not evaluation results (864 GB/day); replay on demand via `/explain`

## Current architecture decisions (don't re-litigate without asking)

- **Local evaluation + cached definitions** — SDK caches 10k flag definitions in memory; `isEnabled()` never hits the network
- **Cross-region**: write only in primary (us-east), CDC → Kafka fan-out, each region runs full stack (Consumer → Redis → Snapshot API → CDN → SDK)
- **Per-app snapshots** — Snapshot API filters by `app_id` (resolved from api_key via HMAC); `flag_app_scopes(flag_key, env, app_id)` table governs visibility. CDN cache key is `(env, app)`. Side benefit: cross-team flag key collisions are solved.
- **Two-layer auth**: machine auth via HMAC-hashed api_key (`server_secret` lives in Vault); user auth via SSO/OIDC for control-plane audit (`actor` ≠ api_key)
- **`/explain` API** lives on the control plane, not the data plane

## How to run / build / test

**There is nothing to run.** No `package.json`, no `pyproject.toml`, no Makefile, no test suite. The repo's "build" is keeping `docs/design.md` and `docs/design.html` in sync; "lint" is reading them for jargon and over-polish.

`.claude/settings.local.json` only allows the `xxd` Bash subcommand. Anything beyond `xxd` requires explicit user approval — and for this repo, **no shell commands are typically needed** since the work is doc editing via Edit/Write tools.