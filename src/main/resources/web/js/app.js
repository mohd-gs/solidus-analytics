/* ═══════════════════════════════════════════════════════════════════
   Solidus Analytics Dashboard — Modern Cyberpunk / Dark Tech
   Color Palette: #00D86C / #0A0A0A / #141414 / #222222 / #FFFFFF / #1E3D2F
   ═══════════════════════════════════════════════════════════════════ */

// ── State ────────────────────────────────────────────────────────

let dashboardData = null;
let chartInstances = {};  // Chart instance registry by canvas ID
let updateInterval = null;
let isEmbedded = false;
let dataPassword = null;
let activeTab = 'dashboard';

// ── Color Palette (matches CSS variables exactly) ────────────────

const COLORS = {
    accent:        '#00D86C',
    accentDim:     'rgba(0, 216, 108, 0.12)',
    accentFaded:   'rgba(0, 216, 108, 0.35)',
    accentMid:     'rgba(0, 216, 108, 0.55)',
    blue:          '#448AFF',
    blueFaded:     'rgba(68, 138, 255, 0.5)',
    yellow:        '#FFD740',
    yellowFaded:   'rgba(255, 215, 64, 0.15)',
    red:           '#FF5252',
    redFaded:      'rgba(255, 82, 82, 0.5)',
    orange:        '#FF6E40',
    purple:        '#B388FF',
    textPrimary:   '#FFFFFF',
    textSec:       '#E0E0E0',
    textMuted:     '#888888',
    grid:          'rgba(255, 255, 255, 0.04)',
    cardBg:        '#141414',
    mutedGreen:    '#1E3D2F',
    border:        '#222222'
};

// ── Encryption Constants (must match Java DashboardEncryption) ────

const SALT_LENGTH = 16;
const IV_LENGTH = 12;
const PBKDF2_ITERATIONS = 100000;

// ── Chart.js Global Defaults ─────────────────────────────────────

Chart.defaults.font.family = "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif";
Chart.defaults.font.size = 11;
Chart.defaults.color = COLORS.textMuted;

// ── Initialization ───────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
    isEmbedded = window.location.port && window.location.port !== '';
    loadData();
    updateInterval = setInterval(loadData, 60000);

    document.getElementById('password-input').addEventListener('keydown', (e) => {
        if (e.key === 'Enter') decryptWithPassword();
    });
});

// ── Tab Switching ────────────────────────────────────────────────

function switchTab(tabName) {
    activeTab = tabName;

    // Update nav links
    document.querySelectorAll('.nav-link').forEach(link => {
        link.classList.remove('active');
        const linkTab = link.textContent.trim().toLowerCase();
        if (linkTab === tabName) link.classList.add('active');
    });

    // Update tab content visibility
    document.querySelectorAll('.tab-content').forEach(tab => {
        tab.classList.remove('active');
    });
    const target = document.getElementById('tab-' + tabName);
    if (target) target.classList.add('active');

    // Refresh data rendering for the active tab
    if (dashboardData) {
        if (tabName === 'economy') renderEconomyTab();
        if (tabName === 'security') renderSecurityTab();
    }
}

// ── Data Loading ─────────────────────────────────────────────────

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

function refreshData() {
    loadData();
}

// ── Decryption (Browser-side) ────────────────────────────────────

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
            JSON.parse(encryptedPayload);
            base64Data = encryptedPayload;
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

// ── Master UI Update ─────────────────────────────────────────────

function updateUI() {
    if (!dashboardData) return;

    document.getElementById('dashboard').style.display = 'block';
    document.getElementById('password-modal').style.display = 'none';

    // Server name & timestamp
    if (dashboardData.server) {
        document.getElementById('server-name').textContent = dashboardData.server.name || 'Server';
    }

    if (dashboardData.timestamp) {
        const date = new Date(dashboardData.timestamp);
        document.getElementById('last-update').textContent =
            'Updated ' + date.toLocaleTimeString();
    }

    updateSummaryCards();
    updateDashboardTab();
    updateEconomyTab();
    updateSecurityTab();
}

