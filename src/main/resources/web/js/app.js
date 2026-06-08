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
let isRefreshing = false;
let lastRefreshTime = 0;
const REFRESH_COOLDOWN_MS = 3000; // 3s cooldown between manual refreshes
let retryCount = 0;
const MAX_RETRY_DELAY = 30000; // Max 30s retry delay
let currentFraudFilter = 'all';
let previousMetrics = {}; // For change detection

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

    // Keyboard navigation for tabs
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') closeMobileMenu();
    });
});

// ── Tab Switching ────────────────────────────────────────────────

function switchTab(tabName) {
    activeTab = tabName;

    // Update nav links
    document.querySelectorAll('.nav-link').forEach(link => {
        link.classList.remove('active');
        link.setAttribute('aria-selected', 'false');
        const linkTab = link.textContent.trim().toLowerCase();
        if (linkTab === tabName) {
            link.classList.add('active');
            link.setAttribute('aria-selected', 'true');
        }
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

    // Close mobile menu after tab switch
    closeMobileMenu();
}

// ── Mobile Menu ──────────────────────────────────────────────────

function toggleMobileMenu() {
    const navLinks = document.getElementById('nav-links');
    const menuBtn = document.getElementById('mobile-menu-btn');
    const overlay = document.getElementById('mobile-menu-overlay');
    const isOpen = navLinks.classList.contains('mobile-open');

    if (isOpen) {
        closeMobileMenu();
    } else {
        navLinks.classList.add('mobile-open');
        menuBtn.classList.add('open');
        menuBtn.setAttribute('aria-expanded', 'true');
        overlay.classList.add('active');
    }
}

function closeMobileMenu() {
    const navLinks = document.getElementById('nav-links');
    const menuBtn = document.getElementById('mobile-menu-btn');
    const overlay = document.getElementById('mobile-menu-overlay');

    if (navLinks) navLinks.classList.remove('mobile-open');
    if (menuBtn) {
        menuBtn.classList.remove('open');
        menuBtn.setAttribute('aria-expanded', 'false');
    }
    if (overlay) overlay.classList.remove('active');
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
            // Hide skeleton, show real data
            hideSkeleton();
            updateUI();
            updateConnectionStatus(true);
            // Reset retry counter on success
            retryCount = 0;
        }

    } catch (error) {
        console.error('Failed to load data:', error);
        updateConnectionStatus(false);
        scheduleRetry();
    }
}

function refreshData() {
    // Rate limiting: prevent spamming refresh
    const now = Date.now();
    if (now - lastRefreshTime < REFRESH_COOLDOWN_MS) {
        showToast('Please wait a moment before refreshing again.', 'info');
        return;
    }
    if (isRefreshing) return;

    lastRefreshTime = now;
    isRefreshing = true;

    // Show refresh indicator
    const indicator = document.getElementById('refresh-indicator');
    if (indicator) indicator.classList.remove('hidden');

    // Add spinning class to refresh button
    const refreshBtn = document.querySelector('.topbar-btn');
    if (refreshBtn) refreshBtn.classList.add('refreshing');

    loadData().finally(() => {
        isRefreshing = false;
        if (indicator) indicator.classList.add('hidden');
        if (refreshBtn) refreshBtn.classList.remove('refreshing');
    });
}

/**
 * Schedules an automatic retry with exponential backoff.
 * Delays: 5s, 10s, 20s, 30s (max), 30s, ...
 */
function scheduleRetry() {
    retryCount++;
    const delay = Math.min(5000 * Math.pow(2, retryCount - 1), MAX_RETRY_DELAY);

    // Update footer text
    const footerText = document.getElementById('footer-refresh-text');
    if (footerText) {
        footerText.textContent = 'Reconnecting in ' + Math.round(delay / 1000) + 's...';
    }

    setTimeout(() => {
        loadData();
    }, delay);
}

// ── Skeleton Loading ─────────────────────────────────────────────

function hideSkeleton() {
    const skeleton = document.getElementById('loading-skeleton');
    const summaryCards = document.getElementById('summary-cards');
    if (skeleton) skeleton.classList.add('hidden');
    if (summaryCards) summaryCards.classList.remove('hidden');
}

// ── Decryption (Browser-side) ────────────────────────────────────

let encryptedPayload = null;

