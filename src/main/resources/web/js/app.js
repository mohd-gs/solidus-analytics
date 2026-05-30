/* ═══════════════════════════════════════════════════════════
   Solidus Analytics Dashboard — JavaScript
   ═══════════════════════════════════════════════════════════ */

// ── State ────────────────────────────────────────────────────

let dashboardData = null;
let volumeChart = null;
let inflationChart = null;
let wealthChart = null;
let ratioChart = null;
let updateInterval = null;
let isEmbedded = false; // true if served from embedded web server
let dataPassword = null; // held in memory only

// ── Encryption Constants (must match Java DashboardEncryption) ──

const SALT_LENGTH = 16;
const IV_LENGTH = 12;
const PBKDF2_ITERATIONS = 100000;

// ── Initialization ───────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
    // Detect if we're on embedded server or GitHub Pages
    isEmbedded = window.location.port && window.location.port !== '';

    // Try to load data
    loadData();

    // Set up auto-refresh every 60 seconds
    updateInterval = setInterval(loadData, 60000);

    // Password input: Enter key support
    document.getElementById('password-input').addEventListener('keydown', (e) => {
        if (e.key === 'Enter') decryptWithPassword();
    });
});

// ── Data Loading ─────────────────────────────────────────────

async function loadData() {
    try {
        let response;
        if (isEmbedded) {
            // Embedded server: data is plain JSON, auth via HTTP Basic
            response = await fetch('/api/data', {
                headers: dataPassword ? {
                    'Authorization': 'Basic ' + btoa('admin:' + dataPassword)
                } : {}
            });
        } else {
            // GitHub Pages: data file may be encrypted
            response = await fetch('./data/analytics-data.json?t=' + Date.now());
        }

        if (!response.ok) throw new Error('HTTP ' + response.status);

        let rawText = await response.text();

        // Check if data is encrypted
        let jsonData;
        try {
            // Try parsing as plain JSON first
            jsonData = JSON.parse(rawText);
        } catch (e) {
            // Not valid JSON — might be encrypted
            jsonData = null;
            if (rawText.startsWith('"') || !rawText.startsWith('{')) {
                // Looks like encrypted data — show password modal
                showPasswordModal(rawText);
                return;
            }
        }

        if (jsonData) {
            dashboardData = jsonData;
            updateUI();
            updateConnectionStatus(true);
        }

    } catch (error) {
        console.error('Failed to load data:', error);
        updateConnectionStatus(false);
    }
}

// ── Decryption (Browser-side) ────────────────────────────────

let encryptedPayload = null;

function showPasswordModal(encrypted) {
    encryptedPayload = encrypted;
    document.getElementById('password-modal').style.display = 'flex';
    document.getElementById('dashboard').style.display = 'none';
    document.getElementById('password-input').focus();
}

async function decryptWithPassword() {
    const passwordInput = document.getElementById('password-input');
    const errorEl = document.getElementById('password-error');
    errorEl.style.display = 'none';

    const password = passwordInput.value;
    if (!password) return;

    try {
        let base64Data;
        // The encrypted data might be a JSON string containing the base64,
        // or it might be raw base64
        try {
            const parsed = JSON.parse(encryptedPayload);
            base64Data = parsed; // If it was a JSON string containing base64
        } catch {
            base64Data = encryptedPayload.replace(/^"|"$/g, ''); // Remove quotes
        }

        const rawData = Uint8Array.from(atob(base64Data), c => c.charCodeAt(0));

        // Extract salt, IV, and ciphertext
        const salt = rawData.slice(0, SALT_LENGTH);
        const iv = rawData.slice(SALT_LENGTH, SALT_LENGTH + IV_LENGTH);
        const ciphertext = rawData.slice(SALT_LENGTH + IV_LENGTH);

        // Derive key using PBKDF2 (Web Crypto API)
        const keyMaterial = await crypto.subtle.importKey(
            'raw',
            new TextEncoder().encode(password),
            'PBKDF2',
            false,
            ['deriveKey']
        );

        const key = await crypto.subtle.deriveKey(
            {
                name: 'PBKDF2',
                salt: salt,
                iterations: PBKDF2_ITERATIONS,
                hash: 'SHA-256'
            },
            keyMaterial,
            { name: 'AES-GCM', length: 256 },
            false,
            ['decrypt']
        );

        // Decrypt with AES-256-GCM
        const decrypted = await crypto.subtle.decrypt(
            { name: 'AES-GCM', iv: iv },
            key,
            ciphertext
        );

        const jsonStr = new TextDecoder().decode(decrypted);
        dashboardData = JSON.parse(jsonStr);

        // Success — hide modal, show dashboard
        dataPassword = password; // Store for future requests
        document.getElementById('password-modal').style.display = 'none';
        document.getElementById('dashboard').style.display = 'block';
        updateUI();
        updateConnectionStatus(true);

    } catch (error) {
        console.error('Decryption failed:', error);
        errorEl.style.display = 'block';
        passwordInput.value = '';
        passwordInput.focus();
    }
}

// ── UI Updates ───────────────────────────────────────────────

