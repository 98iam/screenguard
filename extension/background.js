/**
 * ScreenGuard Focus Sync - Background Service Worker (Manifest V3)
 * Ultra-lightweight: zero CPU, event-driven, throttled tab sync.
 */

const API_BASE = "http://127.0.0.1:49221";
let isSending = false;
let lastHeartbeatTime = 0;

function extractDomain(urlStr) {
  if (!urlStr) return "";
  try {
    const parsed = new URL(urlStr);
    return parsed.hostname.toLowerCase();
  } catch (e) {
    return "";
  }
}

function isInternalUrl(urlStr) {
  if (!urlStr) return true;
  const lower = urlStr.toLowerCase();
  return (
    lower.startsWith("chrome://") ||
    lower.startsWith("edge://") ||
    lower.startsWith("brave://") ||
    lower.startsWith("about:") ||
    lower.startsWith("chrome-extension://") ||
    lower.startsWith("devtools://") ||
    lower.startsWith("view-source:")
  );
}

function updateBadge(result) {
  try {
    if (!result || result.status !== "ok") {
      chrome.action.setBadgeText({ text: "" });
      return;
    }

    if (result.is_whitelisted) {
      chrome.action.setBadgeText({ text: "FREE" });
      chrome.action.setBadgeBackgroundColor({ color: "#10b981" });
      chrome.action.setTitle({ title: "ScreenGuard: Whitelisted Study Site (Unlimited)" });
      return;
    }

    if (result.in_focus_hours) {
      if (result.is_time_up) {
        chrome.action.setBadgeText({ text: "LOCK" });
        chrome.action.setBadgeBackgroundColor({ color: "#ef4444" });
        chrome.action.setTitle({ title: "ScreenGuard: Focus Allowance Expired (Locked)" });
      } else {
        const mins = Math.max(0, Math.ceil(result.remaining_seconds / 60));
        chrome.action.setBadgeText({ text: `${mins}m` });
        chrome.action.setBadgeBackgroundColor({ color: "#3b82f6" });
        chrome.action.setTitle({ title: `ScreenGuard: ${mins} minutes remaining today` });
      }
    } else {
      chrome.action.setBadgeText({ text: "" });
      chrome.action.setTitle({ title: "ScreenGuard: Outside Focus Hours (Unlimited)" });
    }
  } catch (e) {}
}

async function sendHeartbeat(force = false) {
  const now = Date.now();
  // Throttle to at most once per 1.5 seconds unless forced by tab switch
  if (!force && (now - lastHeartbeatTime < 1500)) {
    return;
  }
  if (isSending) return;

  isSending = true;
  try {
    const win = await chrome.windows.getLastFocused().catch(() => null);
    const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
    if (!tab || !tab.url) {
      isSending = false;
      return;
    }

    const url = tab.url;
    const domain = extractDomain(url);
    const tabId = tab.id;
    const isFocused = Boolean(win && win.focused);

    lastHeartbeatTime = Date.now();

    const payload = {
      domain: domain,
      url: url,
      is_focused: isFocused
    };

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 2000);

    const response = await fetch(`${API_BASE}/api/heartbeat`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
      signal: controller.signal
    }).catch(() => null);

    clearTimeout(timeoutId);

    if (!response || !response.ok) {
      chrome.storage.local.set({ screenGuardConnected: false });
      updateBadge(null);
      isSending = false;
      return;
    }

    const data = await response.json().catch(() => null);
    if (!data) {
      isSending = false;
      return;
    }

    chrome.storage.local.set({
      screenGuardConnected: true,
      statusData: data,
      currentDomain: domain,
      currentUrl: url
    });

    updateBadge(data);

    // If focus time is up and this is a non-whitelisted standard web page, enforce block screen
    if (data.is_time_up && !data.is_whitelisted && !isInternalUrl(url) && tabId) {
      const blockedPageUrl = chrome.runtime.getURL("blocked.html") + `?target=${encodeURIComponent(url)}&domain=${encodeURIComponent(domain)}`;
      chrome.tabs.update(tabId, { url: blockedPageUrl }).catch(() => {});
    }
  } catch (err) {
    // Suppress errors to avoid Chrome error notifications
  } finally {
    isSending = false;
  }
}

// Instant Tab Switch
chrome.tabs.onActivated.addListener(() => {
  sendHeartbeat(true);
});

// Instant URL Navigation
chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  if (tab.active && (changeInfo.status === "complete" || changeInfo.url)) {
    sendHeartbeat(true);
  }
});

// Window Focus
chrome.windows.onFocusChanged.addListener((windowId) => {
  if (windowId !== chrome.windows.WINDOW_ID_NONE) {
    sendHeartbeat(false);
  }
});

// Regular periodic sync every 2 seconds
setInterval(() => {
  sendHeartbeat(false);
}, 2000);

// Initial heartbeat
sendHeartbeat(true);
