/*
# Virtual Cam — Auth, Subscription & Payment Schema

Creates the complete database backend for user authentication, subscription plans,
OxaPay crypto payments, and subscription management for the Virtual Cam Android app.

## New Tables
1. profiles — Extended user profile data linked to auth.users
2. plans — Subscription plan definitions (Daily, Weekly, Monthly)
3. subscriptions — Active/past subscriptions for users
4. payments — Payment transaction records (OxaPay)

## Security (RLS)
- profiles: Users can read/update only their own profile
- plans: Public read, no write
- subscriptions: Users can read only their own subscriptions
- payments: Users can read only their own payments
- All writes go through the backend service role (server-side only)
*/

-- ── profiles table ──
CREATE TABLE IF NOT EXISTS profiles (
    id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    email text UNIQUE NOT NULL,
    display_name text,
    created_at timestamptz DEFAULT now()
);

ALTER TABLE profiles ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "select_own_profile" ON profiles;
CREATE POLICY "select_own_profile" ON profiles FOR SELECT
    TO authenticated USING (auth.uid() = id);

DROP POLICY IF EXISTS "update_own_profile" ON profiles;
CREATE POLICY "update_own_profile" ON profiles FOR UPDATE
    TO authenticated USING (auth.uid() = id) WITH CHECK (auth.uid() = id);

-- ── plans table ──
CREATE TABLE IF NOT EXISTS plans (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name text NOT NULL,
    name_ar text NOT NULL,
    price numeric(10,2) NOT NULL,
    duration_days integer NOT NULL,
    description text,
    is_active boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz DEFAULT now()
);

ALTER TABLE plans ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "read_plans" ON plans;
CREATE POLICY "read_plans" ON plans FOR SELECT
    TO anon, authenticated USING (true);

-- ── subscriptions table ──
CREATE TABLE IF NOT EXISTS subscriptions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    plan_id uuid NOT NULL REFERENCES plans(id) ON DELETE RESTRICT,
    status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'expired', 'cancelled')),
    starts_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    created_at timestamptz DEFAULT now()
);

ALTER TABLE subscriptions ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "select_own_subscriptions" ON subscriptions;
CREATE POLICY "select_own_subscriptions" ON subscriptions FOR SELECT
    TO authenticated USING (auth.uid() = user_id);

-- ── payments table ──
CREATE TABLE IF NOT EXISTS payments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    subscription_id uuid REFERENCES subscriptions(id) ON DELETE SET NULL,
    plan_id uuid NOT NULL REFERENCES plans(id) ON DELETE RESTRICT,
    track_id text UNIQUE NOT NULL,
    amount numeric(10,2) NOT NULL,
    currency text NOT NULL DEFAULT 'USD',
    status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'paid', 'failed', 'expired')),
    pay_address text,
    pay_currency text,
    tx_hash text,
    oxapay_response jsonb,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now()
);

ALTER TABLE payments ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "select_own_payments" ON payments;
CREATE POLICY "select_own_payments" ON payments FOR SELECT
    TO authenticated USING (auth.uid() = user_id);

-- ── Indexes ──
CREATE INDEX IF NOT EXISTS idx_subscriptions_user_id ON subscriptions(user_id);
CREATE INDEX IF NOT EXISTS idx_subscriptions_status ON subscriptions(status);
CREATE INDEX IF NOT EXISTS idx_subscriptions_expires_at ON subscriptions(expires_at);
CREATE INDEX IF NOT EXISTS idx_payments_user_id ON payments(user_id);
CREATE INDEX IF NOT EXISTS idx_payments_track_id ON payments(track_id);
CREATE INDEX IF NOT EXISTS idx_payments_status ON payments(status);

-- ── Seed default plans ──
INSERT INTO plans (name, name_ar, price, duration_days, description, is_active, sort_order)
VALUES
    ('Daily', 'يومي', 1.00, 1, '24 hours access', true, 1),
    ('Weekly', 'أسبوعي', 15.00, 7, '7 days access', true, 2),
    ('Monthly', 'شهري', 50.00, 30, '30 days access', true, 3)
ON CONFLICT DO NOTHING;

-- ── Auto-expire subscriptions function ──
CREATE OR REPLACE FUNCTION expire_subscriptions()
RETURNS void AS $$
BEGIN
    UPDATE subscriptions
    SET status = 'expired'
    WHERE status = 'active' AND expires_at < now();
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- ── Function to check if user has active subscription ──
CREATE OR REPLACE FUNCTION has_active_subscription(p_user_id uuid)
RETURNS boolean AS $$
BEGIN
    RETURN EXISTS (
        SELECT 1 FROM subscriptions
        WHERE user_id = p_user_id
        AND status = 'active'
        AND expires_at > now()
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- ── Function to get user's active subscription details ──
CREATE OR REPLACE FUNCTION get_active_subscription(p_user_id uuid)
RETURNS TABLE (
    sub_id uuid,
    plan_name text,
    plan_name_ar text,
    price numeric,
    starts_at timestamptz,
    expires_at timestamptz,
    days_remaining integer
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        s.id,
        p.name,
        p.name_ar,
        p.price,
        s.starts_at,
        s.expires_at,
        CAST(date_part('day', s.expires_at - now()) AS integer) AS days_remaining
    FROM subscriptions s
    JOIN plans p ON s.plan_id = p.id
    WHERE s.user_id = p_user_id
    AND s.status = 'active'
    AND s.expires_at > now()
    ORDER BY s.expires_at DESC
    LIMIT 1;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;