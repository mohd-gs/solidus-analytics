# Solidus Analytics

**Premium Data Analytics Engine for Solidus Economy**

A companion Fabric mod providing comprehensive economic analytics for Minecraft servers running the Solidus economy system. Tracks wealth distribution, inflation, transaction patterns, and market health in real time.

---

## Features

### Core Analytics (Free)
- **Live Metrics Dashboard** — Real-time transaction volume, active players, and item rankings
- **Wealth Distribution** — Gini coefficient, average/median balance, top-1% share
- **Inflation Monitoring** — Money-to-Goods ratio, 24h/7d/30d inflation rates
- **Snapshot System** — Periodic economy snapshots every 30 minutes for trend analysis
- **Historical Data** — Daily metrics history with up to 90 days retention

### Premium Features (Licensed)
- **Economy Health Score** — Composite 0-100 score with letter grade (A+ to F) based on Gini, inflation, money growth, activity, and liquidity
- **Fraud Detection** — Automated detection of rapid wealth gain, high-frequency trading, and unusual transaction sizes
- **Discord Webhooks** — Real-time alerts for fraud, inflation warnings, and daily summaries sent to your Discord server
- **Configurable Settings** — Customizable snapshot intervals, retention periods, and notification thresholds

---

## Installation

1. Install [Solidus Core](https://github.com/mohammad-salah-qasiaa/solidus) mod on your server
2. Place `solidus-analytics.jar` in your server's `mods/` folder
3. Start the server — analytics database is auto-created at `config/solidus-analytics/analytics.db`
4. (Premium) Place your license key in `config/solidus-analytics/license.key`

## Configuration

A default config file is created at `config/solidus-analytics/analytics.properties`:

```properties
# Snapshot interval (minutes)
snapshot.interval.minutes=30

# Transaction polling interval (seconds)
polling.interval.seconds=30

# Data retention (days)
data.retention.days=90

# Discord webhook (premium)
discord.enabled=false
discord.webhook.url=
discord.notify.fraud=true
discord.notify.inflation=true
discord.notify.daily_summary=true
discord.notify.health_score=true
discord.health_score.threshold=50.0
discord.fraud.min_severity=HIGH

# Premium features
premium.enabled=false
```

---

## Commands

All commands require OP level 2+ unless noted.

| Command | Description |
|---------|-------------|
| `/analytics` | Live economy dashboard |
| `/analytics wealth` | Wealth distribution + Gini coefficient |
| `/analytics inflation` | Inflation rates and Money:Goods ratio |
| `/analytics top items` | Top traded items |
| `/analytics top buyers` | Top buyers ranking *(coming soon)* |
| `/analytics top sellers` | Top sellers ranking *(coming soon)* |
| `/analytics history [days]` | Daily metrics history (1-90 days) |
| `/analytics snapshot` | Force an economy snapshot *(OP level 3+)* |
| `/analytics export` | Export data as CSV *(OP level 3+, coming soon)* |
| `/inflation` | Quick inflation check |
| `/inflation day` | 24-hour inflation rate |
| `/inflation week` | 7-day inflation rate |
| `/inflation month` | 30-day inflation rate |

### Premium Commands

| Command | Description |
|---------|-------------|
| `/analytics health` | Economy health score with factor breakdown |
| `/analytics fraud [list]` | View recent fraud detection alerts |
| `/analytics fraud scan` | Force a fraud detection scan *(OP level 3+)* |
| `/analytics license` | View license status *(OP level 3+)* |

---

## Architecture

### 100% Server-Side
No client-side mod required. All analytics run on the server and are accessed via chat commands.

### Zero Compile Dependency on Solidus
Uses Java reflection to access SolidusAPI at runtime. If Solidus is not loaded, the mod operates in standalone mode, reading economy databases directly via JDBC.

### The Three Core Algorithms

1. **Live Metrics Tracker** — Polls the transaction log every 30 seconds and maintains in-memory counters for O(1) dashboard reads.
2. **Snapshot Scheduler** — Computes wealth distribution metrics (Gini, median, top-1%) every 30 minutes.
3. **Inflation Calculator** — Money-to-Goods ratio and inflation rate tracking over 24h, 7d, and 30d periods.

### Premium Algorithms

4. **Economy Health Score** — Weighted composite score from 5 factors: Gini (25%), inflation (25%), money growth (20%), activity (15%), liquidity (15%).
5. **Fraud Detector** — Pattern-based anomaly detection for rapid wealth gain, high-frequency trading, and unusual transaction sizes.
6. **Discord Notifier** — Rate-limited webhook integration for real-time economy alerts.

---

## Economy Health Score

The composite health score uses these factors:

| Factor | Weight | Optimal Range |
|--------|--------|---------------|
| Gini Coefficient | 25% | 0.2 - 0.3 |
| Inflation Rate | 25% | 2% - 5% |
| Money Growth | 20% | 5% - 15% weekly |
| Activity Level | 15% | 5-10 tx/player/day |
| Market Liquidity | 15% | 1-2 listings/player |

Score interpretation:
- **90-100 (A+)**: Excellent — thriving, well-balanced economy
- **70-89 (A/B+)**: Good — healthy with minor concerns
- **50-69 (C+/B)**: Fair — some imbalances to address
- **30-49 (C/D)**: Poor — significant problems
- **0-29 (F)**: Critical — economy in distress

---

## Technical Details

- **Minecraft**: 26.1.x (unobfuscated)
- **Fabric Loader**: 0.19.2+
- **Java**: 25
- **Database**: SQLite (WAL mode, independent from Solidus Core's DB)
- **Thread Model**: Single-threaded executor for DB operations, async for calculations

---

## License

This software is proprietary and distributed under the Solidus Analytics End-User License Agreement (EULA). See the [LICENSE](LICENSE) file for details.

Premium features require a valid license key. Contact licensing@solidusanalytics.com for inquiries.
