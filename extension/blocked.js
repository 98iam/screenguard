// Parse target domain from query string
const params = new URLSearchParams(window.location.search);
const blockedDomain = params.get("domain") || params.get("target") || "Distracting Website";

const domainEl = document.getElementById("blockedDomain");
if (domainEl) {
  domainEl.textContent = blockedDomain;
}

// "Open ScreenGuard App" button handler
const openBtn = document.getElementById("openScreenGuardBtn");
if (openBtn) {
  openBtn.addEventListener("click", async () => {
    try {
      openBtn.textContent = "Opening ScreenGuard...";
      await fetch("http://127.0.0.1:49221/api/show", { method: "GET" });
      setTimeout(() => {
        openBtn.textContent = "ScreenGuard Opened";
      }, 500);
    } catch (e) {
      openBtn.textContent = "Ensure ScreenGuard is Running";
      setTimeout(() => {
        openBtn.innerHTML = `
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
          </svg>
          Open ScreenGuard App
        `;
      }, 2500);
    }
  });
}
