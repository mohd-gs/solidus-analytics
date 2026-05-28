<div align="center">

# Solidus Analytics

### Advanced economy analytics and monitoring for Solidus Core

**Understand your server economy in real time — from anywhere.**

[![Fabric](https://img.shields.io/badge/Fabric-0.19.2+-db4848?style=flat-square)](https://fabricmc.net)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.x-green?style=flat-square)](https://minecraft.net)
[![Java](https://img.shields.io/badge/Java-25-orange?style=flat-square)](https://adoptium.net)
[![License](https://img.shields.io/badge/License-Proprietary-blue?style=flat-square)](LICENSE)

[Features](#-features) · [Web Dashboard](#-web-dashboard) · [Quick Start](#-quick-start) · [Commands](#-commands) · [Ecosystem](#-solidus-ecosystem)

</div>

---

Solidus Analytics is the intelligence layer for [Solidus Core](https://github.com/mohammad-salah-qasiaa/solidus-core) — tracking every transaction, measuring wealth inequality, detecting inflation trends, catching fraud, and putting it all on a live web dashboard you can access from your phone.

No VPS required. No open ports needed. Encrypted data pushed to GitHub Pages — decrypted only in your browser. Premium features available with a license key.

## Designed For

- Economy balancing
- Detecting inflation early
- Monitoring player wealth
- Server staff oversight
- Long-term economy analysis

---

## Solidus Ecosystem

| Project | Description |
|---------|-------------|
| [Solidus Core](https://github.com/mohammad-salah-qasiaa/solidus-core) | Economy engine, server shop, auction house |
| **Solidus Analytics** | Economy intelligence dashboard, inflation tracking, fraud detection |
| [Solidus Governance](https://github.com/mohammad-salah-qasiaa/Solidus-Governance) | Economy administration, taxation, audit, recovery |

---

## Features

### Core Analytics (Free)

| Feature | What It Does |
|---------|-------------|
| **Live Metrics** | Real-time transaction volume, active players, item rankings — updated every 30 seconds |
| **Wealth Distribution** | Gini coefficient, average/median balance, top-1% share |
| **Inflation Monitoring** | Money-to-Goods ratio with clear thresholds and 24h/7d/30d rates |
| **Snapshot System** | Periodic economy snapshots every 30 minutes for trend analysis |
| **Historical Data** | Up to 90 days of daily metrics |

### Premium Features (Licensed)

| Feature | What It Does |
|---------|-------------|
| **Economy Health Score** | Composite 0–100 score with A+ to F grade across 5 weighted factors |
| **Fraud Detection** | Automatic detection of rapid wealth gain, high-frequency trading, suspicious patterns |
| **Discord Webhooks** | Real-time alerts — fraud warnings, inflation spikes, health drops, daily summaries |
| **Weekly Reports** | Auto-generated Monday reports with actionable recommendations |
| **Web Dashboard** | Interactive browser dashboard with Chart.js visualizations — no VPS needed |

---

## Web Dashboard

A fully interactive economy dashboard accessible from any browser — your phone, tablet, or desktop — even while away from your server.

### Two Modes

#### GitHub Pages Mode — Zero Cost

Data is encrypted with AES-256-GCM and pushed to a free GitHub repository. Only you can decrypt it. No VPS, no open ports, no extra costs.

1. Create a free GitHub repo
2. `/analytics dashboard github <token> <owner> <repo>`
3. `/analytics dashboard setup <password>`
4. Visit `https://yourname.github.io/your-repo` — done

The server pushes data outbound via HTTPS (works on any hosting, even shared). Your browser decrypts data locally using the Web Crypto API. Your password never leaves your device.

#### Embedded Server Mode — For VPS Owners

If your server has an open port, the dashboard runs directly via an embedded NanoHTTPD web server. Real-time data, HTTP Basic Auth, zero external dependencies.

1. Enable in `dashboard.properties`: `webserver.enabled=true`
2. Set a port: `webserver.port=9090`
3. Visit `http://your-server:9090` — done

### Dashboard View

- **Economy Health Score** — Color-coded 0–100 score with grade and 5-factor breakdown
- **Live Metrics** — Daily volume, transaction count, active players, inflation rate
- **Transaction Volume Chart** — Bar chart with daily volume trends
- **Inflation Trend Chart** — Line chart tracking inflation over time
- **Wealth Distribution** — Doughnut chart showing top 1% vs. 99%
- **Money:Goods Ratio** — Color-coded bar (blue = deflation, green = healthy, yellow = moderate, red = critical)
- **Fraud Alerts** — Real-time list with severity badges
- **Top Traded Items** — Most bought and most sold at a glance

### Security

| Concern | How We Handle It |
|---------|-----------------|
| Data on public GitHub repos | **AES-256-GCM** encryption with PBKDF2 key derivation (100,000 iterations) — decrypted only in your browser |
| Password in config file | **Never.** Only a salted SHA-256 hash is stored. The actual password lives in server memory only and is zeroed on shutdown |
| Browser decryption | Native **Web Crypto API** — your password never touches any server after you type it |
| GitHub API concurrency | Automatic **GET-SHA-then-PUT** with exponential backoff on 409 Conflict — no data loss, no race conditions |

---

## Quick Start

### Installation

1. Install [Solidus Core](https://github.com/mohammad-salah-qasiaa/solidus-core) on your server
2. Drop `solidus-analytics.jar` into your `mods/` folder
3. Start the server — analytics database auto-creates at `config/solidus-analytics/analytics.db`

That's it. Tracking starts immediately.

### Premium Activation

1. Get your server fingerprint: `/analytics fingerprint`
2. Send it to the license seller to receive your key
3. Place the key in `config/solidus-analytics/license.key`
4. Restart — premium features unlock automatically

### Dashboard Setup (Premium)

```
/analytics dashboard setup MySecretPassword            ← Set encryption password (first time)
/analytics dashboard github ghp_xxx MyUser my-repo     ← Connect to GitHub
/analytics dashboard unlock MySecretPassword           ← Unlock encryption (each startup)
```

Visit your GitHub Pages URL, enter the same password, and you're in.

---

## Commands

### Core Commands (Free)

| Command | Description |
|---------|-------------|
| `/analytics` | Live economy dashboard in chat |
| `/analytics wealth` | Wealth distribution + Gini coefficient |
| `/analytics inflation` | Inflation rates and Money:Goods ratio |
| `/analytics top items` | Top traded items ranking |
| `/analytics history [days]` | Historical metrics (1–90 days) |
| `/analytics snapshot` | Force an economy snapshot *(OP 3+)* |
| `/inflation` | Quick inflation check |
| `/inflation day` | 24-hour inflation rate |
| `/inflation week` | 7-day inflation rate |
| `/inflation month` | 30-day inflation rate |

### Premium Commands

| Command | Description |
|---------|-------------|
| `/analytics health` | Economy health score with factor breakdown |
| `/analytics fraud` | Recent fraud detection alerts |
| `/analytics fraud scan` | Force a fraud detection scan *(OP 3+)* |
| `/analytics report weekly` | Generate a weekly economy report |
| `/analytics license` | View license status *(OP 3+)* |
| `/analytics fingerprint` | Show server fingerprint for license purchase *(OP 3+)* |

### Dashboard Commands (Premium)

| Command | Description |
|---------|-------------|
| `/analytics dashboard` | Show dashboard status |
| `/analytics dashboard setup <password>` | Set encryption password (first time) |
| `/analytics dashboard unlock <password>` | Unlock encryption (each startup) |
| `/analytics dashboard github <token> <owner> <repo>` | Configure GitHub Pages publishing |
| `/analytics dashboard publish` | Force publish data immediately |

---

## Economy Health Score

A single number that tells you how healthy your economy is — weighted across 5 critical factors:

| Factor | Weight | What It Measures |
|--------|--------|-----------------|
| Gini Inequality | 25% | How evenly is wealth distributed? |
| Inflation Rate | 25% | Is money losing value too fast? |
| Money Growth | 20% | Is the money supply expanding at a healthy rate? |
| Activity Level | 15% | Are players actively trading? |
| Market Liquidity | 15% | Are there enough items on the market? |

| Score | Grade | Status |
|-------|-------|--------|
| 90–100 | A+ | Thriving — keep doing what you're doing |
| 70–89 | A / B+ | Healthy with minor concerns |
| 50–69 | C+ / B | Fair — some imbalances worth addressing |
| 30–49 | C / D | Poor — significant problems need attention |
| 0–29 | F | Critical — economy needs immediate intervention |

---

## Weekly Reports

Auto-generated every Monday and saved to `config/solidus-analytics/reports/`. Also sent to Discord if webhooks are configured.

Each report includes:
- Executive summary with health score and grade
- Key metrics: volume, transactions, active players (7-day breakdown)
- Inflation analysis with 24h/7d/30d rates
- Wealth distribution (Gini, top 1%, average/median balance)
- Top traded items
- Fraud alerts summary
- **Actionable recommendations** based on detected patterns

Generate any time: `/analytics report weekly`

---

## Get Premium

Solidus Analytics includes a free core feature set. Advanced capabilities — health scoring, fraud detection, Discord alerts, weekly reports, and the live web dashboard — require a premium license.

**Contact:** mohdmxmxm@gmail.com · Discord: **mohd_gs**

---

## Compatibility

| Component | Requirement |
|-----------|-------------|
| Minecraft | 26.1.x |
| Fabric Loader | 0.19.2+ |
| Java | 25 |
| Solidus Core | Any version (optional — works in standalone mode) |

Server-side only. No client mod needed.

---

## License

This software is proprietary and distributed under the Solidus Analytics End-User License Agreement (EULA). See [LICENSE](LICENSE) for details. Premium features require a valid license key.

---

Built by [MOHD_Gs](https://github.com/mohammad-salah-qasiaa)
