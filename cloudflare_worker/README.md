# 🛡 ScreenGuard Cloudflare Worker (Backend API)

High-performance, zero-maintenance backend for ScreenGuard using **Cloudflare Workers**, **Cloudflare R2** (Screenshots Storage), and **Cloudflare D1** (Serverless SQLite Database).

---

## ⚡ Deployment in 3 Easy Steps

From this folder (`cloudflare_worker`):

### 1. Create the R2 Bucket
```bash
npx wrangler r2 bucket create screenguard-screenshots
```

### 2. Create the D1 Database
```bash
npx wrangler d1 create screenguard-db
```
*Wrangler will print a `database_id`. Copy it and replace `REPLACE_WITH_YOUR_D1_DATABASE_ID` in `wrangler.jsonc`.*

### 3. Initialize the Tables & Deploy
```bash
# Apply SQL Schema
npx wrangler d1 execute screenguard-db --remote --file=schema.sql

# Deploy the Worker
npx wrangler deploy
```

Once deployed, Wrangler will give you your public API URL:
```text
https://screenguard-backend.<your-subdomain>.workers.dev
```
Copy and paste that URL into the **ScreenGuard Android App** and the **ScreenGuard Guardian App**!
