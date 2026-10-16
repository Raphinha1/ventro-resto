# Publication and operational limits

This repository is a source-only portfolio snapshot, not a supported or production-ready product release. It excludes the original Git history, local environment files, uploaded content, runtime databases, backups, logs, build outputs, and private/operational documentation.

The stabilized source was verified once with PostgreSQL 15.14: `mvn clean verify` reported 266 passed, 0 failed, and 2 opt-in skips, followed by browser/client smokes for KDS/reservations, POS/tables, and receipt reversals. The snapshot preparation changed only local example configuration, rights-related asset references, and public documentation. No new full suite was run after those presentation/configuration changes.

Two logo SVGs and the external KDS sound were omitted because their provenance/redistribution terms were not established. Some third-party assets still load from CDNs, and the QR flow calls an external service with the current menu URL.

The WhatsApp/SMS adapter was replaced with a no-op in this snapshot: no provider call is made, and phone numbers/message bodies are not written to logs. This does not provide an implemented messaging integration.

The source has not undergone production acceptance, live data migration, restore rehearsal, trademark clearance, or a complete dependency-license/SBOM review for binary distribution. Inventory reconciliation, activation, receipt, and reversal paths retain explicit limits; they should not be enabled against real stock without a separately reviewed deployment and operational procedure.
