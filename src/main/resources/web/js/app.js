/* ═══════════════════════════════════════════════════════════
   Solidus Analytics Dashboard — JavaScript (Redesigned)
   Color scheme: Dark theme with green accent (#00E676)
   ═══════════════════════════════════════════════════════════ */

// ── State ────────────────────────────────────────────────────

let dashboardData = null;
let volumeChart = null;
let inflationChart = null;
let wealthChart = null;
let ratioChart = null;
let updateInterval = null;
let isEmbedded = false;
let dataPassword = null;

// ── Color Palette (matches CSS --accent variables) ───────────

const COLORS = {
    accent:     '#00E676',
    accentDim:  'rgba(0, 230, 118, 0.12)',
    accentFaded:'rgba(0, 230, 118, 0.5)',
    blue:       '#448AFF',
    blueFaded:  'rgba(68, 138, 255, 0.5)',
    yellow:     '#FFD740',
    yellowFaded:'rgba(255, 215, 64, 0.15)',
    red:        '#FF5252',
    redFaded:   'rgba(255, 82, 82, 0.5)',
    orange:     '#FF6E40',
    purple:     '#B388FF',
    textPrimary:'#FFFFFF',
    textSec:    '#A0A0A0',
    textMuted:  '#6B6B6B',
    grid:       'rgba(255, 255, 255, 0.05)',
    cardBg:     '#1E1E1E'
};

// ── Encryption Constants (must match Java DashboardEncryption) ──

const SALT_LENGTH = 16;
const IV_LENGTH = 12;
const PBKDF2_ITERATIONS = 100000;

// ── Chart.js Global Defaults ─────────────────────────────────

Chart.defaults.font.family = "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif";
Chart.defaults.color = COLORS.textSec;

// ── Initialization ───────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
    isEmbedded = window.location.port && window.location.port !== '';
    loadData();
    updateInterval = setInterval(loadData, 60000);

    document.getElementById('password-input').addEventListener('keydown', (e) => {
        if (e.key === 'Enter') decryptWithPassword();
    });
});

// ── Data Loading ─────────────────────────────────────────────