// ── Summary Cards (always visible) ───────────────────────────────

function updateSummaryCards() {
    if (!dashboardData.liveMetrics) return;
    const m = dashboardData.liveMetrics;

    // Money Supply card
    document.getElementById('metric-volume').textContent = formatMoney(m.dailyVolume);
    document.getElementById('metric-players').textContent = m.activePlayerCount.toLocaleString();

    // Money supply from snapshot or inflation data
    let moneySupply = null;
    if (dashboardData.inflation && dashboardData.inflation.moneySupplyCents) {
        moneySupply = dashboardData.inflation.moneySupplyCents;
    } else if (dashboardData.latestSnapshot && dashboardData.latestSnapshot.moneySupply) {
        moneySupply = dashboardData.latestSnapshot.moneySupply;
    }
    document.getElementById('metric-money-supply').textContent =
        moneySupply != null ? formatMoney(moneySupply) : 'N/A';

    // Inflation card
    if (dashboardData.inflation) {
        const inf = dashboardData.inflation;
        if (inf.inflationRate24h != null) {
            const rate = inf.inflationRate24h;
            const el = document.getElementById('metric-inflation');
            el.textContent = rate.toFixed(2) + '%';
            el.style.color = rate > 10 ? COLORS.red : rate > 5 ? COLORS.yellow : COLORS.accent;
        } else {
            document.getElementById('metric-inflation').textContent = 'N/A';
        }

        document.getElementById('metric-inflation-7d').textContent =
            inf.inflationRate7d != null ? inf.inflationRate7d.toFixed(2) + '%' : 'N/A';
        document.getElementById('metric-inflation-30d').textContent =
            inf.inflationRate30d != null ? inf.inflationRate30d.toFixed(2) + '%' : 'N/A';
        document.getElementById('metric-ratio').textContent =
            inf.moneyToGoodsRatio >= 0 ? inf.moneyToGoodsRatio.toFixed(1) : 'N/A';
    }

    // Health Score card
    if (dashboardData.healthScore) {
        const h = dashboardData.healthScore;
        const score = h.overallScore;

        const scoreEl = document.getElementById('health-value');
        scoreEl.textContent = score.toFixed(0);
        scoreEl.className = 'big-number ' + getScoreClass(score);

        const badgeEl = document.getElementById('health-grade-badge');
        badgeEl.textContent = h.grade;
        badgeEl.className = 'card-badge ' + getBadgeClass(score);

        // Mini factor scores in the summary card
        document.getElementById('factor-gini-val').textContent = Math.round(h.giniScore);
        document.getElementById('factor-activity-val').textContent = Math.round(h.activityScore);
        document.getElementById('factor-liquidity-val').textContent = Math.round(h.liquidityScore);
    } else {
        document.getElementById('health-value').textContent = 'N/A';
        document.getElementById('health-grade-badge').textContent = '--';
    }
}

// ── Dashboard Tab ────────────────────────────────────────────────

function updateDashboardTab() {
    updateFraudAlerts('fraud-list', false);
    updateTopItems('top-bought', 'top-sold');
    renderChart('volume-chart', 'volume', dashboardData);
    renderChart('inflation-chart', 'inflation', dashboardData);
    renderChart('wealth-chart', 'wealth', dashboardData);
    renderChart('ratio-chart', 'ratio', dashboardData);
}

// ── Economy Tab ──────────────────────────────────────────────────

function renderEconomyTab() {
    updateEconomyTab();
}

