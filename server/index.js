require('dotenv').config();

const express = require('express');
const cors = require('cors');
const crypto = require('crypto');
const { createClient } = require('@supabase/supabase-js');

const app = express();

app.use(cors({
  origin: '*',
  methods: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
  allowedHeaders: ['Content-Type', 'Authorization', 'X-Client-Info', 'Apikey'],
}));
app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true }));

const SUPABASE_URL = process.env.SUPABASE_URL;
const SUPABASE_SERVICE_ROLE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY;
const OXAPAY_MERCHANT_KEY = process.env.OXAPAY_MERCHANT_KEY;

if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
  console.error('FATAL: SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY must be set');
  process.exit(1);
}

if (!OXAPAY_MERCHANT_KEY) {
  console.error('WARNING: OXAPAY_MERCHANT_KEY is not set — payment creation will fail');
}

const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
  auth: { autoRefreshToken: false, persistSession: false },
});

// ── Middleware: verify Supabase JWT from Authorization header ──

async function authMiddleware(req, res, next) {
  try {
    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      return res.status(401).json({ error: 'Missing authorization header' });
    }
    const token = authHeader.split(' ')[1];

    const { data: { user }, error } = await supabase.auth.getUser(token);
    if (error || !user) {
      return res.status(401).json({ error: 'Invalid or expired token' });
    }

    req.user = user;
    req.accessToken = token;
    next();
  } catch (err) {
    return res.status(401).json({ error: 'Authentication failed' });
  }
}

// ── Health check ──