async function loadData() {
    try {
        let response;
        if (isEmbedded) {
            response = await fetch('/api/data', {
                headers: dataPassword ? {
                    'Authorization': 'Basic ' + btoa('admin:' + dataPassword)
                } : {}
            });
        } else {
            response = await fetch('./data/analytics-data.json?t=' + Date.now());
        }

        if (!response.ok) throw new Error('HTTP ' + response.status);

        let rawText = await response.text();
        let jsonData;
        try {
            jsonData = JSON.parse(rawText);
        } catch (e) {
            jsonData = null;
            if (rawText.startsWith('"') || !rawText.startsWith('{')) {
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
        try {
            const parsed = JSON.parse(encryptedPayload);
            base64Data = parsed;
        } catch {
            base64Data = encryptedPayload.replace(/^"|"$/g, '');
        }

        const rawData = Uint8Array.from(atob(base64Data), c => c.charCodeAt(0));
        const salt = rawData.slice(0, SALT_LENGTH);
        const iv = rawData.slice(SALT_LENGTH, SALT_LENGTH + IV_LENGTH);
        const ciphertext = rawData.slice(SALT_LENGTH + IV_LENGTH);

        const keyMaterial = await crypto.subtle.importKey(
            'raw', new TextEncoder().encode(password),
            'PBKDF2', false, ['deriveKey']
        );

        const key = await crypto.subtle.deriveKey(
            { name: 'PBKDF2', salt, iterations: PBKDF2_ITERATIONS, hash: 'SHA-256' },
            keyMaterial,
            { name: 'AES-GCM', length: 256 },
            false, ['decrypt']
        );

        const decrypted = await crypto.subtle.decrypt(
            { name: 'AES-GCM', iv }, key, ciphertext
        );

        const jsonStr = new TextDecoder().decode(decrypted);
        dashboardData = JSON.parse(jsonStr);

        dataPassword = password;
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

    document.getElementById('dashboard').style.display = 'block';
    document.getElementById('password-modal').style.display = 'none';

    if (dashboardData.server) {
        document.getElementById('server-name').textContent = dashboardData.server.name || '';
    }

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

    document.getElementById('metric-volume').textContent = formatMoney(m.dailyVolume);
    document.getElementById('metric-transactions').textContent = m.dailyTransactionCount.toLocaleString();
    document.getElementById('metric-players').textContent = m.activePlayerCount.toLocaleString();

    if (dashboardData.inflation && dashboardData.inflation.inflationRate24h != null) {
        const rate = dashboardData.inflation.inflationRate24h;
        const el = document.getElementById('metric-inflation');
        el.textContent = rate.toFixed(2) + '%';
        el.className = 'metric-value';
        if (rate > 10) el.style.color = COLORS.red;
        else if (rate > 5) el.style.color = COLORS.yellow;
        else el.style.color = COLORS.accent;
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

    const scoreEl = document.getElementById('health-value');
    scoreEl.textContent = score.toFixed(1);
    scoreEl.className = 'score-value ' + getScoreClass(score);

    const gradeEl = document.getElementById('health-grade');
    gradeEl.textContent = grade;
    gradeEl.className = 'score-grade ' + getScoreClass(score);

    document.getElementById('health-summary-text').textContent = h.summary || '';

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
    const boughtList = document.getElementById('top-bought');
    if (dashboardData.topItems && dashboardData.topItems.bought) {
        boughtList.innerHTML = dashboardData.topItems.bought.map(item =>
            `<li><span class="item-name">${escapeHtml(item.item)}</span>
             <span class="item-qty">${item.quantity.toLocaleString()}</span></li>`
        ).join('');
    }

    const soldList = document.getElementById('top-sold');
    if (dashboardData.topItems && dashboardData.topItems.sold) {
        soldList.innerHTML = dashboardData.topItems.sold.map(item =>
            `<li><span class="item-name">${escapeHtml(item.item)}</span>
             <span class="item-qty">${item.quantity.toLocaleString()}</span></li>`
        ).join('');
    }
}

// ── Charts (using chart.update() instead of destroy/recreate) ──

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

    if (volumeChart) {
        // Update existing chart — no destroy/recreate
        volumeChart.data.labels = labels;
        volumeChart.data.datasets[0].data = data;
        volumeChart.update('none'); // 'none' = no animation for faster updates
        return;
    }

    volumeChart = new Chart(ctx, {
        type: 'bar',
        data: {
            labels,
            datasets: [{
                label: 'Volume (S$)',
                data,
                backgroundColor: COLORS.accentFaded,
                borderColor: COLORS.accent,
                borderWidth: 1,
                borderRadius: 4,
                hoverBackgroundColor: 'rgba(0, 230, 118, 0.7)'
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

    if (inflationChart) {
        inflationChart.data.labels = labels;
        inflationChart.data.datasets[0].data = data;
        inflationChart.update('none');
        return;
    }

    inflationChart = new Chart(ctx, {
        type: 'line',
        data: {
            labels,
            datasets: [{
                label: 'Inflation Rate (%)',
                data,
                borderColor: COLORS.yellow,
                backgroundColor: COLORS.yellowFaded,
                fill: true,
                tension: 0.4,
                pointRadius: 3,
                pointBackgroundColor: COLORS.yellow,
                pointBorderColor: COLORS.yellow,
                pointHoverRadius: 6
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

    if (wealthChart) {
        wealthChart.data.datasets[0].data = [top1, rest];
        wealthChart.update('none');
        return;
    }

    wealthChart = new Chart(ctx, {
        type: 'doughnut',
        data: {
            labels: ['Top 1%', 'Other 99%'],
            datasets: [{
                data: [top1, rest],
                backgroundColor: [COLORS.redFaded, COLORS.accentFaded],
                borderColor: [COLORS.red, COLORS.accent],
                borderWidth: 2,
                hoverOffset: 8
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            cutout: '65%',
            plugins: {
                legend: {
                    position: 'bottom',
                    labels: {
                        color: COLORS.textSec,
                        padding: 16,
                        usePointStyle: true,
                        pointStyle: 'circle'
                    }
                }
            }
        }
    });
}

function updateRatioChart() {
    const ctx = document.getElementById('ratio-chart');
    if (!ctx || !dashboardData.inflation) return;

    const inf = dashboardData.inflation;
    if (inf.moneyToGoodsRatio < 0) {
        if (ratioChart) { ratioChart.destroy(); ratioChart = null; }
        ctx.parentElement.innerHTML = '<p class="no-data">No goods value data available</p>';
        return;
    }

    const ratio = inf.moneyToGoodsRatio;
    const maxRatio = Math.max(ratio * 1.5, 15);

    if (ratioChart) {
        ratioChart.data.datasets[0].data = [ratio];
        ratioChart.data.datasets[0].backgroundColor = getRatioColor(ratio);
        ratioChart.data.datasets[0].borderColor = getRatioColor(ratio, 1);
        ratioChart.options.scales.x.max = maxRatio;
        ratioChart.update('none');
        return;
    }

    ratioChart = new Chart(ctx, {
        type: 'bar',
        data: {
            labels: ['Money:Goods Ratio'],
            datasets: [{
                label: 'Ratio',
                data: [ratio],
                backgroundColor: getRatioColor(ratio),
                borderColor: getRatioColor(ratio, 1),
                borderWidth: 1,
                borderRadius: 4
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            indexAxis: 'y',
            scales: {
                x: {
                    max: maxRatio,
                    grid: { color: COLORS.grid },
                    ticks: { color: COLORS.textSec }
                },
                y: {
                    grid: { display: false },
                    ticks: { color: COLORS.textPrimary }
                }
            },
            plugins: { legend: { display: false } }
        }
    });
}

function chartOptions(yLabel) {
    return {
        responsive: true,
        maintainAspectRatio: false,
        interaction: {
            mode: 'index',
            intersect: false
        },
        scales: {
            x: {
                grid: { color: COLORS.grid },
                ticks: { color: COLORS.textSec, maxRotation: 45 }
            },
            y: {
                grid: { color: COLORS.grid },
                ticks: { color: COLORS.textSec },
                title: { display: true, text: yLabel, color: COLORS.textSec }
            }
        },
        plugins: {
            legend: {
                labels: {
                    color: COLORS.textSec,
                    usePointStyle: true,
                    pointStyle: 'circle',
                    padding: 16
                }
            },
            tooltip: {
                backgroundColor: '#2A2A2A',
                titleColor: COLORS.textPrimary,
                bodyColor: COLORS.textSec,
                borderColor: '#333',
                borderWidth: 1,
                cornerRadius: 8,
                padding: 12
            }
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
    if (ratio < 2)  return `rgba(68, 138, 255, ${a})`;
    if (ratio < 5)  return `rgba(0, 230, 118, ${a})`;
    if (ratio < 10) return `rgba(255, 215, 64, ${a})`;
    return `rgba(255, 82, 82, ${a})`;
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
