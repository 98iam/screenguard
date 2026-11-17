export interface Env {
  SCREENSHOTS: R2Bucket;
  DB: D1Database;
  GUARDIAN_SECRET?: string;
}

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { headers: corsHeaders });
    }

    try {
      // 1. Upload Incident & Screenshot
      if (request.method === "POST" && url.pathname === "/api/incidents") {
        const formData = await request.formData();
        const deviceId = formData.get("device_id")?.toString() || "Unknown";
        const packageName = formData.get("package_name")?.toString() || "";
        const appName = formData.get("app_name")?.toString() || "";
        const matchedWord = formData.get("matched_word")?.toString() || "";
        const timestamp = parseInt(formData.get("timestamp")?.toString() || Date.now().toString());
        const screenshotFile = formData.get("screenshot") as File | null;

        const id = crypto.randomUUID();
        let screenshotKey: string | null = null;

        if (screenshotFile) {
          screenshotKey = `screenshots/${deviceId}/${timestamp}_${id.slice(0, 8)}.webp`;
          await env.SCREENSHOTS.put(screenshotKey, await screenshotFile.arrayBuffer(), {
            httpMetadata: { contentType: screenshotFile.type || "image/webp" },
          });
        }

        await env.DB.prepare(
          `INSERT INTO incidents (id, device_id, package_name, app_name, matched_word, screenshot_key, timestamp)
           VALUES (?, ?, ?, ?, ?, ?, ?)`
        )
          .bind(id, deviceId, packageName, appName, matchedWord, screenshotKey, timestamp)
          .run();

        return new Response(JSON.stringify({ status: "ok", id, screenshot_key: screenshotKey }), {
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      // 2. Fetch Incidents Feed (for Guardian App)
      if (request.method === "GET" && url.pathname === "/api/incidents") {
        const limit = Math.min(100, parseInt(url.searchParams.get("limit") || "30"));
        const { results } = await env.DB.prepare(
          `SELECT id, device_id, package_name, app_name, matched_word, screenshot_key, timestamp, created_at
           FROM incidents ORDER BY timestamp DESC LIMIT ?`
        )
          .bind(limit)
          .all();

        const enriched = results.map((item: any) => ({
          ...item,
          screenshot_url: item.screenshot_key ? `${url.origin}/api/screenshot/${encodeURIComponent(item.screenshot_key)}` : null,
        }));

        return new Response(JSON.stringify({ incidents: enriched }), {
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      // 3. Serve Screenshot from R2
      if (request.method === "GET" && url.pathname.startsWith("/api/screenshot/")) {
        const key = decodeURIComponent(url.pathname.replace("/api/screenshot/", ""));
        const object = await env.SCREENSHOTS.get(key);

        if (!object) {
          return new Response("Screenshot Not Found", { status: 404, headers: corsHeaders });
        }

        const headers = new Headers(corsHeaders);
        object.writeHttpMetadata(headers);
        headers.set("etag", object.httpEtag);
        headers.set("Cache-Control", "public, max-age=86400");

        return new Response(object.body, { headers });
      }

      // 4. Request a Temporary Pause (Monitored App -> Cloudflare)
      if (request.method === "POST" && url.pathname === "/api/requests/pause") {
        const body = (await request.json()) as any;
        const deviceId = body.device_id || "Unknown";
        const durationMins = parseInt(body.duration_minutes || "15");
        const id = crypto.randomUUID();
        const requestedAt = Date.now();

        await env.DB.prepare(
          `INSERT INTO pause_requests (id, device_id, duration_minutes, status, requested_at)
           VALUES (?, ?, ?, 'PENDING', ?)`
        )
          .bind(id, deviceId, durationMins, requestedAt)
          .run();

        return new Response(JSON.stringify({ status: "ok", request_id: id }), {
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      // 5. Guardian Fetches Pending Requests
      if (request.method === "GET" && url.pathname === "/api/requests") {
        const { results } = await env.DB.prepare(
          `SELECT id, device_id, duration_minutes, status, requested_at, approved_until, created_at
           FROM pause_requests ORDER BY requested_at DESC LIMIT 20`
        ).all();

        return new Response(JSON.stringify({ requests: results }), {
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      // 6. Guardian Approves or Rejects Request
      if (request.method === "POST" && url.pathname === "/api/requests/respond") {
        const body = (await request.json()) as any;
        const requestId = body.request_id;
        const decision = body.decision; // "APPROVED" or "REJECTED"

        if (!requestId || !["APPROVED", "REJECTED"].includes(decision)) {
          return new Response(JSON.stringify({ error: "Invalid parameters" }), { status: 400, headers: corsHeaders });
        }

        const reqRecord: any = await env.DB.prepare(`SELECT * FROM pause_requests WHERE id = ?`).bind(requestId).first();

        if (!reqRecord) {
          return new Response(JSON.stringify({ error: "Request not found" }), { status: 404, headers: corsHeaders });
        }

        const now = Date.now();
        const approvedUntil = decision === "APPROVED" ? now + reqRecord.duration_minutes * 60 * 1000 : 0;

        await env.DB.prepare(
          `UPDATE pause_requests SET status = ?, approved_until = ? WHERE id = ?`
        )
          .bind(decision, approvedUntil, requestId)
          .run();

        return new Response(JSON.stringify({ status: "ok", decision, approved_until: approvedUntil }), {
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      // 7. Device Status Check (Is Protection Currently Paused by Guardian?)
      if (request.method === "GET" && url.pathname === "/api/status") {
        const deviceId = url.searchParams.get("device_id");
        if (!deviceId) {
          return new Response(JSON.stringify({ is_paused: false }), {
            headers: { ...corsHeaders, "Content-Type": "application/json" },
          });
        }

        const now = Date.now();
        const activePause: any = await env.DB.prepare(
          `SELECT * FROM pause_requests 
           WHERE device_id = ? AND status = 'APPROVED' AND approved_until > ? 
           ORDER BY approved_until DESC LIMIT 1`
        )
          .bind(deviceId, now)
          .first();

        const isPaused = Boolean(activePause);
        const remainingSeconds = activePause ? Math.max(0, Math.floor((activePause.approved_until - now) / 1000)) : 0;

        return new Response(
          JSON.stringify({
            is_paused: isPaused,
            remaining_seconds: remainingSeconds,
            approved_until: activePause ? activePause.approved_until : 0,
          }),
          { headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      return new Response(JSON.stringify({ status: "ok", message: "ScreenGuard Cloudflare API Active" }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    } catch (err: any) {
      return new Response(JSON.stringify({ error: err.message || "Internal Error" }), {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }
  },
};
