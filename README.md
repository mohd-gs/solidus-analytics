<div align="center">

# Solidus Analytics

### The Ultimate Economy Intelligence Dashboard for Minecraft

**See your server's economy like never before — in real time, from anywhere.**

[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.x-green?style=flat-square)](https://minecraft.net)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.2+-db4848?style=flat-square)](https://fabricmc.net)
[![License](https://img.shields.io/badge/License-Proprietary-blue?style=flat-square)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange?style=flat-square)](https://adoptium.net)

[Features](#-features) &bull; [Web Dashboard](#-web-dashboard) &bull; [Quick Start](#-quick-start) &bull; [Commands](#-commands) &bull; [Get Premium](#-get-premium)

</div>

---

## Stop Guessing. Start Knowing.

Your server's economy is alive — transactions flowing, prices shifting, wealth accumulating. But without the right tools, you're flying blind. Is inflation creeping up? Are players exploiting the system? Is wealth concentrated in the hands of a few?

**Solidus Analytics gives you X-ray vision into your economy.**

Built as a companion to [Solidus](https://github.com/mohammad-salah-qasiaa/solidus), it tracks every transaction, measures wealth inequality, detects inflation trends, catches fraudsters, and puts it all on a stunning web dashboard you can access from your phone — **no VPS required.**

---

## Features

### Free — What You Get Out of the Box

| Feature | What It Does |
|---------|-------------|
| **Live Metrics** | Real-time transaction volume, active players, and item rankings — updated every 30 seconds |
| **Wealth Distribution** | Gini coefficient, average/median balance, top-1% share — see who holds the money |
| **Inflation Monitoring** | Money-to-Goods ratio with clear thresholds (Healthy / Warning / Critical) and 24h/7d/30d rates |
| **Snapshot System** | Automatic economy snapshots every 30 minutes for trend analysis |
| **Historical Data** | Up to 90 days of daily metrics — watch your economy evolve over time |

### Premium — Unlock the Full Power

| Feature | What It Does |
|---------|-------------|
| **Economy Health Score** | A single 0–100 number (with A+ to F grade) that tells you exactly how healthy your economy is — weighted across 5 factors |
| **Fraud Detection** | Automatic detection of rapid wealth gain, high-frequency trading, and suspicious transaction patterns |
| **Discord Webhooks** | Real-time alerts pushed to your Discord — fraud warnings, inflation spikes, health drops, and daily summaries |
| **Weekly Reports** | Auto-generated Monday reports with actionable recommendations — "increase money sinks," "top 1% holds 42% of wealth" |
| **Web Dashboard** | Beautiful browser-based dashboard with interactive charts — accessible from anywhere, no VPS needed |

---

## Web Dashboard

The crown jewel of Solidus Analytics. A fully interactive, real-time economy dashboard you can open on your phone, tablet, or any browser — even while away from your server.

### Two Modes — Pick What Works for You

#### GitHub Pages Mode — Zero Cost, Zero Setup

The dashboard pushes encrypted data to a free GitHub repository. Your players' data is AES-256 encrypted — only you can decrypt it with your password. No VPS, no open ports, no extra costs.

**How it works:**
1. Create a free GitHub repo
2. Run one command: `/analytics dashboard github <token> <owner> <repo>`
3. Set your encryption password: `/analytics dashboard setup <password>`
4. Visit `https://yourname.github.io/your-repo` — done

Your server pushes data outbound via HTTPS (works on any hosting, even shared). The browser decrypts data locally using the Web Crypto API — your password never leaves your device.

#### Embedded Server Mode — For VPS Owners

If your server has an open port, the dashboard runs directly on your Minecraft server via an embedded NanoHTTPD web server. Real-time data, HTTP Basic Auth, zero external dependencies.

**How it works:**
1. Enable in `dashboard.properties`: `webserver.enabled=true`
2. Set a port: `webserver.port=9090`
3. Visit `http://your-server:9090` — done

### What You See

- **Economy Health Score** — Big, bold, color-coded score with grade and 5-factor breakdown
- **Live Metrics** — Daily volume, transaction count, active players, inflation rate — updating every minute
- **Transaction Volume Chart** — Bar chart showing daily volume trends
- **Inflation Trend Chart** — Line chart tracking inflation over time
- **Wealth Distribution** — Doughnut chart showing top 1% vs. 99%
- **Money:Goods Ratio** — Color-coded ratio bar (blue = deflation, green = healthy, yellow = moderate, red = critical)
- **Fraud Alerts** — Real-time list with severity badges (HIGH / MEDIUM / LOW)
- **Top Traded Items** — Most bought and most sold items at a glance

### Security You Can Trust

| Concern | How We Handle It |
|---------|-----------------|
| Data on public GitHub repos | **AES-256-GCM encryption** with PBKDF2 key derivation (100,000 iterations) — military-grade encryption, decrypted only in your browser |
| Password stored in config | **Never.** Only a salted SHA-256 hash is stored. The actual password lives in server memory only and is cleared on shutdown |
| Browser decryption | Uses the native **Web Crypto API** — your password never touches any server after you type it |
| GitHub token in config | Stored with **XOR obfuscation** — not plain text. Server file permissions provide the real protection |
| GitHub API conflicts | Automatic **GET-SHA-then-PUT** with exponential backoff retries on 409 Conflict — no data loss, no race conditions |

---

## Quick Start

### Installation

1. Install [Solidus Core](https://github.com/mohammad-salah-qasiaa/solidus) on your server
2. Drop `solidus-analytics.jar` into your `mods/` folder
3. Start the server — everything auto-configures

That's it. Analytics starts tracking immediately.

### Premium Activation

1. Get your server fingerprint: `/analytics fingerprint`
2. Send it to the license seller to receive your key
3. Place the key in `config/solidus-analytics/license.key`
4. Restart the server — premium features unlock automatically

### Dashboard Setup (Premium)

```
/analytics dashboard setup MySecretPassword     ← Set encryption password
/analytics dashboard github ghp_xxx MyUser my-repo  ← Connect to GitHub
/analytics dashboard unlock MySecretPassword    ← Unlock encryption (each startup)
```

Then visit your GitHub Pages URL. Enter the same password in the browser. You're in.

---

## Commands

### Core Commands (Free)

| Command | What It Shows |
|---------|--------------|
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

| Command | What It Shows |
|---------|--------------|
| `/analytics health` | Economy health score with factor breakdown |
| `/analytics fraud` | Recent fraud detection alerts |
| `/analytics fraud scan` | Force a fraud detection scan *(OP 3+)* |
| `/analytics report weekly` | Generate a weekly economy report |
| `/analytics license` | View license status *(OP 3+)* |
| `/analytics fingerprint` | Show server fingerprint for license purchase *(OP 3+)* |

### Dashboard Commands (Premium)

| Command | What It Does |
|---------|-------------|
| `/analytics dashboard` | Show dashboard status (encryption, GitHub, web server) |
| `/analytics dashboard setup <password>` | Set encryption password (first time only) |
| `/analytics dashboard unlock <password>` | Unlock encryption (each server startup) |
| `/analytics dashboard github <token> <owner> <repo>` | Configure GitHub Pages publishing |
| `/analytics dashboard publish` | Force publish data immediately |

---

## Economy Health Score

A single number that tells you everything. The health score is a weighted composite of 5 critical economy factors:

| Factor | Weight | What It Measures |
|--------|--------|-----------------|
| Gini Inequality | 25% | How evenly is wealth distributed? |
| Inflation Rate | 25% | Is money losing value too fast? |
| Money Growth | 20% | Is the money supply expanding at a healthy rate? |
| Activity Level | 15% | Are players actively trading? |
| Market Liquidity | 15% | Are there enough items listed on the market? |

**What the scores mean:**

| Score | Grade | Status |
|-------|-------|--------|
| 90–100 | A+ | Thriving economy — keep doing what you're doing |
| 70–89 | A / B+ | Healthy with minor concerns — small tweaks needed |
| 50–69 | C+ / B | Fair — some imbalances worth addressing |
| 30–49 | C / D | Poor — significant problems need attention |
| 0–29 | F | Critical — your economy needs immediate intervention |

---

## Weekly Reports

Every Monday, Solidus Analytics auto-generates a comprehensive economy report and saves it to `config/solidus-analytics/reports/`. If Discord webhooks are configured, the report is also sent to your channel.

Each report includes:
- Executive summary with health score and grade
- Key metrics: volume, transactions, active players (7-day breakdown)
- Inflation analysis with 24h/7d/30d rates
- Wealth distribution (Gini, top 1%, average/median balance)
- Top traded items
- Fraud alerts summary
- **Actionable recommendations** — specific steps to fix detected problems

Generate a report any time: `/analytics report weekly`

---

## Get Premium

Solidus Analytics is free to use with core analytics features. Premium features — health scoring, fraud detection, Discord alerts, weekly reports, and the web dashboard — require a license key.

**Contact:** mohdmxmxm@gmail.com or Discord: **mohd_gs**

---

## Technical Requirements

| Requirement | Version |
|-------------|---------|
| Minecraft | 26.1.x |
| Fabric Loader | 0.19.2+ |
| Java | 25 |
| Solidus Core | Any version (optional — mod works in standalone mode) |

**Server-side only.** No client mod needed. Works on any Fabric server.

---

## License

This software is proprietary and distributed under the Solidus Analytics End-User License Agreement (EULA). See the [LICENSE](LICENSE) file for details. Premium features require a valid license key.