function showPasswordModal(encrypted) {
    encryptedPayload = encrypted;
    document.getElementById('password-modal').classList.remove('hidden');
    document.getElementById('dashboard').classList.add('hidden');
    document.getElementById('password-input').focus();
}

async function decryptWithPassword() {
    const passwordInput = document.getElementById('password-input');
    const errorEl = document.getElementById('password-error');
    errorEl.classList.add('hidden');

    const password = passwordInput.value;
    if (!password) return;

    // ── Demo Mode: Enter "test" to preview the dashboard with dummy data ──
    if (password.toLowerCase() === 'test') {
        dashboardData = generateDemoData();
        dataPassword = 'test';
        document.getElementById('password-modal').classList.add('hidden');
        document.getElementById('dashboard').classList.remove('hidden');
        hideSkeleton();
        updateUI();
        updateConnectionStatus(true);
        showToast('Demo mode — showing sample data.', 'info');
        return;
    }

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
        document.getElementById('password-modal').classList.add('hidden');
        document.getElementById('dashboard').classList.remove('hidden');
        hideSkeleton();
        updateUI();
        updateConnectionStatus(true);
        showToast('Dashboard unlocked successfully.', 'success');

    } catch (error) {
        console.error('Decryption failed:', error);
        errorEl.classList.remove('hidden');
        passwordInput.value = '';
        passwordInput.focus();
    }
}

// ── Master UI Update ─────────────────────────────────────────────

function updateUI() {
    if (!dashboardData) return;

    document.getElementById('dashboard').classList.remove('hidden');
    document.getElementById('password-modal').classList.add('hidden');

    // Server name & timestamp
    if (dashboardData.server) {
        document.getElementById('server-name').textContent = dashboardData.server.name || 'Server';
    }

    if (dashboardData.timestamp) {
        const date = new Date(dashboardData.timestamp);
        document.getElementById('last-update').textContent =
            'Updated ' + date.toLocaleTimeString();
    }

    // Reset footer text
    const footerText = document.getElementById('footer-refresh-text');
    if (footerText) footerText.textContent = 'Auto-refresh every 60s';

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
    animateValue('metric-volume', formatMoney(m.dailyVolume));
    animateValue('metric-players', m.activePlayerCount.toLocaleString());

    // Money supply from snapshot or inflation data
    let moneySupply = null;
    if (dashboardData.inflation && dashboardData.inflation.moneySupplyCents) {
        moneySupply = dashboardData.inflation.moneySupplyCents;
    } else if (dashboardData.latestSnapshot && dashboardData.latestSnapshot.moneySupply) {
        moneySupply = dashboardData.latestSnapshot.moneySupply;
    }
    animateValue('metric-money-supply', moneySupply != null ? formatMoney(moneySupply) : 'N/A');

    // Inflation card
    if (dashboardData.inflation) {
        const inf = dashboardData.inflation;
        if (inf.inflationRate24h != null) {
            const rate = inf.inflationRate24h;
            const el = document.getElementById('metric-inflation');
            animateValue('metric-inflation', rate.toFixed(2) + '%');
            el.style.color = rate > 10 ? COLORS.red : rate > 5 ? COLORS.yellow : COLORS.accent;
        } else {
            animateValue('metric-inflation', 'N/A');
        }

        animateValue('metric-inflation-7d',
            inf.inflationRate7d != null ? inf.inflationRate7d.toFixed(2) + '%' : 'N/A');
        animateValue('metric-inflation-30d',
            inf.inflationRate30d != null ? inf.inflationRate30d.toFixed(2) + '%' : 'N/A');
        animateValue('metric-ratio',
            inf.moneyToGoodsRatio >= 0 ? inf.moneyToGoodsRatio.toFixed(1) : 'N/A');
    }

    // Health Score card
    if (dashboardData.healthScore) {
        const h = dashboardData.healthScore;
        const score = h.overallScore;

        const scoreEl = document.getElementById('health-value');
        animateValue('health-value', score.toFixed(0));
        scoreEl.className = 'big-number ' + getScoreClass(score);

        const badgeEl = document.getElementById('health-grade-badge');
        badgeEl.textContent = h.grade;
        badgeEl.className = 'card-badge ' + getBadgeClass(score);

        // Mini factor scores in the summary card
        animateValue('factor-gini-val', Math.round(h.giniScore).toString());
        animateValue('factor-activity-val', Math.round(h.activityScore).toString());
        animateValue('factor-liquidity-val', Math.round(h.liquidityScore).toString());
    } else {
        animateValue('health-value', 'N/A');
        document.getElementById('health-grade-badge').textContent = '--';
    }
}

