# Google Home Integration — Setup Guide

Voice-control your OpenBeken bulbs via Google Home: **"Hey Google, turn on the kitchen lights"**, **"Hey Google, activate Rainbow Party"**, etc.

## Architecture

```
Hey Google → Google Smart Home API → HTTPS webhook (ngrok) → Java Server (port 8080)
                                                                    ↓ MQTT
                                                             Moquette Broker (1883)
                                                                    ↓
                                                           OpenBeken Bulbs (WiFi)
```

## Prerequisites

- [ ] Mac running the Java server (port 8080 reachable)
- [ ] [ngrok](https://ngrok.com/) installed: `brew install ngrok`
- [ ] Google account
- [ ] Google Home app on phone (same Google account)

---

## Part 1 — Start the Java Server

```bash
# From the repo root:
./gradlew :mqtt:googleHome
```

On first run, the server **auto-generates a token** and prints it:
```
⚠ GOOGLE_HOME_TOKEN not set — generated one-time token.
⚠ Set it permanently: export GOOGLE_HOME_TOKEN="abc123-uuid-here"

┌─────────────────────────────────────────────────┐
│  AUTH TOKEN (copy this for Google Cloud setup): │
│  abc123-uuid-here                               │
└─────────────────────────────────────────────────┘
```

**Copy that token** and add it to your shell profile so it's stable across restarts:
```bash
echo 'export GOOGLE_HOME_TOKEN="abc123-uuid-here"' >> ~/.zshrc
source ~/.zshrc
./gradlew :mqtt:googleHome   # now uses the stable token
```

> **Important**: The token must be the same every time you restart the server. If it changes,
> Google's account linking breaks and you have to re-link in the Google Home app.

> **Note**: `GOOGLE_HOME_TOKEN` is NOT the API key from Cloud Console. It's your own custom
> string — Google will send it back to you as a Bearer token to authenticate fulfillment
> requests. You choose the value.

---

## Part 2 — Expose via ngrok

In a new terminal:
```bash
ngrok http 8080
```

ngrok prints a public HTTPS URL, e.g.: `https://abc123.ngrok-free.app`

**Copy this URL** — you'll need it in Part 3.

Test that it works:
```bash
curl https://abc123.ngrok-free.app/health
# Should return: OK
```

---

## Part 3 — Google Home Developer Console Setup

> **⚠️ Important**: The new Google Home Developer Console (console.home.google.com) has two
> integration types: **Matter** (for physical chips) and **Cloud-to-cloud** (for API/HTTPS
> webhooks like ours). You want **Cloud-to-cloud** — NOT Matter.

### 3.1 Navigate to Cloud-to-cloud

1. Go to [console.home.google.com](https://console.home.google.com/)
2. Select your project (e.g. `mqtt-lights`)
3. In the **left sidebar**, look for **Cloud-to-cloud** (not Matter, not Local Home)
   - Direct URL: `https://console.home.google.com/projects/mqtt-lights/cloud-to-cloud`
4. Click **Add integration** (or the **+** button)

### 3.2 Fill in the Integration Form

The Cloud-to-cloud form has **5 required fields** (replace `YOUR-NGROK-URL`):

| Field | Value |
|-------|-------|
| **Client ID** | `home-lights` |
| **Client secret** | `home-lights-secret` |
| **Authorization URL** | `https://YOUR-NGROK-URL/auth` |
| **Token URL** | `https://YOUR-NGROK-URL/token` |
| **Cloud fulfillment URL** | `https://YOUR-NGROK-URL/fulfillment` |

Example (if ngrok gave you `https://abc123.ngrok-free.app`):
```
Client ID:            home-lights
Client secret:        home-lights-secret
Authorization URL:    https://abc123.ngrok-free.app/auth
Token URL:            https://abc123.ngrok-free.app/token
Cloud fulfillment URL:https://abc123.ngrok-free.app/fulfillment
```

> **Before saving**: verify your server responds:
> ```bash
> curl https://YOUR-NGROK-URL/health   # should return: OK
> ```

Click **Save** / **Next** (draft/test deployment is fine — no app review needed for personal use).

### 3.4 Enable HomeGraph API

1. Go to [console.cloud.google.com](https://console.cloud.google.com/)
2. Select the **same project** (`mqtt-lights`)
3. Search for **HomeGraph API** → Enable it

> **Note**: If you don't see "Cloud-to-cloud" in the sidebar, your project type may have been
> set to Matter only. Create a new project and on the first screen choose
> **Cloud-to-cloud** as the integration type.

---

## Part 4 — Link to Google Home App

1. Open **Google Home app** on your phone
2. Tap **+** → **Set up device** → **Works with Google**
3. Search for your project name (e.g. "Home Lights")
4. Tap it → Sign in (any credentials work — our OAuth auto-approves)
5. Google will SYNC and discover all your devices

You should see all your rooms and lights appear in the Google Home app.

---

## Part 5 — Voice Commands

Once linked, you can say:

```
"Hey Google, turn on the kitchen"
"Hey Google, turn off the living room"
"Hey Google, set the kitchen to 50%"
"Hey Google, set the kitchen to red"
"Hey Google, activate Rainbow Party"
"Hey Google, activate Warm White"
"Hey Google, turn on Kitchen Panel 1"
```

---

## Part 6 — Adding More Devices

As you flash more bulbs with OpenBeken:

1. Edit `~/.mqtt/google-home-devices.json` (see note below)
2. Add the device with its IP and room:
   ```json
   { "id": "K5", "name": "Kitchen Panel 5", "room": "Kitchen", "ip": "192.168.86.XX" }
   ```
3. Restart the server: `./gradlew :mqtt:googleHome`
4. In Google Home app: **Settings → Home → [your home] → More → Manage → Re-sync**

> **Config file location**: `GoogleHomeMain` reads device config from
> **`~/.mqtt/google-home-devices.json`** at startup. This is the live, authoritative copy.
> The file previously at `mqtt/src/main/resources/google-home-devices.json` is no longer
> used and has been removed from the repo. Edit only the `~/.mqtt/` copy.

---

## Part 7 — Make it Permanent (Optional)

For 24/7 operation, run the server as a background service:

```bash
# Create a startup script
cat > ~/start-google-home.sh << 'EOF'
#!/bin/bash
cd /Users/treemark/Development/git_reposatories/zigbee
export GOOGLE_HOME_TOKEN="my-secret-token-change-this"
./gradlew :mqtt:googleHome >> /tmp/google-home.log 2>&1
EOF
chmod +x ~/start-google-home.sh

# Use a persistent ngrok tunnel (requires ngrok account):
ngrok http 8080 --log=stdout >> /tmp/ngrok.log &
```

Or use a fixed ngrok subdomain (paid plan): `ngrok http 8080 --subdomain=my-lights`

---

## Diagnostics — "Something went wrong"

When the Google Home app says "something went wrong" during account linking, check these:

**Step 1**: Open the ngrok inspector in your browser: `http://localhost:4040`
- You should see incoming requests to `/auth` and `/token` when you tap the device in the app
- If no requests appear → ngrok is down or the URLs in the Cloud console are wrong

**Step 2**: Check the server terminal for log lines:
```
[Fulfillment] ← ...   (inbound request)
[Fulfillment] → ...   (outbound response)
```
If nothing appears → Google can't reach the server

**Step 3**: Verify the token is non-empty:
```bash
# The server terminal should print:
# AUTH TOKEN: some-uuid-string-here
# If it shows an empty token, stop the server and:
export GOOGLE_HOME_TOKEN="pick-any-string-you-want"
./gradlew :mqtt:googleHome
```

**Step 4**: Verify the OAuth endpoints directly:
```bash
# Test auth endpoint (should return a 302 redirect):
curl -v "https://YOUR-NGROK-URL/auth?response_type=code&client_id=home-lights&redirect_uri=https://example.com&state=test"

# Test token endpoint (should return JSON with access_token):
curl -X POST "https://YOUR-NGROK-URL/token" \
  -d "grant_type=authorization_code&code=test&client_id=home-lights&client_secret=home-lights-secret"
# Expected: {"access_token":"...","token_type":"Bearer",...}
```

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| "Something went wrong" on link | See Diagnostics section above; most likely empty/mismatched token |
| Google says "device unavailable" | Check server is running & ngrok tunnel is active |
| "Hey Google" doesn't find the room | Re-sync in Google Home: Devices → ⋮ → Sync |
| Light responds but wrong room | Edit `~/.mqtt/google-home-devices.json`, change room, restart & re-sync |
| Rainbow doesn't stop | Say "Hey Google, turn off Rainbow Party" or restart server |
| New bulb not showing | Add IP to `~/.mqtt/google-home-devices.json`, restart server, re-sync |
| ngrok URL changed | Update Authorization/Token/Fulfillment URLs in Cloud console → re-link |
| Token changed after restart | Set `GOOGLE_HOME_TOKEN` in `~/.zshrc` for stable token |

---

## File Structure

```
mqtt/
├── GOOGLE_HOME_SETUP.md                     ← This file
└── src/main/java/com/openbeken/google/
    ├── GoogleHomeMain.java                  ← Entry point (./gradlew :mqtt:googleHome)
    ├── GoogleHomeServer.java                ← HTTP server (port 8080)
    ├── OAuthHandler.java                    ← GET /auth, POST /token
    ├── FulfillmentHandler.java              ← POST /fulfillment (SYNC/QUERY/EXECUTE)
    ├── SceneExecutor.java                   ← Animation scenes (rainbow, warm, cool)
    ├── DeviceRegistry.java                  ← Loads google-home-devices.json
    └── GoogleDevice.java                    ← Device/Scene model

~/.mqtt/
└── google-home-devices.json                 ← Live device config (edit this one)
```

> **Note**: The device config file lives at `~/.mqtt/google-home-devices.json`, outside
> the repo. `GoogleHomeMain` loads it from there at startup. The copy previously at
> `src/main/resources/google-home-devices.json` has been removed.

## OAuth Credentials Reference

These are hardcoded in `OAuthHandler.java` for personal/home use:

| Setting | Value |
|---------|-------|
| Client ID | `home-lights` |
| Client Secret | `home-lights-secret` |
| Token | Set via `GOOGLE_HOME_TOKEN` env var |

To change the client ID/secret, edit the constants in `OAuthHandler.java`.

---

## Session Summary — 2026-04-25

### What Was Built

A complete Google Smart Home fulfillment server integrated into the `mqtt` Gradle subproject. The architecture:

```
Google Home voice command
    → Google Smart Home API (Cloud)
    → HTTPS webhook via ngrok → Java HTTP server (port 8080)
        - GET/POST /auth     (OAuth2 auto-approve)
        - POST      /token   (OAuth2 token exchange)
        - POST      /fulfillment (SYNC / QUERY / EXECUTE)
    → Paho MQTT client → Moquette broker (port 1883)
    → OpenBeken bulbs (via cmnd/{topic}/POWER1 etc.)
```

**New Java files** (`mqtt/src/main/java/com/openbeken/google/`):
- `GoogleHomeMain.java` — entry point, reads env vars, starts everything
- `GoogleHomeServer.java` — embedded JDK HttpServer on port 8080
- `OAuthHandler.java` — auto-approving OAuth2 for personal use
- `FulfillmentHandler.java` — SYNC / QUERY / EXECUTE intents
- `SceneExecutor.java` — rainbow/warm/cool animation scenes
- `DeviceRegistry.java` — loads `~/.mqtt/google-home-devices.json`
- `GoogleDevice.java` — POJO for lights and scenes

**Device config**: `~/.mqtt/google-home-devices.json` — 40 devices pre-mapped to rooms.
(The copy previously at `mqtt/src/main/resources/google-home-devices.json` has been removed.)

### Current State (End of Session)

✅ **Working:**
- OAuth account linking (Google Home app → "[test] 6688-mqtt" → linked)
- SYNC — all flashed lights appear in Google Home app by room
- On/Off via voice ("Hey Google, turn on/off Kitchen Panel 1")
- Basic MQTT dispatch confirmed working

⚠️ **Verified, but not tested with real bulbs yet:**
- Brightness ("set kitchen to 50%") → `cmnd/{topic}/Dimmer`
- Color ("set kitchen to red") → `cmnd/{topic}/HsbColor`
- Scenes ("activate Rainbow Party") → `SceneExecutor` rainbow thread

❌ **Known Remaining Issues (to fix next session):**
1. **Most bulbs not yet flashed** — only `obk17811957` (O3 at 192.168.86.66) is confirmed flashed with OpenBeken. K5, K6, G1, G2 have no IP in `google-home-devices.json`. Many others need flashing via CloudCutter.
2. **ngrok URL changes on restart** — Authorization URL, Token URL, and Fulfillment URL in Google Cloud console must be updated every time ngrok restarts. Consider ngrok static domain (paid) or Cloudflare Tunnel (free).
3. **State tracking is in-memory** — QUERY returns last-seen state from EXECUTE commands, not real device state. If a bulb is manually turned off, Google doesn't know. Fix: subscribe to `stat/#` topics and update state from MQTT telemetry.
4. **Room control latency** — When saying "turn on the kitchen" for 9 bulbs, each gets a separate MQTT publish. Consider publishing to the `animations` group topic instead.
5. **Scenes not responding to "stop"** — Saying "Hey Google, turn off Rainbow Party" doesn't stop the animation thread. Need to implement scene deactivation.
6. **Token expires on server restart** — If `GOOGLE_HOME_TOKEN` isn't set in `~/.zshrc`, a new UUID is generated and Google's stored access token becomes invalid, requiring re-link.

### Your Environment Variables (set in `~/.zshrc`)

```bash
export GOOGLE_HOME_TOKEN="meow123"       # your auth token
export GOOGLE_OAUTH_CLIENT_ID="6688-mqtt"     # your Google Cloud client ID
export GOOGLE_OAUTH_CLIENT_SECRET="meow123"   # your Google Cloud client secret
```

### Your Google Cloud Setup

| Setting | Value |
|---------|-------|
| Project | `MQTT Lights` (`mqtt-lights-47894`) |
| Client ID | `6688-mqtt` |
| Client Secret | `meow123` |
| HomeGraph API | Enabled |
| Auth URL | `https://{ngrok}/auth` |
| Token URL | `https://{ngrok}/token` |
| Fulfillment URL | `https://{ngrok}/fulfillment` |

> **Note**: The ngrok URL changes on restart. After restarting ngrok, update all 3 URLs in
> `https://console.home.google.com/projects/mqtt-lights/cloud-to-cloud` and re-link.

### To Resume Next Session

```bash
# 1. Start the server
source ~/.zshrc
./gradlew :mqtt:googleHome

# 2. Start ngrok (in a new terminal)
ngrok http 8080

# 3. If ngrok URL changed, update the 3 URLs in:
#    https://console.home.google.com/projects/mqtt-lights/cloud-to-cloud
#    Then in Google Home app: + → Works with Google → "[test] 6688-mqtt" → re-link

# 4. Test MQTT directly (O3 is the only confirmed flashed bulb):
mosquitto_pub -h localhost -p 1883 -t 'cmnd/obk17811957/POWER1' -m '1'
```

### Next Steps for Next Session

- [ ] **Flash more bulbs** via CloudCutter and add their IPs to `google-home-devices.json`
- [ ] **MQTT state subscription** — subscribe to `stat/#` in FulfillmentHandler to track real device state for QUERY responses
- [ ] **Group MQTT for rooms** — when Google commands a whole room, publish to `cmnd/animations/POWER1` instead of individual devices
- [ ] **Stop animations** — implement scene deactivation (ActivateScene with `deactivate=true` param)
- [ ] **Persistent ngrok** — set up ngrok static domain or Cloudflare Tunnel so the URL never changes
- [ ] **Test color/brightness** with real bulbs