app.get('/health', (req, res) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

// ── Auth: Signup ──

app.post('/api/auth/signup', async (req, res) => {
  try {
    const { email, password } = req.body;
    if (!email || !password) {
      return res.status(400).json({ error: 'Email and password are required' });
    }
    if (password.length < 6) {
      return res.status(400).json({ error: 'Password must be at least 6 characters' });
    }

    const { data, error } = await supabase.auth.admin.createUser({
      email: email.trim().toLowerCase(),
      password,
      email_confirm: true,
    });

    if (error) {
      if (error.message.includes('already') || error.message.includes('exists')) {
        return res.status(409).json({ error: 'An account with this email already exists' });
      }
      return res.status(400).json({ error: error.message });
    }

    // Create profile
    await supabase.from('profiles').insert({
      id: data.user.id,
      email: email.trim().toLowerCase(),
    });

    // Sign in to get session tokens
    const { data: sessionData, error: sessionError } = await supabase.auth.signInWithPassword({
      email: email.trim().toLowerCase(),
      password,
    });

    if (sessionError) {
      return res.status(200).json({
        message: 'Account created. Please login.',
        userId: data.user.id,
      });
    }

    res.json({
      message: 'Account created successfully',
      accessToken: sessionData.session.access_token,
      refreshToken: sessionData.session.refresh_token,
      user: {
        id: data.user.id,
        email: data.user.email,
      },
    });
  } catch (err) {
    console.error('Signup error:', err);
    res.status(500).json({ error: 'Internal server error' });
  }
});

// ── Auth: Login ──

app.post('/api/auth/login', async (req, res) => {
  try {
    const { email, password } = req.body;
    if (!email || !password) {
      return res.status(400).json({ error: 'Email and password are required' });
    }

    const { data, error } = await supabase.auth.signInWithPassword({
      email: email.trim().toLowerCase(),
      password,
    });

    if (error) {
      return res.status(401).json({ error: 'Invalid email or password' });
    }

    res.json({
      message: 'Login successful',
      accessToken: data.session.access_token,
      refreshToken: data.session.refresh_token,
      user: {
        id: data.user.id,
        email: data.user.email,
      },
    });
  } catch (err) {
    console.error('Login error:', err);
    res.status(500).json({ error: 'Internal server error' });
  }
});

// ── Auth: Refresh token ──

app.post('/api/auth/refresh', async (req, res) => {
  try {
    const { refreshToken } = req.body;
    if (!refreshToken) {
      return res.status(400).json({ error: 'Refresh token required' });
    }

    const { data, error } = await supabase.auth.refreshSession({
      refresh_token: refreshToken,
    });

    if (error || !data.session) {
      return res.status(401).json({ error: 'Invalid refresh token' });
    }

    res.json({
      accessToken: data.session.access_token,
      refreshToken: data.session.refresh_token,
      user: {
        id: data.user.id,
        email: data.user.email,
      },
    });
  } catch (err) {
    console.error('Refresh error:', err);
    res.status(500).json({ error: 'Internal server error' });
  }
});

// ── Auth: Verify session ──

app.get('/api/auth/verify', authMiddleware, async (req, res) => {
  res.json({
    valid: true,
    user: {
      id: req.user.id,
      email: req.user.email,
    },
  });
});

// ── Get plans ──

app.get('/api/plans', async (req, res) => {
  try {
    const { data, error } = await supabase
      .from('plans')
      .select('*')
      .eq('is_active', true)
      .order('sort_order', { ascending: true });

    if (error) throw error;
    res.json({ plans: data || [] });
  } catch (err) {
    console.error('Get plans error:', err);
    res.status(500).json({ error: 'Failed to fetch plans' });
  }
});

// ── Get subscription status ──

app.get('/api/subscription/status', authMiddleware, async (req, res) => {
  try {
    // First expire any overdue subscriptions
    await supabase.rpc('expire_subscriptions');

    const { data, error } = await supabase.rpc('get_active_subscription', {
      p_user_id: req.user.id,
    });

    if (error) throw error;

    if (!data || data.length === 0) {
      return res.json({
        active: false,
        subscription: null,
      });
    }

    const sub = data[0];
    res.json({
      active: true,
      subscription: {
        id: sub.sub_id,
        planName: sub.plan_name,
        planNameAr: sub.plan_name_ar,
        price: parseFloat(sub.price),
        startsAt: sub.starts_at,
        expiresAt: sub.expires_at,
        daysRemaining: sub.days_remaining,
      },
    });
  } catch (err) {
    console.error('Subscription status error:', err);
    res.status(500).json({ error: 'Failed to check subscription status' });
  }
});

// ── Create payment (OxaPay invoice) ──

app.post('/api/payments/create', authMiddleware, async (req, res) => {
  try {
    const { planId } = req.body;
    if (!planId) {
      return res.status(400).json({ error: 'Plan ID is required' });
    }

    if (!OXAPAY_MERCHANT_KEY) {
      return res.status(500).json({ error: 'Payment gateway not configured' });
    }

    // Get plan
    const { data: plan, error: planError } = await supabase
      .from('plans')
      .select('*')
      .eq('id', planId)
      .eq('is_active', true)
      .single();

    if (planError || !plan) {
      return res.status(404).json({ error: 'Plan not found or inactive' });
    }

    // Create OxaPay invoice
    const callbackUrl = `${process.env.SERVER_URL || 'https://your-server-url.railway.app'}/api/payments/webhook`;
    const returnUrl = `${process.env.SERVER_URL || 'https://your-server-url.railway.app'}/api/payments/return?track_id=PLACEHOLDER`;

    const invoicePayload = {
      merchant: OXAPAY_MERCHANT_KEY,
      amount: parseFloat(plan.price),
      currency: 'USD',
      lifeTime: 60,
      feePaidByPayer: 0,
      underPaidCover: 2,
      callbackUrl: callbackUrl,
      returnUrl: returnUrl,
      orderId: `${req.user.id}_${plan.id}_${Date.now()}`,
      email: req.user.email || '',
      description: `Virtual Cam ${plan.name} subscription`,
    };

    const oxapayResponse = await fetch('https://api.oxapay.com/merchants/request', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(invoicePayload),
    });

    const oxapayData = await oxapayResponse.json();

    if (oxapayData.status !== 100 || !oxapayData.trackId) {
      console.error('OxaPay invoice creation failed:', oxapayData);
      return res.status(400).json({
        error: 'Failed to create payment invoice',
        details: oxapayData.message || 'Unknown error',
      });
    }

    // Create payment record
    const { data: payment, error: paymentError } = await supabase
      .from('payments')
      .insert({
        user_id: req.user.id,
        plan_id: plan.id,
        track_id: oxapayData.trackId,
        amount: parseFloat(plan.price),
        currency: 'USD',
        status: 'pending',
        pay_address: oxapayData.payAddress || null,
        oxapay_response: oxapayData,
      })
      .select()
      .single();

    if (paymentError) throw paymentError;

    res.json({
      paymentId: payment.id,
      trackId: oxapayData.trackId,
      paymentUrl: oxapayData.payLink,
      payAddress: oxapayData.payAddress || null,
      amount: parseFloat(plan.price),
      currency: 'USD',
      status: 'pending',
    });
  } catch (err) {
    console.error('Create payment error:', err);
    res.status(500).json({ error: 'Failed to create payment' });
  }
});

