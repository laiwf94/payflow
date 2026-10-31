# payflow roadmap, November 2026 to October 2027

## Vision

By October 2027 payflow grows from a crash-safe checkout saga with one simulated provider into a self-hosted payment platform with feature parity, for a merchant's own use, with the core of Stripe's payments stack and with open-source orchestrators such as Hyperswitch: a payment intent lifecycle with partial, multiple and incremental captures, 3DS2, a token vault with hosted card fields, wallets and local methods, real provider connectors with routing and failover, Radar-style rules, disputes, subscriptions with dunning and usage billing, connected accounts and payouts, a double-entry ledger with three-way reconciliation, test clocks, a dashboard with SSO and roles, dated API versions and four server SDKs. It stays small where the leaders are heavy: one native binary in an image under 100 MB, cold start under 150 ms, under 200 MB resident at 1,000 orders/s and p99 API latency under 50 ms, measured against Hyperswitch in a published benchmark and enforced as a CI gate. Its one promise never changes: every path charges exactly what is owed, once, proven by chaos and kill -9 tests on every new flow.

Benchmarks: Stripe is the primary reference for features; Adyen, Checkout.com, PayPal Braintree, Square, Hyperswitch, Spreedly, Primer and Xendit complete the comparison. Hyperswitch is the footprint and latency benchmark because it is the leading self-hosted orchestrator.

Structure: weeks 1 to 12 deliver the twelve monthly themes of the v1 draft, one per week; weeks 13 to 52 are forty new weeks closing the ranked gaps from the comparison. Each week's why names the matrix row it closes.

## 2026-11 · Refunds, webhooks, holds and split tender

Weeks 1 to 5 deliver v1's first five monthly themes in five weeks: release footing and API gaps, partial refunds, merchant webhooks, time limits on holds, and split tender with provider routing.

| Week | Dates | Deliverable | Why |
|---|---|---|---|
| 1 | 02 Nov to 08 Nov 2026 | Release footing and API gaps: Spring Boot 4, tagged releases, order listing, references, clean shutdown | v1 month 1 in one week. Spring Boot 3.5 pins Spring Framework 6.2, whose two advisories osv-scanner.toml has to ignore; merchants can only fetch an order by id; deploys wait 30 s for leases to expire. Matrix rows H5 and H6 (API surface) and H8 (deploys) depend on fixing these first. |
| 2 | 09 Nov to 15 Nov 2026 | Partial refunds: refund records, partial and line-item refunds that return stock, chaos coverage | v1 month 2 in one week. Refunds are all or nothing today, but most real refunds return one item or part of the amount (matrix row A5, which every column in the matrix supports). |
| 3 | 16 Nov to 22 Nov 2026 | Merchant webhooks: endpoints, signed delivery with retries, delivery log and replay, SSRF-safe sender | v1 month 3 in one week. Merchants learn about changes only by polling. Signed webhooks with retries and replay are table stakes in every column of matrix row E2 (Stripe, Adyen HMAC, Checkout.com, Braintree, Square, Hyperswitch). |
| 4 | 23 Nov to 29 Nov 2026 | Time limits on holds: durable timers, expiring reservations, unpaid-order cancel, delayed capture, authorization expiry | v1 month 4 in one week. Physical goods are charged on shipment and authorizations expire; reservations and unpaid orders need something to happen later even if every instance restarts (matrix rows A2 and A4). |
| 5 | 30 Nov to 06 Dec 2026 | Split tender and provider routing: payments table, multi-tender saga, refund allocation, second provider routing | v1 month 5 in one week. Gift card plus card needs a payment record per tender, and orchestration needs more than one provider (matrix rows D1 and D2, where Hyperswitch, Spreedly and Primer lead). |

## 2026-12 · Throughput, fairness, operations and privacy

Weeks 6 to 9 deliver v1's themes for fewer commits per order, fairness under load, operating it, and data lifecycle and privacy.