function updateEconomyTab() {
    // Health factors
    if (dashboardData.healthScore) {
        const h = dashboardData.healthScore;

        document.getElementById('health-summary-text').textContent = h.summary || 'No health assessment available.';

        updateFactorBar('gini', h.giniScore);
        updateFactorBar('inflation', h.inflationScore);
        updateFactorBar('growth', h.moneyGrowthScore);
        updateFactorBar('activity', h.activityScore);
        updateFactorBar('liquidity', h.liquidityScore);
    }

    // Snapshot details
    if (dashboardData.latestSnapshot) {
        const s = dashboardData.latestSnapshot;
        document.getElementById('snap-total-wealth').textContent = formatMoney(s.totalWealth);
        document.getElementById('snap-player-count').textContent = s.playerCount.toLocaleString();
        document.getElementById('snap-avg-balance').textContent = formatMoney(s.avgBalance);
        document.getElementById('snap-median-balance').textContent = formatMoney(s.medianBalance);
        document.getElementById('snap-top1').textContent = (s.top1PercentShare * 100).toFixed(1) + '%';
        document.getElementById('snap-gini').textContent = s.giniCoefficient.toFixed(3);
        document.getElementById('snap-auctions').textContent = s.auctionActiveListings.toLocaleString();
        document.getElementById('snap-auction-value').textContent = formatMoney(s.auctionTotalValue);
    }

    // Economy tab charts (separate instances)
    renderChart('volume-chart-2', 'volume', dashboardData);
    renderChart('wealth-chart-2', 'wealth', dashboardData);
    updateTopItems('top-bought-2', 'top-sold-2');
}

// ── Security Tab ─────────────────────────────────────────────────

function renderSecurityTab() {
    updateSecurityTab();
}

function updateSecurityTab() {
    updateFraudAlerts('fraud-list-full', true);
}

// ── Fraud Alerts ─────────────────────────────────────────────────

function updateFraudAlerts(listId, full) {
    const listEl = document.getElementById(listId);
    if (!listEl) return;

    if (!dashboardData.fraudAlerts || dashboardData.fraudAlerts.length === 0) {
        if (!full) {
            listEl.innerHTML = `
                <div class="empty-state">
                    <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#333" stroke-width="1.5"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
                    <span class="empty-title">No Alerts</span>
                    <span class="empty-desc">Fraud detection is monitoring transactions.</span>
                </div>`;
        } else {
            listEl.innerHTML = `
                <div class="empty-state">
                    <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#333" stroke-width="1.5"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
                    <span class="empty-title">All Clear</span>
                    <span class="empty-desc">No fraud alerts detected. The system is monitoring transactions continuously.</span>
                </div>`;
        }
        return;
    }

    let html = '';
    for (const alert of dashboardData.fraudAlerts) {
        const severityClass = alert.severity.toLowerCase();
        html += `
            <div class="alert-item">
                <div class="alert-info">
                    <span class="alert-player">${escapeHtml(alert.playerName)}</span>
                    <span class="alert-desc">${escapeHtml(alert.description)}</span>
                </div>
                <span class="alert-severity ${severityClass}">${alert.severity}</span>
            </div>`;
    }
    listEl.innerHTML = html;

    // Update fraud count badge in security tab
    const badge = document.getElementById('fraud-count-badge');
    if (badge && dashboardData.fraudAlerts) {
        const count = dashboardData.fraudAlerts.length;
        badge.textContent = count + ' Active';
        badge.className = 'card-badge ' + (count > 5 ? 'badge-red' : count > 0 ? 'badge-yellow' : 'badge-green');
    }
}

// ── Top Items ────────────────────────────────────────────────────

function updateTopItems(boughtId, soldId) {
    const boughtList = document.getElementById(boughtId);
    const soldList = document.getElementById(soldId);

    if (dashboardData.topItems) {
        if (boughtList && dashboardData.topItems.bought && dashboardData.topItems.bought.length > 0) {
            boughtList.innerHTML = dashboardData.topItems.bought.map(item =>
                `<li><span class="item-name">${escapeHtml(item.item)}</span>
                 <span class="item-qty">${item.quantity.toLocaleString()}</span></li>`
            ).join('');
        }

        if (soldList && dashboardData.topItems.sold && dashboardData.topItems.sold.length > 0) {
            soldList.innerHTML = dashboardData.topItems.sold.map(item =>
                `<li><span class="item-name">${escapeHtml(item.item)}</span>
                 <span class="item-qty">${item.quantity.toLocaleString()}</span></li>`
            ).join('');
        }
    }
}

// ── Health Factor Bars ───────────────────────────────────────────

