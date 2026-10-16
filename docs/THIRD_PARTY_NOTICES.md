# Third-party notices

The repository contains project source and references dependencies; it does not include third-party JARs, web libraries, fonts, or generated bundles. This notice is a focused inventory of direct dependencies and client-side resources observed in the source, not a complete resolved dependency SBOM. Maven resolves transitive versions from the Spring Boot 3.2.4 dependency management. Before redistributing a built application, generate and review an SBOM and preserve all upstream notices for the exact resolved versions.

## Maven dependencies

| Component | Version/source | License information | Distribution in this snapshot |
|---|---|---|---|
| Spring Boot starters and Spring Security | Spring Boot 3.2.4 parent-managed | Apache License 2.0; [Spring Boot](https://github.com/spring-projects/spring-boot), [Spring Security](https://github.com/spring-projects/spring-security) | Referenced by Maven; binaries are not included |
| Thymeleaf and Spring Security extras | Parent-managed | Apache License 2.0; [Thymeleaf](https://github.com/thymeleaf/thymeleaf) | Referenced by Maven; binaries are not included |
| Flyway Core | Parent-managed (Spring Boot 3.2.4) | Apache License 2.0; [Flyway](https://github.com/flyway/flyway) | Referenced by Maven; binaries are not included |
| PostgreSQL JDBC driver | Parent-managed, runtime scope | BSD 2-Clause; [pgJDBC license](https://jdbc.postgresql.org/license/) | Referenced by Maven; binary is not included |
| OpenPDF | 1.3.30 | Dual-licensed LGPL-2.1 and MPL-2.0 upstream; [OpenPDF](https://github.com/LibrePDF/OpenPDF) | Referenced by Maven; binary is not included; review obligations before distributing a built JAR/container |
| Spring Boot test and Spring Security test starters | Parent-managed, test scope | See each resolved upstream component/license | Referenced for tests; binaries are not included |

## Browser resources loaded remotely

These are referenced by templates and are not copied into this repository. They require network access in the browser and may contact their providers:

| Resource | Observed reference | Upstream licensing/source |
|---|---|---|
| Bootstrap CSS/JS | 5.3.2 and 5.3.3 CDN URLs | MIT; [upstream](https://github.com/twbs/bootstrap) |
| Bootstrap Icons | 1.11.1 and 1.11.3 CDN URLs | MIT; [upstream](https://github.com/twbs/icons) |
| FullCalendar Standard bundle | 6.1.11 | MIT for Standard; [license](https://fullcalendar.io/license) |
| Chart.js | CDN URL without a pinned version | MIT upstream; [upstream](https://github.com/chartjs/Chart.js) |
| SweetAlert2 | `@11` CDN range | MIT upstream; [upstream](https://github.com/sweetalert2/sweetalert2) |
| Inter and Outfit fonts | Google Fonts CSS | Font files are not redistributed here; review each family’s upstream terms before self-hosting |

The menu QR feature sends the current menu URL to `api.qrserver.com`; this is an external runtime service, not a bundled dependency. Consider a local QR implementation before using non-public or sensitive URLs. The Mixkit-hosted KDS sound reference was removed from this snapshot due to unresolved redistribution/source terms. The WhatsApp/SMS adapter is intentionally a no-op in this snapshot; it has no provider URL or credential.

This notice does not grant additional rights in any third-party component. Retain upstream notices when downloading, bundling, or redistributing those materials.
