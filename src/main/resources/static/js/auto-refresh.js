/**
 * Auto-refresh untuk halaman admin.
 * Polling /admin/heartbeat tiap X detik. Refresh halaman kalau ada perubahan data.
 *
 * Cara pakai:
 *   <script src="/js/auto-refresh.js" data-watch="totalClicks,totalLogs"
 *           data-interval="5000"></script>
 *
 * data-watch: nama field dari heartbeat JSON yang dipantau (comma-separated)
 * data-interval: interval polling dalam ms (default 5000)
 */
(function() {
    'use strict';

    const script = document.currentScript;
    const watchFields = (script.dataset.watch || 'totalUrls,totalClicks,totalLogs').split(',');
    const interval = parseInt(script.dataset.interval || '5000', 10);

    let lastValues = null;
    let enabled = localStorage.getItem('admin-auto-refresh') !== 'false';
    let timer = null;
    let countdownTimer = null;
    let remainingSec = interval / 1000;

    // Bikin UI indikator
    function createIndicator() {
        const wrap = document.createElement('div');
        wrap.id = 'auto-refresh-indicator';
        wrap.style.cssText = `
            position: fixed; bottom: 20px; right: 20px; z-index: 9999;
            background: white; border: 1px solid #E5E7EB; border-radius: 24px;
            padding: 8px 16px; display: flex; align-items: center; gap: 10px;
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            font-size: 13px; box-shadow: 0 4px 12px rgba(0,0,0,0.08);
            cursor: pointer; user-select: none;
            transition: transform 0.2s, box-shadow 0.2s;
        `;

        wrap.innerHTML = `
            <span id="ar-dot" style="width: 8px; height: 8px; border-radius: 50%;
                background: #10B981; display: inline-block;
                animation: ar-pulse 2s infinite;"></span>
            <span id="ar-label" style="color: #374151;">Auto-refresh</span>
            <span id="ar-status" style="color: #6B7280; font-size: 12px;">ON</span>
            <span id="ar-countdown" style="color: #9CA3AF; font-size: 11px;
                font-family: 'SF Mono', Monaco, monospace; min-width: 20px;
                text-align: right;"></span>
        `;

        // Hover effect
        wrap.addEventListener('mouseenter', () => {
            wrap.style.transform = 'translateY(-2px)';
            wrap.style.boxShadow = '0 6px 16px rgba(0,0,0,0.12)';
        });
        wrap.addEventListener('mouseleave', () => {
            wrap.style.transform = '';
            wrap.style.boxShadow = '0 4px 12px rgba(0,0,0,0.08)';
        });

        // Click untuk toggle
        wrap.addEventListener('click', toggle);

        document.body.appendChild(wrap);

        // CSS pulse animation
        const style = document.createElement('style');
        style.textContent = `
            @keyframes ar-pulse {
                0%, 100% { opacity: 1; }
                50% { opacity: 0.4; }
            }
        `;
        document.head.appendChild(style);
    }

    function updateIndicator() {
        const dot = document.getElementById('ar-dot');
        const status = document.getElementById('ar-status');
        const countdown = document.getElementById('ar-countdown');
        if (!dot) return;

        if (enabled) {
            dot.style.background = '#10B981';
            dot.style.animation = 'ar-pulse 2s infinite';
            status.textContent = 'ON';
            status.style.color = '#10B981';
            countdown.textContent = remainingSec + 's';
        } else {
            dot.style.background = '#9CA3AF';
            dot.style.animation = 'none';
            status.textContent = 'OFF';
            status.style.color = '#9CA3AF';
            countdown.textContent = '';
        }
    }

    function toggle() {
        enabled = !enabled;
        localStorage.setItem('admin-auto-refresh', enabled);
        if (enabled) {
            start();
        } else {
            stop();
        }
        updateIndicator();
    }

    async function checkHeartbeat() {
        try {
            const res = await fetch('/admin/heartbeat', {
                headers: { 'Accept': 'application/json' },
                credentials: 'same-origin'
            });

            // Kalau session expired, kembali ke login
            if (res.status === 401 || res.status === 403 || res.redirected) {
                stop();
                return;
            }

            if (!res.ok) return;

            const data = await res.json();

            if (lastValues === null) {
                lastValues = data;
                return;
            }

            // Cek apakah ada field yang berubah
            const changed = watchFields.some(field => {
                field = field.trim();
                return lastValues[field] !== data[field];
            });

            if (changed) {
                // Cuma reload kalau user nggak lagi ngetik di form
                if (isUserInteracting()) {
                    // Simpan pesan, reload di cek berikutnya
                    showBadge(data);
                } else {
                    window.location.reload();
                }
            }
        } catch (e) {
            // Diam aja kalau network error (mungkin offline sementara)
            console.debug('Heartbeat failed:', e.message);
        }
    }

    function isUserInteracting() {
        const active = document.activeElement;
        if (!active) return false;
        const tag = active.tagName;
        // Kalau user lagi fokus di input/textarea, tunda refresh
        return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT';
    }

    function showBadge(data) {
        let badge = document.getElementById('ar-badge');
        if (badge) return; // sudah ada

        badge = document.createElement('div');
        badge.id = 'ar-badge';
        badge.style.cssText = `
            position: fixed; top: 70px; right: 20px; z-index: 9999;
            background: #4F46E5; color: white; padding: 12px 18px;
            border-radius: 8px; font-size: 14px;
            font-family: -apple-system, sans-serif;
            box-shadow: 0 10px 25px rgba(79, 70, 229, 0.3);
            cursor: pointer;
            animation: slideIn 0.3s ease-out;
        `;
        badge.innerHTML = '🔔 Ada data baru — klik untuk refresh';
        badge.onclick = () => window.location.reload();

        const style = document.createElement('style');
        style.textContent = `
            @keyframes slideIn {
                from { transform: translateX(100%); opacity: 0; }
                to { transform: translateX(0); opacity: 1; }
            }
        `;
        document.head.appendChild(style);

        document.body.appendChild(badge);
    }

    function start() {
        stop();
        remainingSec = interval / 1000;

        // Polling heartbeat
        timer = setInterval(() => {
            checkHeartbeat();
            remainingSec = interval / 1000;
        }, interval);

        // Countdown
        countdownTimer = setInterval(() => {
            if (remainingSec > 0) remainingSec--;
            updateIndicator();
        }, 1000);
    }

    function stop() {
        if (timer) { clearInterval(timer); timer = null; }
        if (countdownTimer) { clearInterval(countdownTimer); countdownTimer = null; }
    }

    // Pause polling kalau tab tidak aktif (hemat resource)
    document.addEventListener('visibilitychange', () => {
        if (document.hidden) {
            stop();
        } else if (enabled) {
            checkHeartbeat(); // langsung cek pas balik ke tab
            start();
        }
    });

    // Init
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    function init() {
        createIndicator();
        updateIndicator();
        if (enabled) {
            // First heartbeat tanpa compare (buat set baseline)
            checkHeartbeat().then(() => start());
        }
    }
})();