function updateFactorBar(id, value) {
    const fill = document.getElementById('factor-' + id);
    const valEl = document.getElementById('factor-' + id + '-val2');
    if (fill) fill.style.width = value + '%';
    if (valEl) valEl.textContent = Math.round(value) + '/100';
}

// ── Chart Rendering Engine ───────────────────────────────────────

function renderChart(canvasId, type, data) {
    const ctx = document.getElementById(canvasId);
    if (!ctx || !data) return;

    // Destroy existing chart for this canvas if it exists
    if (chartInstances[canvasId]) {
        chartInstances[canvasId].destroy();
        delete chartInstances[canvasId];
    }

    let chart = null;

    switch (type) {
        case 'volume':
            chart = buildVolumeChart(ctx, data);
            break;
        case 'inflation':
            chart = buildInflationChart(ctx, data);
            break;
        case 'wealth':
            chart = buildWealthChart(ctx, data);
            break;
        case 'ratio':
            chart = buildRatioChart(ctx, data);
            break;
    }

    if (chart) {
        chartInstances[canvasId] = chart;
    }
}

function buildVolumeChart(ctx, data) {
    if (!data.dailyHistory || data.dailyHistory.length === 0) return null;

    const history = data.dailyHistory.slice().reverse();
    const labels = history.map(d => formatDateLabel(d.date));
    const values = history.map(d => d.transactionVolume / 100);

    return new Chart(ctx, {
        type: 'bar',
        data: {
            labels,
            datasets: [{
                label: 'Volume (S$)',
                data: values,
                backgroundColor: COLORS.accentFaded,
                borderColor: COLORS.accent,
                borderWidth: 1,
                borderRadius: 4,
                hoverBackgroundColor: COLORS.accentMid
            }]
        },
        options: baseChartOptions('Volume (S$)')
    });
}

function buildInflationChart(ctx, data) {
    if (!data.dailyHistory || data.dailyHistory.length === 0) return null;

    const history = data.dailyHistory.slice().reverse();
    const labels = history.map(d => formatDateLabel(d.date));
    const values = history.map(d => d.inflationRate);

    return new Chart(ctx, {
        type: 'line',
        data: {
            labels,
            datasets: [{
                label: 'Inflation Rate (%)',
                data: values,
                borderColor: COLORS.yellow,
                backgroundColor: COLORS.yellowFaded,
                fill: true,
                tension: 0.4,
                pointRadius: 3,
                pointBackgroundColor: COLORS.yellow,
                pointBorderColor: 'transparent',
                pointHoverRadius: 6,
                pointHoverBackgroundColor: COLORS.yellow,
                borderWidth: 2
            }]
        },
        options: baseChartOptions('Rate (%)')
    });
}

