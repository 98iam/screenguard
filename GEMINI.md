# ScreenGuard Project Instructions

1. **Code & Architecture:**
   - Follow the **ponytail** approach: write the simplest, cleanest code without unnecessary libraries or bloat.
   - Use clean, professional frontend design (avoiding generic AI aesthetics).

2. **Package Management:**
   - Always use `uv` for Python packages.
   - Always use `bun` for JavaScript/TypeScript packages.

3. **Android Build & Google Drive Sync:**
   - Whenever APKs are built or updated, always sync both `ScreenGuard-Child.apk` and `ScreenGuard-Parent.apk` to Google Drive via `push_apks_to_gdrive.ps1` or `gdrive mutate update`.