// ── Check payment status ──

app.get('/api/payments/status/:trackId', authMiddleware, async (req, res) => {
  try {
    const { trackId } = req.params;

    const { data: payment, error } = await supabase
      .from('payments')
      .select('*')
      .eq('track_id', trackId)
      .eq('user_id', req.user.id)
      .single();

    if (error || !payment) {
      return res.status(404).json({ error: 'Payment not found' });
    }

    // If still pending, query OxaPay for the latest status
    if (payment.status === 'pending' && OXAPAY_MERCHANT_KEY) {
      try {
        const infoResponse = await fetch(`https://api.oxapay.com/v1/payment/${trackId}`, {
          method: 'GET',
          headers: {
            'merchant_api_key': OXAPAY_MERCHANT_KEY,
            'Content-Type': 'application/json',
          },
        });
        const infoData = await infoResponse.json();

        if (infoData.status === 200 && infoData.data) {
          const payStatus = (infoData.data.status || '').toLowerCase();

          if (payStatus === 'paid') {
            // Activate subscription
            await activateSubscription(payment);
          } else if (payStatus === 'expired' || payStatus === 'failed') {
            await supabase
              .from('payments')
              .update({ status: payStatus, updated_at: new Date().toISOString(), oxapay_response: infoData })
              .eq('id', payment.id);
          }

          return res.json({
            trackId: trackId,
            status: payStatus === 'paid' ? 'paid' : (payStatus === 'expired' || payStatus === 'failed' ? payStatus : 'pending'),
            amount: parseFloat(payment.amount),
          });
        }
      } catch (pollErr) {
        console.error('OxaPay poll error:', pollErr);
      }
    }

    res.json({
      trackId: trackId,
      status: payment.status,
      amount: parseFloat(payment.amount),
    });
  } catch (err) {
    console.error('Check payment status error:', err);
    res.status(500).json({ error: 'Failed to check payment status' });
  }
});

// ── OxaPay Webhook ──

app.post('/api/payments/webhook', async (req, res) => {
  try {
    const rawBody = JSON.stringify(req.body);
    const hmacHeader = req.headers['hmac'] || '';

    // Verify HMAC signature
    if (OXAPAY_MERCHANT_KEY && hmacHeader) {
      const computedHmac = crypto
        .createHmac('sha512', OXAPAY_MERCHANT_KEY)
        .update(rawBody)
        .digest('hex');

      if (computedHmac !== hmacHeader) {
        console.error('HMAC verification failed');
        return res.status(401).send('Invalid signature');
      }
    }

    const data = req.body;
    const trackId = data.trackId;
    const status = (data.status || '').toLowerCase();

    if (!trackId) {
      return res.status(200).send('ok');
    }

    // Get payment record
    const { data: payment, error } = await supabase
      .from('payments')
      .select('*')
      .eq('track_id', trackId)
      .single();

    if (error || !payment) {
      console.error('Payment not found for trackId:', trackId);
      return res.status(200).send('ok');
    }

    if (status === 'paid') {
      await activateSubscription(payment, data);
    } else if (status === 'expired' || status === 'failed') {
      await supabase
        .from('payments')
        .update({
          status: status,
          updated_at: new Date().toISOString(),
          oxapay_response: data,
        })
        .eq('id', payment.id);
    } else {
      // Waiting or Confirming — update with latest info
      await supabase
        .from('payments')
        .update({
          pay_address: data.address || payment.pay_address,
          pay_currency: data.payCurrency || payment.pay_currency,
          tx_hash: data.txID || payment.tx_hash,
          updated_at: new Date().toISOString(),
          oxapay_response: data,
        })
        .eq('id', payment.id);
    }

    res.status(200).send('ok');
  } catch (err) {
    console.error('Webhook error:', err);
    res.status(200).send('ok');
  }
});

// ── Payment return URL (redirect after payment) ──