// ── Animated Value Transition ────────────────────────────────────

/**
 * Smoothly transitions text content with a brief highlight effect.
 * Tracks previous values to avoid re-animating unchanged data.
 */
function animateValue(elementId, newValue) {
    const el = document.getElementById(elementId);
    if (!el) return;

    const oldValue = previousMetrics[elementId];
    if (oldValue === newValue) return; // No change, skip animation

    previousMetrics[elementId] = newValue;

    // Apply a brief highlight if value changed
    if (oldValue !== undefined && oldValue !== newValue) {
        el.style.transition = 'color 0.15s ease';
        el.style.color = COLORS.accent;
        setTimeout(() => {
            el.style.color = '';
            el.style.transition = '';
        }, 400);
    }

    el.textContent = newValue;
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
    updateSecurityOverview();
}

function updateSecurityOverview() {
    if (!dashboardData) return;

    const alerts = dashboardData.fraudAlerts || [];
    const activeCount = alerts.length;

    // Active alerts count
    animateValue('security-active-count', activeCount.toString());
    const activeCountEl = document.getElementById('security-active-count');
    if (activeCountEl) {
        activeCountEl.className = 'big-number ' + (activeCount > 5 ? 'score-critical' : activeCount > 0 ? 'score-fair' : 'score-excellent');
    }

    // Security score (inverse of threat level)
    const securityScore = Math.max(0, 100 - (activeCount * 10));
    animateValue('security-score', securityScore.toString());
    const scoreEl = document.getElementById('security-score');
    if (scoreEl) {
        scoreEl.className = 'big-number ' + getScoreClass(securityScore);
    }

    // Transactions monitored
    const txCount = dashboardData.liveMetrics ? dashboardData.liveMetrics.dailyTransactionCount : 0;
    animateValue('security-tx-monitored', txCount.toLocaleString());
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

        // Update mini badge
        const miniBadge = document.getElementById('fraud-count-badge-mini');
        if (miniBadge) {
            miniBadge.textContent = '0';
            miniBadge.className = 'card-badge badge-green';
        }
        return;
    }

    // Filter alerts if on security tab
    let alerts = dashboardData.fraudAlerts;
    if (full && currentFraudFilter !== 'all') {
        alerts = alerts.filter(a => a.severity.toLowerCase() === currentFraudFilter);
    }

    let html = '';
    for (const alert of alerts) {
        const severityClass = alert.severity.toLowerCase();
        const timeStr = alert.timestamp ? formatAlertTime(alert.timestamp) : '';
        html += `
            <div class="alert-item" data-severity="${severityClass}">
                <div class="alert-info">
                    <span class="alert-player">${escapeHtml(alert.playerName)}</span>
                    <span class="alert-desc">${escapeHtml(alert.description)}</span>
                    ${timeStr ? `<span class="alert-time">${timeStr}</span>` : ''}
                </div>
                <span class="alert-severity ${severityClass}">${alert.severity}</span>
            </div>`;
    }
    listEl.innerHTML = html;

    // Update fraud count badges
    const count = dashboardData.fraudAlerts.length;
    const badge = document.getElementById('fraud-count-badge');
    if (badge) {
        badge.textContent = count + ' Active';
        badge.className = 'card-badge ' + (count > 5 ? 'badge-red' : count > 0 ? 'badge-yellow' : 'badge-green');
    }

    const miniBadge = document.getElementById('fraud-count-badge-mini');
    if (miniBadge) {
        miniBadge.textContent = count.toString();
        miniBadge.className = 'card-badge ' + (count > 5 ? 'badge-red' : count > 0 ? 'badge-yellow' : 'badge-green');
    }
}

/**
 * Filters fraud alerts by severity level.
 */
function filterFraudAlerts(severity) {
    currentFraudFilter = severity;

    // Update active filter button
    document.querySelectorAll('.filter-btn').forEach(btn => {
        btn.classList.remove('active');
        if (btn.dataset.severity === severity) btn.classList.add('active');
    });

    // Re-render the full alert list
    updateFraudAlerts('fraud-list-full', true);
}