function updateUI() {
    if (!dashboardData) return;

    // Show dashboard
    document.getElementById('dashboard').style.display = 'block';
    document.getElementById('password-modal').style.display = 'none';

    // Server name
    if (dashboardData.server) {
        document.getElementById('server-name').textContent = dashboardData.server.name || '';
    }

    // Last update time
    if (dashboardData.timestamp) {
        const date = new Date(dashboardData.timestamp);
        document.getElementById('last-update').textContent =
            'Updated: ' + date.toLocaleTimeString();
    }

    updateLiveMetrics();
    updateHealthScore();
    updateFraudAlerts();
    updateTopItems();
    updateCharts();
}

function updateLiveMetrics() {
    if (!dashboardData.liveMetrics) return;
    const m = dashboardData.liveMetrics;

    document.getElementById('metric-volume').textContent =
        formatMoney(m.dailyVolume);
    document.getElementById('metric-transactions').textContent =
        m.dailyTransactionCount.toLocaleString();
    document.getElementById('metric-players').textContent =
        m.activePlayerCount.toLocaleString();

    // Inflation rate
    if (dashboardData.inflation && dashboardData.inflation.inflationRate24h != null) {
        const rate = dashboardData.inflation.inflationRate24h;
        const el = document.getElementById('metric-inflation');
        el.textContent = rate.toFixed(2) + '%';
        el.className = 'metric-value';
        if (rate > 10) el.style.color = 'var(--accent-red)';
        else if (rate > 5) el.style.color = 'var(--accent-yellow)';
        else el.style.color = 'var(--accent-green)';
    } else {
        document.getElementById('metric-inflation').textContent = 'N/A';
    }
}

function updateHealthScore() {
    if (!dashboardData.healthScore) {
        document.getElementById('health-section').style.display = 'none';
        return;
    }
    document.getElementById('health-section').style.display = 'block';

    const h = dashboardData.healthScore;
    const score = h.overallScore;
    const grade = h.grade;

    // Score value and color
    const scoreEl = document.getElementById('health-value');
    scoreEl.textContent = score.toFixed(1);
    scoreEl.className = 'score-value ' + getScoreClass(score);

    const gradeEl = document.getElementById('health-grade');
    gradeEl.textContent = grade;
    gradeEl.className = 'score-grade ' + getScoreClass(score);

    // Summary
    document.getElementById('health-summary-text').textContent = h.summary || '';

    // Factor bars
    updateFactorBar('gini', h.giniScore);
    updateFactorBar('inflation', h.inflationScore);
    updateFactorBar('growth', h.moneyGrowthScore);
    updateFactorBar('activity', h.activityScore);
    updateFactorBar('liquidity', h.liquidityScore);
}

function updateFactorBar(id, value) {
    const fill = document.getElementById('factor-' + id);
    const valEl = document.getElementById('factor-' + id + '-val');
    if (fill) fill.style.width = value + '%';
    if (valEl) valEl.textContent = Math.round(value);
}

function updateFraudAlerts() {
    const listEl = document.getElementById('fraud-list');

    if (!dashboardData.fraudAlerts || dashboardData.fraudAlerts.length === 0) {
        listEl.innerHTML = '<p class="no-data">No fraud alerts</p>';
        return;
    }

    let html = '';
    for (const alert of dashboardData.fraudAlerts) {
        const severityClass = alert.severity.toLowerCase();
        html += `
            <div class="alert-item">
                <div>
                    <span class="alert-player">${escapeHtml(alert.playerName)}</span>
                    <span class="alert-desc">${escapeHtml(alert.description)}</span>
                </div>
                <span class="alert-severity ${severityClass}">${alert.severity}</span>
            </div>
        `;
    }
    listEl.innerHTML = html;
}

function updateTopItems() {
    // Top Bought
    const boughtList = document.getElementById('top-bought');
    if (dashboardData.topItems && dashboardData.topItems.bought) {
        boughtList.innerHTML = dashboardData.topItems.bought.map(item =>
            `<li><span class="item-name">${escapeHtml(item.item)}</span>
             <span class="item-qty">${item.quantity.toLocaleString()}</span></li>`
        ).join('');
    }

    // Top Sold
    const soldList = document.getElementById('top-sold');
    if (dashboardData.topItems && dashboardData.topItems.sold) {
        soldList.innerHTML = dashboardData.topItems.sold.map(item =>
            `<li><span class="item-name">${escapeHtml(item.item)}</span>
             <span class="item-qty">${item.quantity.toLocaleString()}</span></li>`
        ).join('');
    }
}

// ── Charts ───────────────────────────────────────────────────

function updateCharts() {
    updateVolumeChart();
    updateInflationChart();
    updateWealthChart();
    updateRatioChart();
}

function updateVolumeChart() {
    const ctx = document.getElementById('volume-chart');
    if (!ctx || !dashboardData.dailyHistory) return;

    const history = dashboardData.dailyHistory.slice().reverse();
    const labels = history.map(d => d.date);
    const data = history.map(d => d.transactionVolume / 100);

    if (volumeChart) volumeChart.destroy();

    volumeChart = new Chart(ctx, {
        type: 'bar',
        data: {
            labels,
            datasets: [{
                label: 'Volume (S$)',
                data,
                backgroundColor: 'rgba(79, 140, 255, 0.5)',
                borderColor: 'rgba(79, 140, 255, 1)',
                borderWidth: 1
            }]
        },
        options: chartOptions('Volume (S$)')
    });
}