function buildWealthChart(ctx, data) {
    if (!data.latestSnapshot) return null;

    const s = data.latestSnapshot;
    const top1 = s.top1PercentShare * 100;
    const rest = 100 - top1;

    return new Chart(ctx, {
        type: 'doughnut',
        data: {
            labels: ['Top 1%', 'Other 99%'],
            datasets: [{
                data: [top1, rest],
                backgroundColor: [
                    'rgba(255, 82, 82, 0.35)',
                    'rgba(0, 216, 108, 0.35)'
                ],
                borderColor: [COLORS.red, COLORS.accent],
                borderWidth: 2,
                hoverOffset: 8
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            cutout: '68%',
            plugins: {
                legend: {
                    position: 'bottom',
                    labels: {
                        color: COLORS.textMuted,
                        padding: 16,
                        usePointStyle: true,
                        pointStyle: 'circle',
                        font: { size: 11 }
                    }
                },
                tooltip: tooltipConfig()
            }
        }
    });
}

function buildRatioChart(ctx, data) {
    if (!data.inflation || data.inflation.moneyToGoodsRatio < 0) return null;

    const ratio = data.inflation.moneyToGoodsRatio;
    const maxRatio = Math.max(ratio * 1.5, 15);

    return new Chart(ctx, {
        type: 'bar',
        data: {
            labels: ['Money:Goods'],
            datasets: [{
                label: 'Ratio',
                data: [ratio],
                backgroundColor: getRatioColor(ratio, 0.4),
                borderColor: getRatioColor(ratio, 1),
                borderWidth: 1,
                borderRadius: 6,
                barThickness: 28
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
                    ticks: { color: COLORS.textMuted, font: { size: 10 } }
                },
                y: {
                    grid: { display: false },
                    ticks: { color: COLORS.textSec, font: { size: 11, weight: '600' } }
                }
            },
            plugins: {
                legend: { display: false },
                tooltip: tooltipConfig()
            }
        }
    });
}

// ── Chart Configuration Helpers ──────────────────────────────────

function baseChartOptions(yLabel) {
    return {
        responsive: true,
        maintainAspectRatio: false,
        interaction: {
            mode: 'index',
            intersect: false
        },
        scales: {
            x: {
                grid: { color: COLORS.grid, drawBorder: false },
                ticks: {
                    color: COLORS.textMuted,
                    maxRotation: 45,
                    font: { size: 10 }
                }
            },
            y: {
                grid: { color: COLORS.grid, drawBorder: false },
                ticks: { color: COLORS.textMuted, font: { size: 10 } },
                title: {
                    display: true,
                    text: yLabel,
                    color: COLORS.textMuted,
                    font: { size: 10, weight: '600' }
                }
            }
        },
        plugins: {
            legend: {
                labels: {
                    color: COLORS.textMuted,
                    usePointStyle: true,
                    pointStyle: 'circle',
                    padding: 16,
                    font: { size: 11 }
                }
            },
            tooltip: tooltipConfig()
        }
    };
}

function tooltipConfig() {
    return {
        backgroundColor: '#1A1A1A',
        titleColor: COLORS.textPrimary,
        bodyColor: COLORS.textSec,
        borderColor: COLORS.border,
        borderWidth: 1,
        cornerRadius: 8,
        padding: 12,
        titleFont: { weight: '700', size: 12 },
        bodyFont: { size: 11 },
        displayColors: true,
        boxPadding: 4
    };
}

// ── Utility Functions ────────────────────────────────────────────

function formatMoney(cents) {
    if (cents == null) return 'N/A';
    const dollars = cents / 100;
    if (dollars >= 1_000_000) return (dollars / 1_000_000).toFixed(1) + 'M S$';
    if (dollars >= 1_000) return (dollars / 1_000).toFixed(1) + 'K S$';
    return dollars.toFixed(2) + ' S$';
}

function formatDateLabel(dateStr) {
    try {
        const parts = dateStr.split('-');
        const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
        return months[parseInt(parts[1]) - 1] + ' ' + parseInt(parts[2]);
    } catch {
        return dateStr;
    }
}

function getScoreClass(score) {
    if (score >= 80) return 'score-excellent';
    if (score >= 60) return 'score-good';
    if (score >= 40) return 'score-fair';
    if (score >= 20) return 'score-poor';
    return 'score-critical';
}

function getBadgeClass(score) {
    if (score >= 80) return 'badge-green';
    if (score >= 60) return 'badge-green';
    if (score >= 40) return 'badge-yellow';
    if (score >= 20) return 'badge-yellow';
    return 'badge-red';
}

function getRatioColor(ratio, alpha) {
    const a = alpha || 0.4;
    if (ratio < 2)  return `rgba(68, 138, 255, ${a})`;
    if (ratio < 5)  return `rgba(0, 216, 108, ${a})`;
    if (ratio < 10) return `rgba(255, 215, 64, ${a})`;
    return `rgba(255, 82, 82, ${a})`;
}

function updateConnectionStatus(connected) {
    const dot = document.querySelector('#connection-status .status-dot');
    const text = document.querySelector('#connection-status .status-text');
    if (dot) {
        dot.className = 'status-dot ' + (connected ? 'connected' : 'disconnected');
    }
    if (text) {
        text.textContent = connected ? 'Online' : 'Offline';
    }
}

function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