/**
 * Formats a timestamp into a relative time string.
 */
function formatAlertTime(timestamp) {
    try {
        const now = Date.now();
        const diff = now - timestamp;
        const minutes = Math.floor(diff / 60000);
        const hours = Math.floor(diff / 3600000);

        if (minutes < 1) return 'Just now';
        if (minutes < 60) return minutes + 'm ago';
        if (hours < 24) return hours + 'h ago';
        return new Date(timestamp).toLocaleDateString();
    } catch {
        return '';
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

    try {
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
    } catch (error) {
        console.error('Chart render error for', canvasId, ':', error);
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

    // Enhanced: show quintile breakdown if gini is available
    const hasGini = s.giniCoefficient != null && s.giniCoefficient > 0;

    if (hasGini) {
        // Estimate quintile shares using the gini coefficient
        // Lorenz curve approximation: bottom 20% ~ (1-gini)*0.08, etc.
        const g = s.giniCoefficient;
        const quintiles = estimateQuintileShares(g);

        return new Chart(ctx, {
            type: 'doughnut',
            data: {
                labels: ['Top 20%', '4th Quintile', '3rd Quintile', '2nd Quintile', 'Bottom 20%'],
                datasets: [{
                    data: quintiles,
                    backgroundColor: [
                        'rgba(255, 82, 82, 0.4)',
                        'rgba(255, 110, 64, 0.4)',
                        'rgba(255, 215, 64, 0.4)',
                        'rgba(68, 138, 255, 0.4)',
                        'rgba(0, 216, 108, 0.4)'
                    ],
                    borderColor: [COLORS.red, COLORS.orange, COLORS.yellow, COLORS.blue, COLORS.accent],
                    borderWidth: 2,
                    hoverOffset: 8
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                cutout: '62%',
                plugins: {
                    legend: {
                        position: 'bottom',
                        labels: {
                            color: COLORS.textMuted,
                            padding: 14,
                            usePointStyle: true,
                            pointStyle: 'circle',
                            font: { size: 10 }
                        }
                    },
                    tooltip: {
                        ...tooltipConfig(),
                        callbacks: {
                            label: function(context) {
                                return context.label + ': ' + context.parsed.toFixed(1) + '%';
                            }
                        }
                    }
                }
            }
        });
    }

    // Fallback: simple Top 1% vs Other 99%
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

/**
 * Estimates wealth quintile shares based on the Gini coefficient.
 * Uses a simplified Lorenz curve model for visualization purposes.
 */
function estimateQuintileShares(gini) {
    // Approximate quintile shares using an exponential distribution model
    // Higher gini = more concentrated wealth at the top
    const concentration = gini * 2.5 + 0.5; // Maps gini 0-1 to 0.5-3.0
    const rawShares = [];
    for (let i = 0; i < 5; i++) {
        rawShares.push(Math.exp(concentration * (i - 2)));
    }
    const total = rawShares.reduce((a, b) => a + b, 0);
    // Reverse so top 20% is first
    const shares = rawShares.reverse().map(s => (s / total) * 100);
    return shares.map(s => parseFloat(s.toFixed(1)));
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
    if (dollars < 0) return '-' + formatMoney(-cents); // Handle negative
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

// ── Data Export ──────────────────────────────────────────────────

/**
 * Exports the current dashboard data as a JSON file download.
 */
function exportData() {
    if (!dashboardData) {
        showToast('No data available to export.', 'error');
        return;
    }

    try {
        const jsonStr = JSON.stringify(dashboardData, null, 2);
        const blob = new Blob([jsonStr], { type: 'application/json' });
        const url = URL.createObjectURL(blob);

        const link = document.createElement('a');
        link.href = url;
        link.download = 'solidus-analytics-' + new Date().toISOString().slice(0, 10) + '.json';
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);

        URL.revokeObjectURL(url);
        showToast('Data exported successfully.', 'success');
    } catch (error) {
        console.error('Export failed:', error);
        showToast('Failed to export data.', 'error');
    }
}

// ── Toast Notifications ─────────────────────────────────────────

/**
 * Shows a toast notification.
 * @param {string} message - The message to display
 * @param {'success'|'error'|'info'} type - Toast type
 * @param {number} duration - Duration in ms (default: 3000)
 */
function showToast(message, type, duration) {
    type = type || 'info';
    duration = duration || 3000;

    const container = document.getElementById('toast-container');
    if (!container) return;

    const toast = document.createElement('div');
    toast.className = 'toast ' + type;
    toast.textContent = message;
    container.appendChild(toast);

    // Auto-remove after duration
    setTimeout(() => {
        toast.classList.add('toast-exit');
        setTimeout(() => {
            if (toast.parentNode) toast.parentNode.removeChild(toast);
        }, 250);
    }, duration);
}

// ── Demo Data Generator ─────────────────────────────────────────

/**
 * Generates realistic demo data for dashboard preview.
 * Activated by entering "test" as the password.
 * All numbers are fictional — no real server data is used.
 */
function generateDemoData() {
    const now = Date.now();
    const DAY_MS = 86400000;

    // Generate 14 days of daily history
    const dailyHistory = [];
    for (let i = 13; i >= 0; i--) {
        const date = new Date(now - i * DAY_MS);
        const dateStr = date.getFullYear() + '-' +
            String(date.getMonth() + 1).padStart(2, '0') + '-' +
            String(date.getDate()).padStart(2, '0');
        dailyHistory.push({
            date: dateStr,
            transactionCount: 150 + Math.floor(Math.random() * 120),
            transactionVolume: (500000 + Math.floor(Math.random() * 400000)),
            activePlayers: 25 + Math.floor(Math.random() * 20),
            inflationRate: parseFloat((1.5 + Math.random() * 4).toFixed(2))
        });
    }

    return {
        timestamp: now,
        server: {
            name: "Demo Server",
            fingerprint: "demo-fingerprint-preview"
        },
        liveMetrics: {
            dailyVolume: 850000,
            dailyTransactionCount: 247,
            activePlayerCount: 42,
            transactionsByType: {
                "SHOP_BUY": 142,
                "SHOP_SELL": 68,
                "AUCTION_BID": 22,
                "PLAYER_TRADE": 15
            }
        },
        latestSnapshot: {
            timestamp: now - 1800000,
            snapshotType: "periodic",
            totalWealth: 125000000,
            playerCount: 156,
            giniCoefficient: 0.42,
            avgBalance: 801280,
            medianBalance: 350000,
            top1PercentShare: 0.18,
            moneySupply: 125000000,
            auctionActiveListings: 34,
            auctionTotalValue: 45000000
        },
        inflation: {
            moneySupplyCents: 125000000,
            goodsValueCents: 38000000,
            moneyToGoodsRatio: 3.3,
            status: "MODERATE",
            inflationRate24h: 2.8,
            inflationRate7d: 4.1,
            inflationRate30d: 6.3
        },
        healthScore: {
            overallScore: 72,
            grade: "B",
            summary: "Economy is stable with moderate inflation. Wealth distribution is slightly unequal — consider adjusting shop prices to improve liquidity.",
            giniScore: 65,
            inflationScore: 70,
            moneyGrowthScore: 78,
            activityScore: 85,
            liquidityScore: 62
        },
        fraudAlerts: [
            {
                timestamp: now - 1200000,
                type: "RAPID_TRANSACTIONS",
                playerName: "xNotch",
                severity: "HIGH",
                description: "47 transactions in 30 seconds — possible automated trading bot"
            },
            {
                timestamp: now - 3600000,
                type: "UNUSUAL_VOLUME",
                playerName: "DiamondKing",
                severity: "MEDIUM",
                description: "Sold 640 diamonds in 1 hour — 12x above daily average"
            },
            {
                timestamp: now - 7200000,
                type: "PRICE_MANIPULATION",
                playerName: "TradeMaster99",
                severity: "LOW",
                description: "Repeated buy/sell pattern on iron ingots detected"
            }
        ],
        dailyHistory: dailyHistory,
        topItems: {
            bought: [
                { item: "Diamond Pickaxe", quantity: 89 },
                { item: "Iron Ingot", quantity: 1240 },
                { item: "Oak Planks", quantity: 3200 },
                { item: "Cooked Steak", quantity: 560 },
                { item: "Torch", quantity: 1800 }
            ],
            sold: [
                { item: "Cobblestone", quantity: 8900 },
                { item: "Wheat", quantity: 2100 },
                { item: "Raw Iron", quantity: 780 },
                { item: "Coal", quantity: 1500 },
                { item: "String", quantity: 420 }
            ]
        }
    };
}