| Week | Dates | Deliverable | Why |
|---|---|---|---|
| 6 | 07 Dec to 13 Dec 2026 | Fewer commits per order: repeatable benchmark, merged transitions, batched inbox, partitioned outbox | v1 month 6 in one week. The benchmark saturates on WAL writes at about a dozen commits per order and varies run to run. Matrix row H9 (published performance) starts with a benchmark that can be trusted. |
| 7 | 14 Dec to 20 Dec 2026 | Fairness under load: merchant rate limits, fair saga share, provider bulkheads, provider 429 handling | v1 month 7 in one week. One merchant's flash sale can fill all 256 in-flight slots and one slow provider can hold every worker. Matrix rows H8 and H9: the SaaS leaders absorb this for their merchants, and a self-hosted platform must do it itself. |
| 8 | 21 Dec to 27 Dec 2026 | Operating it: trace context, operator API for stuck orders, dashboards and alerts, database outages mid-saga | v1 month 8 in one week, and the year's only observability week. Operators fix stuck orders with SQL today, and nothing shows a trace or raises an alert. Matrix rows H4 and H8 start here: operator actions become audited and outages become tested. |
| 9 | 28 Dec to 03 Jan 2027 | Data lifecycle and privacy: erasure, retention and archive, field encryption with rotation, API keys in the database | v1 month 9 in one week. Personal data must be erasable and not kept forever, sensitive fields need encryption with rotation, and keys must rotate without downtime (matrix rows H3 and H4 build on database-backed keys). |

## 2027-01 · Events, safe deploys and the benchmark against Hyperswitch

Weeks 10 to 13 finish v1 (events and reconciliation, safe deploys, the 0.x ecosystem) and publish the reproducible footprint and latency benchmark against Hyperswitch with a CI regression gate.

| Week | Dates | Deliverable | Why |
|---|---|---|---|
| 10 | 04 Jan to 10 Jan 2027 | Events and reconciliation: versioned event contract, replay, WAL relay, reconciliation against provider ledgers | v1 month 10 in one week. Events are internal JSON with no published contract, the polling relay costs an UPDATE per event, and nothing compares payflow's view with the provider's (matrix rows E3 and G4). |
| 11 | 11 Jan to 17 Jan 2027 | Safe deploys: Kubernetes manifests in CI, expand and contract migrations, mixed-version upgrade, fuzzing and threat model | v1 month 11 in one week, and the year's only deploy week. Every later enterprise feature (matrix row H8) assumes upgrades that never drop an order. |
| 12 | 18 Jan to 24 Jan 2027 | The 0.x ecosystem: Java client, documented test mode, Linux benchmark on real storage, compatibility draft | v1 month 12 in one week, recast as a 0.9 release because 1.0 now waits for the parity features below. Matrix rows H1 (test mode) and H5 (SDKs) start here. |
| 13 | 25 Jan to 31 Jan 2027 | Reproducible benchmark against Hyperswitch with a CI regression gate | Matrix row H9. Payment orchestrators publish no reproducible footprint numbers; payflow wins by measuring image size, cold start, resident memory, throughput and p99 against Hyperswitch, the leading self-hosted orchestrator, on the same host and the same simulated connectors, then gating every change on it. |

## 2027-02 · Native footprint, payment intents and SCA

A native image, a Stripe-style payment intent lifecycle, incremental, extended and multiple captures, and 3DS2 with exemptions and decision rules.

| Week | Dates | Deliverable | Why |
|---|---|---|---|
| 14 | 01 Feb to 07 Feb 2027 | Native image: small, fast-starting, low-memory payflow | Matrix row H9. A JVM image is several hundred megabytes and starts in seconds; Spring Boot AOT with GraalVM native image is the lever that lets a Java service compete on footprint with a Rust one. The GraalVM native-image toolchain is a build-time dependency only. |
| 15 | 08 Feb to 14 Feb 2027 | Payment intents: a standalone payment lifecycle beside orders | Matrix row A1. Stripe's PaymentIntent, Adyen's payment and Hyperswitch's payments API all expose a payment object a merchant drives directly; payflow only has orders. Intents become the core money object and orders orchestrate them with inventory and shipping. |
| 16 | 15 Feb to 21 Feb 2027 | Incremental, extended and multiple captures with partial authorization | Matrix rows A3 and A4. Stripe offers multicapture, incremental and extended authorization; Adyen and Checkout.com offer authorization adjustment and non-final captures. Hotels, marketplaces and split shipments need them. |
| 17 | 22 Feb to 28 Feb 2027 | 3DS2 and SCA: challenges, frictionless flow, exemptions and decision rules | Matrix row C1. Every column supports 3DS; Hyperswitch adds a 3DS decision manager. Without it payflow cannot take European cards. |

