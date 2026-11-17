/**
 * ScreenGuard Focus Sync - Popup Controller
 */

const API_BASE = "http://127.0.0.1:49221";

function formatTime(seconds) {
  const s = Math.max(0, Math.floor(seconds));
  const m = Math.floor(s / 60);
  const remSec = s % 60;
  return `${m.toString().padStart(2, "0")}:${remSec.toString().padStart(2, "0")}`;
}

async function updateUI() {
  const connPill = document.getElementById("connStatus");
  const tabDomainEl = document.getElementById("tabDomain");
  const siteTagEl = document.getElementById("siteTag");
  const timerValEl = document.getElementById("remainingTimer");
  const progressFill = document.getElementById("progressFill");
  const usedMeta = document.getElementById("usedMeta");
  const stateMeta = document.getElementById("stateMeta");

  // Get current active tab
  try {
    let [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab) {
      [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
    }
    if (tab && tab.url) {
      try {
        const u = new URL(tab.url);
        tabDomainEl.textContent = u.hostname || tab.url;
      } catch (e) {
        tabDomainEl.textContent = tab.url;
      }
    }
  } catch (e) {}

  // Fetch status from ScreenGuard Desktop App
  try {
    const res = await fetch(`${API_BASE}/api/status`);
    if (!res.ok) throw new Error("Status failed");

    const data = await res.json();

    // Connection Status
    connPill.className = "conn-pill connected";
    connPill.querySelector(".text").textContent = "Connected";

    // Timer & Progress
    const remaining = data.remaining_seconds || 0;
    const used = data.used_seconds || 0;
    const budgetMins = data.budget_minutes || 60;
    const totalSecs = budgetMins * 60;

    timerValEl.textContent = formatTime(remaining);
    
    timerValEl.className = "timer-value";
    if (remaining <= 0 && data.in_focus_hours) {
      timerValEl.classList.add("expired");
    } else if (remaining < 300) {
      timerValEl.classList.add("warning");
    }

    const pct = Math.min(100, Math.max(0, (used / totalSecs) * 100));
    progressFill.style.width = `${pct}%`;

    const usedMins = Math.floor(used / 60);
    usedMeta.textContent = `Used: ${usedMins}m / ${budgetMins}m`;

    if (!data.in_focus_hours) {
      stateMeta.textContent = "Off-Hours (Unlimited)";
    } else if (data.is_time_up) {
      stateMeta.textContent = "Budget Exhausted";
    } else {
      stateMeta.textContent = "Focus Active";
    }

    // Active Tab Classification
    if (data.is_whitelisted) {
      siteTagEl.className = "site-tag unlimited";
      siteTagEl.textContent = "Study (Unlimited)";
    } else if (data.in_focus_hours && data.is_time_up) {
      siteTagEl.className = "site-tag locked";
      siteTagEl.textContent = "Locked";
    } else if (data.in_focus_hours) {
      siteTagEl.className = "site-tag draining";
      siteTagEl.textContent = "Focus Budget";
    } else {
      siteTagEl.className = "site-tag unlimited";
      siteTagEl.textContent = "Off-Hours";
    }

  } catch (err) {
    // ScreenGuard desktop app is offline
    connPill.className = "conn-pill disconnected";
    connPill.querySelector(".text").textContent = "Offline";

    siteTagEl.className = "site-tag";
    siteTagEl.textContent = "Disconnected";

    timerValEl.textContent = "--:--";
    timerValEl.className = "timer-value";
    progressFill.style.width = "0%";
    usedMeta.textContent = "Used: --";
    stateMeta.textContent = "Desktop app not running";
  }
}

// Button to bring ScreenGuard GUI to front
document.getElementById("openAppBtn").addEventListener("click", async () => {
  const btn = document.getElementById("openAppBtn");
  try {
    btn.textContent = "Opening...";
    await fetch(`${API_BASE}/api/show`);
    setTimeout(() => {
      window.close();
    }, 400);
  } catch (e) {
    btn.textContent = "Start ScreenGuard.exe first";
    setTimeout(() => {
      btn.innerHTML = `
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
        </svg>
        Open ScreenGuard App
      `;
    }, 2000);
  }
});

// Update every second while popup is open
updateUI();
setInterval(updateUI, 1000);
