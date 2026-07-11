# Virtual Cam

Virtual Cam is an Android app that lets you inject custom images and videos into your phone's camera feed — compatible with any app that reads from the camera (WhatsApp, Instagram, Zoom, etc.).

## Features

- **User Authentication** — Email/password signup and login (no access codes needed)
- **Subscription System** — Daily ($1), Weekly ($15), Monthly ($50) plans via OxaPay crypto payments
- **Payment Management** — Automatic payment verification and subscription activation
- **Account Page** — View profile, subscription status, and payment history
- **Virtual Camera Injection** — 4 image slots + 4 video slots with floating control window
- **Windows Remote Control** — Conect VCam desktop app for real-time camera control

## Architecture

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Android App     │────▶│  Railway Server  │────▶│   Supabase DB   │
│  (Kotlin)        │◀────│  (Node.js)       │◀────│   (PostgreSQL)  │
└─────────────────┘     └──────┬───────────┘     └─────────────────┘
                               │
                               ▼
                        ┌──────────────┐
                        │   OxaPay     │
                        │   (Crypto)   │
                        └──────────────┘
```

## Setup

### 1. Database (Supabase)

1. Create a Supabase project at [supabase.com](https://supabase.com)
2. Go to **SQL Editor** and paste the entire contents of `database_setup.sql`
3. Run the query — this creates all tables, RLS policies, functions, and seed data

### 2. Backend Server (Railway)

1. Go to [railway.app](https://railway.app) and create a new project
2. Deploy from this GitHub repository
3. Railway will detect the `Dockerfile` automatically
4. Set the following environment variables (see `.env.example`):

   | Variable | Description |
   |----------|-------------|
   | `PORT` | Server port (Railway sets this automatically) |
   | `SERVER_URL` | Your Railway app URL (e.g. `https://your-app.up.railway.app`) |
   | `SUPABASE_URL` | Your Supabase project URL |
   | `SUPABASE_SERVICE_ROLE_KEY` | Supabase service role key (from project settings) |
   | `OXAPAY_MERCHANT_KEY` | Your OxaPay merchant API key |

5. Deploy the server

### 3. OxaPay Configuration

1. Create an account at [oxapay.com](https://oxapay.com)
2. Go to **Merchant Service** and generate a Merchant API Key
3. Set the webhook callback URL in OxaPay to: `https://your-app.up.railway.app/api/payments/webhook`

### 4. Android App

**Where to set the server URL in the app:**

The server URL is configured in `app/src/main/java/com/vcam/utils/ApiClient.kt`:

```kotlin
fun getServerUrl(context: Context): String {
    return prefs(context).getString(KEY_SERVER_URL, "https://your-app.up.railway.app")
}
```

Replace `https://your-app.up.railway.app` with your actual Railway server URL.

### 5. Build

```bash
# Android
./gradlew assembleRelease

# Server
cd server && npm install && npm start
```

## Subscription Flow

1. User signs up / logs in
2. User opens the Subscription page
3. User selects a plan (Daily / Weekly / Monthly)
4. OxaPay payment page opens in a WebView
5. User pays with crypto
6. OxaPay sends webhook to server → server activates subscription
7. App polls payment status and confirms activation
8. Start button becomes enabled in the main app

## Security

- All passwords are hashed by Supabase Auth
- JWT tokens for session management
- RLS (Row Level Security) on all database tables
- HMAC signature verification on OxaPay webhooks
- Service role key only used server-side (never in the app)
- Users can only access their own data

## License

This project is proprietary. All rights reserved.