## 2027-03 · Vault, card data and payment methods

An encrypted token vault, network tokens with an account updater, hosted card fields, customers with stored-credential rules, and wallets.

| Week | Dates | Deliverable | Why |
|---|---|---|---|
| 18 | 01 Mar to 07 Mar 2027 | Token vault with envelope encryption and a key provider interface | Matrix row C2. Stripe, Spreedly, Hyperswitch and every PSP vault card data; payflow must never hold raw card numbers outside one audited component. |
| 19 | 08 Mar to 14 Mar 2027 | Network tokens and card account updater | Matrix row C3. Stripe, Adyen, Checkout.com, Braintree, Hyperswitch and Spreedly use network tokens and updaters to raise approval rates and survive card reissues. |
| 20 | 15 Mar to 21 Mar 2027 | Hosted card fields: a browser library that keeps card data off the merchant's page | Matrix row B4. Stripe Elements, Adyen Drop-in, Braintree Hosted Fields and Checkout.com Frames keep merchants in the lightest PCI scope. payflow needs its own, written in TypeScript with no runtime dependencies. |
| 21 | 22 Mar to 28 Mar 2027 | Customers, saved payment methods and stored-credential rules | Matrix rows C2 and F1. Stripe customers and SetupIntents, Braintree vault customers and Adyen tokenization let merchants charge saved methods with the right customer-initiated and merchant-initiated flags. |
| 22 | 29 Mar to 04 Apr 2027 | Wallets: Apple Pay and Google Pay token handling | Matrix row B2. Every column supports wallets; they are a large share of mobile checkout. |

## 2027-04 · Real connectors, local methods and smart routing

Local and asynchronous payment methods, real connectors for public provider test APIs, success-rate routing and cascading retries, plus the first quarterly benchmark re-measure.

| Week | Dates | Deliverable | Why |
|---|---|---|---|
| 23 | 05 Apr to 11 Apr 2027 | Local and asynchronous methods: bank transfers, QR, e-wallets and direct debit with mandates | Matrix row B3. Adyen, Stripe, Checkout.com, Hyperswitch and Xendit win in Southeast Asia and Europe through local methods that settle late and report through provider webhooks. |
| 24 | 12 Apr to 18 Apr 2027 | Real connectors for public provider test APIs | Matrix row D1. Hyperswitch, Spreedly and Primer connect to many providers; payflow only has simulators. Real connectors over raw HTTP (no vendor SDKs) for two public test APIs prove the SPI. |
| 25 | 19 Apr to 25 Apr 2027 | Success-rate routing with volume splits and cost rules | Matrix row D2. Hyperswitch, Spreedly Composer and Primer workflows route by rules, volume and success rate. |
| 26 | 26 Apr to 02 May 2027 | Cascading retries and failover on declines, with the quarterly re-measure | Matrix row D3 and H9. Hyperswitch, Spreedly and Primer retry soft declines on another provider. Failover must never risk a double charge, and the Q2 benchmark re-measure lands here. |

## 2027-05 · Risk, disputes, ledger and currencies

A rules engine with review queues, a pure-Java risk score, disputes with evidence, a double-entry ledger with balances, and multi-currency with FX quotes.

| Week | Dates | Deliverable | Why |
|---|---|---|---|
| 27 | 03 May to 09 May 2027 | Fraud rules engine with lists and review queues | Matrix row C4. Stripe Radar rules, Adyen risk rules and Checkout.com Fraud Detection let merchants block, review or allow by rule. |
| 28 | 10 May to 16 May 2027 | Pure-Java risk scoring in shadow and enforce modes | Matrix row C5. Stripe, Adyen, Checkout.com and Square score every payment with models; a small model trained on the merchant's own outcomes gives a score without an outside service or an ML runtime dependency. |
| 29 | 17 May to 23 May 2027 | Disputes and chargebacks with evidence, deadlines and pre-dispute alerts | Matrix row E1. Stripe, Adyen, Checkout.com, Braintree and Square expose dispute lifecycles with evidence submission; Hyperswitch only partly. Disputes move money weeks after a sale and must hold funds and meet deadlines. |
| 30 | 24 May to 30 May 2027 | Double-entry ledger with balances and balance transactions | Matrix row G3. Stripe exposes a balance and balance transactions for every movement, Adyen a balance platform; payflow has only per-order totals. A double-entry ledger, with an optional adapter to the sibling ledgerd service, is the base for fees, payouts, disputes and reconciliation. |
| 31 | 31 May to 06 Jun 2027 | Multi-currency: presentment and settlement currencies, FX quotes and fees | Matrix row G2. Stripe, Adyen, Checkout.com and Braintree charge in one currency and settle in another with fees on every movement; payflow handles one currency per order and no fees. |