function updateInflationChart() {
    const ctx = document.getElementById('inflation-chart');
    if (!ctx || !dashboardData.dailyHistory) return;

    const history = dashboardData.dailyHistory.slice().reverse();
    const labels = history.map(d => d.date);
    const data = history.map(d => d.inflationRate);

    if (inflationChart) inflationChart.destroy();

    inflationChart = new Chart(ctx, {
        type: 'line',
        data: {
            labels,
            datasets: [{
                label: 'Inflation Rate (%)',
                data,
                borderColor: 'rgba(251, 191, 36, 1)',
                backgroundColor: 'rgba(251, 191, 36, 0.1)',
                fill: true,
                tension: 0.3,
                pointRadius: 3
            }]
        },
        options: chartOptions('Rate (%)')
    });
}

function updateWealthChart() {
    const ctx = document.getElementById('wealth-chart');
    if (!ctx || !dashboardData.latestSnapshot) return;

    const s = dashboardData.latestSnapshot;
    const top1 = s.top1PercentShare * 100;
    const rest = 100 - top1;

    if (wealthChart) wealthChart.destroy();

    wealthChart = new Chart(ctx, {
        type: 'doughnut',
        data: {
            labels: ['Top 1%', 'Other 99%'],
            datasets: [{
                data: [top1, rest],
                backgroundColor: ['rgba(248, 113, 113, 0.7)', 'rgba(79, 140, 255, 0.7)'],
                borderColor: ['rgba(248, 113, 113, 1)', 'rgba(79, 140, 255, 1)'],
                borderWidth: 1
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { labels: { color: '#e4e6ed' } }
            }
        }
    });
}

function updateRatioChart() {
    const ctx = document.getElementById('ratio-chart');
    if (!ctx || !dashboardData.inflation) return;

    const inf = dashboardData.inflation;
    if (inf.moneyToGoodsRatio < 0) {
        // No goods data
        ctx.parentElement.innerHTML = '<p class="no-data">No goods value data available</p>';
        return;
    }

    const ratio = inf.moneyToGoodsRatio;
    const maxRatio = Math.max(ratio * 1.5, 15); // Scale chart appropriately

    if (ratioChart) ratioChart.destroy();

    ratioChart = new Chart(ctx, {
        type: 'bar',
        data: {
            labels: ['Money:Goods Ratio'],
            datasets: [{
                label: 'Ratio',
                data: [ratio],
                backgroundColor: getRatioColor(ratio),
                borderColor: getRatioColor(ratio, 1),
                borderWidth: 1
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            indexAxis: 'y',
            scales: {
                x: {
                    max: maxRatio,
                    grid: { color: 'rgba(255,255,255,0.05)' },
                    ticks: { color: '#8b8fa3' }
                },
                y: {
                    grid: { display: false },
                    ticks: { color: '#e4e6ed' }
                }
            },
            plugins: {
                legend: { display: false },
                annotation: {}
            }
        }
    });
}

function chartOptions(yLabel) {
    return {
        responsive: true,
        maintainAspectRatio: false,
        scales: {
            x: {
                grid: { color: 'rgba(255,255,255,0.05)' },
                ticks: { color: '#8b8fa3', maxRotation: 45 }
            },
            y: {
                grid: { color: 'rgba(255,255,255,0.05)' },
                ticks: { color: '#8b8fa3' },
                title: { display: true, text: yLabel, color: '#8b8fa3' }
            }
        },
        plugins: {
            legend: { labels: { color: '#e4e6ed' } }
        }
    };
}

// ── Utilities ────────────────────────────────────────────────

function formatMoney(cents) {
    if (cents == null) return 'N/A';
    const dollars = cents / 100;
    if (dollars >= 1_000_000) return (dollars / 1_000_000).toFixed(1) + 'M S$';
    if (dollars >= 1_000) return (dollars / 1_000).toFixed(1) + 'K S$';
    return dollars.toFixed(2) + ' S$';
}

function getScoreClass(score) {
    if (score >= 80) return 'score-excellent';
    if (score >= 60) return 'score-good';
    if (score >= 40) return 'score-fair';
    if (score >= 20) return 'score-poor';
    return 'score-critical';
}

function getRatioColor(ratio, alpha) {
    const a = alpha || 0.5;
    if (ratio < 2) return `rgba(79, 140, 255, ${a})`;      // Deflation: blue
    if (ratio < 5) return `rgba(52, 211, 153, ${a})`;      // Healthy: green
    if (ratio < 10) return `rgba(251, 191, 36, ${a})`;     // Moderate: yellow
    return `rgba(248, 113, 113, ${a})`;                      // High: red
}

function updateConnectionStatus(connected) {
    const dot = document.getElementById('connection-status');
    if (dot) {
        dot.className = 'status-dot ' + (connected ? 'connected' : 'disconnected');
    }
}

function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