app.get('/api/payments/return', (req, res) => {
  const trackId = req.query.track_id || '';
  res.send(`
    <!DOCTYPE html>
    <html>
    <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>Payment Status</title>
      <style>
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #12121F; color: #ECEFF1; display: flex; justify-content: center; align-items: center; min-height: 100vh; margin: 0; }
        .container { text-align: center; padding: 40px; }
        .icon { width: 80px; height: 80px; margin: 0 auto 24px; border-radius: 50%; background: #26A65B; display: flex; align-items: center; justify-content: center; font-size: 40px; }
        h1 { font-size: 24px; margin-bottom: 12px; }
        p { color: #90A4AE; font-size: 14px; }
        .spinner { width: 32px; height: 32px; border: 3px solid #2A2A42; border-top: 3px solid #64B5F6; border-radius: 50%; animation: spin 1s linear infinite; margin: 20px auto; }
        @keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
      </style>
    </head>
    <body>
      <div class="container">
        <div class="icon">&#10003;</div>
        <h1>Payment Processing</h1>
        <p>Please wait while we confirm your payment...</p>
        <div class="spinner"></div>
        <p style="margin-top: 20px; font-size: 12px;">Track ID: ${trackId}</p>
        <p style="margin-top: 16px; font-size: 13px; color: #64B5F6;">You can close this page and return to the app.</p>
      </div>
      <script>
        setTimeout(function() {
          document.querySelector('.icon').innerHTML = '\\u2705';
          document.querySelector('h1').textContent = 'Payment Submitted';
          document.querySelector('p').textContent = 'Your subscription will be activated automatically once confirmed.';
        }, 3000);
      </script>
    </body>
    </html>
  `);
});

// ── Get payment history ──

app.get('/api/payments/history', authMiddleware, async (req, res) => {
  try {
    const { data, error } = await supabase
      .from('payments')
      .select(`
        id,
        track_id,
        amount,
        currency,
        status,
        pay_currency,
        created_at,
        updated_at,
        plans (
          name,
          name_ar
        )
      `)
      .eq('user_id', req.user.id)
      .order('created_at', { ascending: false })
      .limit(50);

    if (error) throw error;

    res.json({ payments: data || [] });
  } catch (err) {
    console.error('Payment history error:', err);
    res.status(500).json({ error: 'Failed to fetch payment history' });
  }
});

// ── Get profile ──

app.get('/api/profile', authMiddleware, async (req, res) => {
  try {
    const { data, error } = await supabase
      .from('profiles')
      .select('*')
      .eq('id', req.user.id)
      .single();

    if (error) {
      // Create profile if missing
      const { data: newProfile } = await supabase
        .from('profiles')
        .insert({
          id: req.user.id,
          email: req.user.email,
        })
        .select()
        .single();

      return res.json({ profile: newProfile });
    }

    res.json({ profile: data });
  } catch (err) {
    console.error('Get profile error:', err);
    res.status(500).json({ error: 'Failed to fetch profile' });
  }
});

// ── Update profile ──

app.put('/api/profile', authMiddleware, async (req, res) => {
  try {
    const { displayName } = req.body;

    const { data, error } = await supabase
      .from('profiles')
      .update({ display_name: displayName })
      .eq('id', req.user.id)
      .select()
      .single();

    if (error) throw error;
    res.json({ profile: data });
  } catch (err) {
    console.error('Update profile error:', err);
    res.status(500).json({ error: 'Failed to update profile' });
  }
});

// ── Helper: activate subscription after payment ──

async function activateSubscription(payment, webhookData) {
  try {
    // Get plan details
    const { data: plan } = await supabase
      .from('plans')
      .select('*')
      .eq('id', payment.plan_id)
      .single();

    if (!plan) {
      console.error('Plan not found for payment:', payment.id);
      return;
    }

    // Calculate subscription dates
    const now = new Date();
    const expiresAt = new Date(now);
    expiresAt.setUTCDate(expiresAt.getUTCDate() + plan.duration_days);

    // Create subscription
    const { data: subscription, error: subError } = await supabase
      .from('subscriptions')
      .insert({
        user_id: payment.user_id,
        plan_id: plan.id,
        status: 'active',
        starts_at: now.toISOString(),
        expires_at: expiresAt.toISOString(),
      })
      .select()
      .single();

    if (subError) throw subError;

    // Update payment record
    await supabase
      .from('payments')
      .update({
        status: 'paid',
        subscription_id: subscription.id,
        pay_address: webhookData?.address || payment.pay_address,
        pay_currency: webhookData?.payCurrency || payment.pay_currency,
        tx_hash: webhookData?.txID || payment.tx_hash,
        updated_at: new Date().toISOString(),
        oxapay_response: webhookData || null,
      })
      .eq('id', payment.id);

    console.log(`Subscription activated for user ${payment.user_id}, plan ${plan.name}, expires ${expiresAt.toISOString()}`);
  } catch (err) {
    console.error('Activate subscription error:', err);
  }
}

// ── Start server ──

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log(`VCam server running on port ${PORT}`);
});

module.exports = app;