## 2027-06 · Reconciliation, platforms and reporting

Three-way reconciliation against settlement files, connected accounts with payouts, marketplace fund flows, and a reporting API.

| Week | Dates | Deliverable | Why |
|---|---|---|---|
| 32 | 07 Jun to 13 Jun 2027 | Three-way reconciliation against settlement files and bank statements | Matrix row G4. Stripe and Adyen publish payout reconciliation reports and Hyperswitch sells two- and three-way reconciliation; week 10's job only compares with a simulator listing. |
| 33 | 14 Jun to 20 Jun 2027 | Connected accounts, onboarding states and payouts | Matrix row G1. Stripe Connect, Adyen for Platforms, Checkout.com Platforms and Xendit xenPlatform let a platform take payments for sellers and pay them out. payflow has a single merchant per key. |
| 34 | 21 Jun to 27 Jun 2027 | Marketplace fund flows: destination charges, transfers, application fees and negative balances | Matrix row G1. Platforms need Stripe-style destination charges, separate charges and transfers, and application fees, with clear rules for who pays refunds and disputes. |
| 35 | 28 Jun to 04 Jul 2027 | Reporting API with scheduled exports | Matrix row G5. Stripe, Adyen and Checkout.com offer report runs and scheduled exports finance teams rely on; payflow has none. |

## 2027-07 · Billing and test clocks

Subscriptions and invoices, proration and trials, dunning with usage billing, and test mode with test clocks, plus the second quarterly re-measure.

| Week | Dates | Deliverable | Why |
|---|---|---|---|
| 36 | 05 Jul to 11 Jul 2027 | Subscriptions and invoices on the durable scheduler | Matrix row F1. Stripe Billing, Braintree, Square and Xendit run subscriptions; Hyperswitch, Spreedly and Primer do not. Customers, saved methods, timers and the ledger are in place. |
| 37 | 12 Jul to 18 Jul 2027 | Subscription changes: proration, trials, pauses and cancellation | Matrix row F1. Plan changes mid-period with exact proration are where billing engines differ; Stripe sets the bar. |
| 38 | 19 Jul to 25 Jul 2027 | Dunning with smart retries, and metered usage billing | Matrix rows F2 and F3. Stripe Smart Retries, Adyen auto rescue and Hyperswitch revenue recovery recover failed renewals; Stripe also bills metered usage. |
| 39 | 26 Jul to 01 Aug 2027 | Test mode and test clocks, with the quarterly re-measure | Matrix rows H1 and H9. Stripe's test mode with test clocks lets merchants simulate months of billing in minutes; others offer only a sandbox. The Q3 benchmark re-measure also lands here. |

## 2027-08 · Dashboard, identity and compliance

Organizations with roles and SSO, a dashboard with a payments explorer and money actions behind approval, a tamper-evident audit log, and the PCI DSS control set.

| Week | Dates | Deliverable | Why |
|---|---|---|---|
| 40 | 02 Aug to 08 Aug 2027 | Organizations, roles and SSO for the platform's own users | Matrix row H3. Stripe, Adyen and Checkout.com sell team roles and SAML or OIDC SSO for their dashboards. Spring Security's OAuth2 client is BOM-managed; SAML uses spring-security-saml2-service-provider, the one heavy dependency, justified because enterprise buyers require SAML. |
| 41 | 09 Aug to 15 Aug 2027 | Dashboard: payments explorer, customers and events | Matrix row H2. Every competitor has a dashboard; payflow has none. Server-rendered templates with Thymeleaf (BOM-managed) and a little vanilla JS keep it light with no frontend build. |
| 42 | 16 Aug to 22 Aug 2027 | Dashboard money actions behind four-eyes approval, with finance views | Matrix row H2. Refunds, captures and dispute responses from a dashboard move money, so they need approval, and finance needs balances, payouts and breaks in one place. |
| 43 | 23 Aug to 29 Aug 2027 | Tamper-evident audit log with export and SIEM streaming | Matrix row H4. Stripe and Adyen keep account security and change histories; enterprise buyers also want proof that the log was not altered and a feed into their own SIEM. Week 8's operator_actions table covers only ops calls. |
| 44 | 30 Aug to 05 Sep 2027 | PCI DSS control set: mTLS, key ceremony, segmentation and an evidence pack | Matrix rows C2 and B4. With a vault, hosted fields and wallet decryption, payflow handles card data, so the controls a qualified assessor checks must exist and be testable: encrypted transport inside the cluster, managed keys, a segmented card data environment and evidence generated from tests. |

