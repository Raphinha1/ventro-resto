# Ventro Resto

Ventro Resto is a portfolio snapshot of a server-rendered restaurant operations application. It brings together menu/catalog management, recipes and sub-recipes, point of sale, kitchen display, tables and reservations, attendance, cash shifts, reporting, and staged inventory workflows in one Spring Boot application.

This repository is source-only. It is **not a production-ready deployment**, does not include real restaurant data, uploaded files, credentials, certificates, build output, or the original Git history. It represents a single restaurant and branch per application/database deployment; it is not a shared multi-tenant SaaS platform.

## Implemented areas

- Java 21 / Spring Boot MVC with Thymeleaf, Spring Security, JPA/Hibernate, and PostgreSQL.
- Product, ingredient, category, recipe, and sub-recipe workflows.
- POS and kitchen display flows, cash shifts, table service, reservations, attendance, and reporting.
- Flyway migrations V1–V13; Hibernate validates the schema rather than changing it automatically.
- Inventory reconciliation/application, purchase receipt, and receipt-reversal foundations and web flows. Receipt confirmation depends on explicit currency configuration; the inventory workflows have operational limits described below.
- Authorization and regression coverage for direct HTTP access, safer text rendering, and selected concurrent updates.

The `ia` area uses application rules/heuristics; this snapshot does not claim a hosted machine-learning or external AI integration.

## Architecture

The application is a modular monolith: Spring MVC controllers and Thymeleaf views call service-layer use cases, repositories persist domain entities through JPA, and Flyway owns schema evolution. POS, reservations, attendance, and inventory use transactional boundaries and selected idempotency/locking mechanisms. PostgreSQL 15 is the intended database.

## Local demo configuration

Requirements: Java 21, Docker Compose, and a local `.env` created from the placeholder template. The sample binds the application to `127.0.0.1`, uses a local PostgreSQL container, and stores demo uploads under an ignored runtime directory. It is intended for a disposable local demo only.

1. Copy `.env.example` to `.env`. Fill the blank secret values with unique local-only values. Generate `VENTRO_ATTENDANCE_PIN_PEPPER` as Base64 for at least 32 random bytes. Set a bootstrap admin password of at least 16 characters when using an empty database.
2. Start the example with `docker compose -p ventro-resto-demo up --build`.
3. Open `http://127.0.0.1:8080`.
4. Stop it with `docker compose -p ventro-resto-demo down`. The named demo database volume remains; remove it only if you intentionally want to discard the demo database.

Do not point this example at an existing database or upload directory. `.env` is ignored and must not be committed. The example does not configure TLS, backups, monitoring, secret rotation, or production operations.

For Maven tests, use a disposable PostgreSQL 15 database and set `DB_URL`, `DB_USER`, `DB_PASSWORD`, `VENTRO_RESTAURANT_ID`, `VENTRO_BRANCH_ID`, `VENTRO_ATTENDANCE_PIN_PEPPER`, `UPLOAD_DIR`, and bootstrap values for an empty database before running `mvn clean verify`. Never use a personal or operational database.

The stabilized candidate passed one isolated PostgreSQL 15.14 `mvn clean verify` run: 266 passed, 0 failed, 2 opt-in tests skipped. The three browser/client smokes for KDS/reservations, POS/tables, and reversals passed. Snapshot preparation excludes uncertain visual/audio assets and adjusts their references; those presentation-only adjustments were not followed by a second full suite.

## Known limits and deployment cautions

- A single deployment/database represents one restaurant and branch. No shared-tenant isolation claim is made.
- Some third-party UI libraries/fonts load from public CDNs. The menu QR action calls a public QR service with the current menu URL. Review those requests and privacy implications before adapting the app.
- WhatsApp/SMS delivery is not implemented in this snapshot. Its adapter is a no-op: it sends no network requests and does not log destination phone numbers or message text.
- The KDS notification sound and two unverified brand SVGs are excluded from this snapshot. The affected screens use text wordmarks; the KDS is silent.
- No production hardening review, threat model, operational restore rehearsal, or independent trademark clearance is provided here.
- Inventory activation/receipt/reversal workflows have domain and operational constraints; see `docs/PUBLICATION_LIMITATIONS.md`. Do not enable real inventory use based only on this portfolio snapshot.
- The project name/word mark is used only to identify the source project. No trademark clearance is claimed.

## Tests and browser smokes

The `src/test` tree contains JVM and PostgreSQL-backed regression tests. `scripts/` includes self-contained browser/client smoke checks using inert fixtures; these do not replace the database-backed test suite or production verification. The full test suite requires a throwaway PostgreSQL instance and fictitious data.

## Rights

`LICENSE` applies only to original project-authored code. Dependencies and remote third-party resources remain under their own licenses; see `docs/THIRD_PARTY_NOTICES.md`. The uncertain logo graphics and external KDS audio are excluded and documented separately in `docs/AI_GENERATED_ASSETS.md`.