## 2027-09 · High availability and scale-out

Database failover and point-in-time restore drills, sharding by merchant, home regions with data residency, and online shard moves.

| Week | Dates | Deliverable | Why |
|---|---|---|---|
| 45 | 06 Sep to 12 Sep 2027 | High availability: database failover, backups and point-in-time restore drills | Matrix row H8. The SaaS leaders run across zones; a self-hosted product must survive a primary failover with no lost or doubled money, and restore from encrypted backups on a schedule that is proven, not assumed. |
| 46 | 13 Sep to 19 Sep 2027 | Sharding by merchant across Postgres clusters | Matrix row H8. One Postgres primary caps throughput at the WAL; Stripe-scale merchants need horizontal scale. Every payflow table already carries merchant_id, so sharding by merchant keeps each saga on one shard with no cross-shard transactions. |
| 47 | 20 Sep to 26 Sep 2027 | Home regions and data residency | Matrix row H8. Adyen and Stripe offer regional processing and data residency; enterprise and regulated merchants require that their data and card data stay in a region. |
| 48 | 27 Sep to 03 Oct 2027 | Online shard and region moves with fencing | Matrix row H8. Rebalancing shards or moving a merchant to a new region must not pause payments. Logical decoding from week 10 copies and catches up; a fenced cutover makes the switch. |

## 2027-10 · API versions, SDKs, checkout and 1.0

Dated API versions, server SDKs in four languages, hosted checkout and payment links, and payflow 1.0 with the final benchmark.

| Week | Dates | Deliverable | Why |
|---|---|---|---|
| 49 | 04 Oct to 10 Oct 2027 | Dated API versions with request-level pinning | Matrix row H6. Stripe pins every account and request to a dated API version and transforms responses for older versions; Square uses a dated version header. Before SDKs and 1.0, payflow needs the same so it can evolve without breaking integrations. |
| 50 | 11 Oct to 17 Oct 2027 | Server SDKs in TypeScript, Python and Go beside Java | Matrix row H5. Stripe, Adyen, Checkout.com, Braintree and Square ship SDKs in many languages. A generator from the versioned OpenAPI plus small hand-written cores keeps four SDKs consistent, each with no runtime dependencies beyond the language's standard library. |
| 51 | 18 Oct to 24 Oct 2027 | Hosted checkout and payment links | Matrix row B4. Stripe Checkout and Payment Links, Adyen Pay by Link, Checkout.com payment links and Xendit invoices let a merchant take payment with no frontend work; payflow only has embeddable fields. |
| 52 | 25 Oct to 31 Oct 2027 | payflow 1.0: compatibility promise, upgrade path and the final benchmark | Matrix rows H6 and H9. 1.0 turns the year into a stable contract: frozen API version, documented upgrade from 0.9, and the fourth quarterly benchmark against Hyperswitch, published with every target checked. |

## Next year

Gaps from the comparison still open after October 2027:

- In-person payments and terminals: Stripe Terminal, Adyen and Square lead; this needs hardware certification.
- Card issuing and treasury accounts: outside an acquiring and orchestration product for now.
- A tax calculation engine: week 51 adds signed merchant tax callbacks only.
- Real identity verification for connected accounts (matrix row G1): week 33 uses a stub verification interface.
- Direct card scheme membership for network tokens (matrix row C3): week 19 works through connectors and a simulator.
- Connector breadth (matrix row D1): Hyperswitch and Spreedly list 100 or more connectors against payflow's two real ones plus the kit.
- Native mobile SDKs for iOS and Android (matrix row B4).
- Network-scale machine learning for fraud (matrix row C5): payflow's model learns only from one deployment's own data.
