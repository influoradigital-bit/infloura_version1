-- Demo creators for brand discovery (dev/staging). Password: Password@123

INSERT INTO users (id, email, password_hash, user_type, status, email_verified, phone_verified,
  onboarding_completed, display_name, first_name, last_name, timezone, created_at, updated_at)
VALUES
('01SEED00000000000000000001', 'priya.creates@demo.influora.com',
 '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/X4.G2oXeiKPaPaS0q8a', 'CREATOR', 'ACTIVE', TRUE, FALSE, TRUE,
 'Priya Sharma', 'Priya', 'Sharma', 'Asia/Kolkata', NOW(), NOW()),
('01SEED00000000000000000002', 'arjun.fitness@demo.influora.com',
 '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/X4.G2oXeiKPaPaS0q8a', 'CREATOR', 'ACTIVE', TRUE, FALSE, TRUE,
 'Arjun Mehta', 'Arjun', 'Mehta', 'Asia/Kolkata', NOW(), NOW()),
('01SEED00000000000000000003', 'maya.beauty@demo.influora.com',
 '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/X4.G2oXeiKPaPaS0q8a', 'CREATOR', 'ACTIVE', TRUE, FALSE, TRUE,
 'Maya Kapoor', 'Maya', 'Kapoor', 'Asia/Kolkata', NOW(), NOW()),
('01SEED00000000000000000004', 'rohit.tech@demo.influora.com',
 '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/X4.G2oXeiKPaPaS0q8a', 'CREATOR', 'ACTIVE', TRUE, FALSE, TRUE,
 'Rohit Verma', 'Rohit', 'Verma', 'Asia/Kolkata', NOW(), NOW()),
('01SEED00000000000000000005', 'neha.food@demo.influora.com',
 '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/X4.G2oXeiKPaPaS0q8a', 'CREATOR', 'ACTIVE', TRUE, FALSE, TRUE,
 'Neha Gupta', 'Neha', 'Gupta', 'Asia/Kolkata', NOW(), NOW());

INSERT INTO creator_profiles (id, user_id, display_name, bio, avatar_url, city, categories, languages,
  rate_min, rate_max, currency, is_verified, is_discoverable, engagement_rate, total_followers, created_at, updated_at)
VALUES
('01SEEDCR00000000000000001', '01SEED00000000000000000001', 'Priya Sharma',
 'Fashion & lifestyle creator from Mumbai.', NULL, 'Mumbai',
 '["fashion","beauty","lifestyle"]', '["hindi","english"]', 35000, 85000, 'INR', TRUE, TRUE, 4.80, 185000, NOW(), NOW()),
('01SEEDCR00000000000000002', '01SEED00000000000000000002', 'Arjun Mehta',
 'Fitness & wellness content.', NULL, 'Delhi',
 '["fitness","health"]', '["hindi","english"]', 25000, 60000, 'INR', TRUE, TRUE, 5.20, 220000, NOW(), NOW()),
('01SEEDCR00000000000000003', '01SEED00000000000000000003', 'Maya Kapoor',
 'Beauty tutorials & skincare reviews.', NULL, 'Bangalore',
 '["beauty","skincare"]', '["english","kannada"]', 40000, 95000, 'INR', FALSE, TRUE, 4.10, 142000, NOW(), NOW()),
('01SEEDCR00000000000000004', '01SEED00000000000000000004', 'Rohit Verma',
 'Tech reviews & unboxing.', NULL, 'Pune',
 '["tech","gadgets"]', '["hindi","english"]', 30000, 75000, 'INR', TRUE, TRUE, 3.90, 98000, NOW(), NOW()),
('01SEEDCR00000000000000005', '01SEED00000000000000000005', 'Neha Gupta',
 'Food & travel reels across India.', NULL, 'Hyderabad',
 '["food","travel"]', '["hindi","telugu"]', 20000, 50000, 'INR', FALSE, TRUE, 6.10, 310000, NOW(), NOW());

INSERT INTO platform_stats (id, creator_profile_id, platform, handle, followers, engagement_rate, is_verified, profile_url, created_at, updated_at)
VALUES
('01SEEDPL00000000000000001', '01SEEDCR00000000000000001', 'INSTAGRAM', '@priya.fashion', 150000, 5.20, TRUE, NULL, NOW(), NOW()),
('01SEEDPL00000000000000002', '01SEEDCR00000000000000001', 'YOUTUBE', 'PriyaStyleDiaries', 35000, 3.80, FALSE, NULL, NOW(), NOW()),
('01SEEDPL00000000000000003', '01SEEDCR00000000000000002', 'INSTAGRAM', '@arjun.fit', 180000, 5.50, TRUE, NULL, NOW(), NOW()),
('01SEEDPL00000000000000004', '01SEEDCR00000000000000002', 'YOUTUBE', 'ArjunFitness', 40000, 4.90, TRUE, NULL, NOW(), NOW()),
('01SEEDPL00000000000000005', '01SEEDCR00000000000000003', 'INSTAGRAM', '@maya.glow', 120000, 4.30, FALSE, NULL, NOW(), NOW()),
('01SEEDPL00000000000000006', '01SEEDCR00000000000000004', 'INSTAGRAM', '@rohit.tech', 75000, 4.00, TRUE, NULL, NOW(), NOW()),
('01SEEDPL00000000000000007', '01SEEDCR00000000000000004', 'YOUTUBE', 'RohitReviews', 23000, 3.50, FALSE, NULL, NOW(), NOW()),
('01SEEDPL00000000000000008', '01SEEDCR00000000000000005', 'INSTAGRAM', '@neha.eats', 280000, 6.20, FALSE, NULL, NOW(), NOW()),
('01SEEDPL00000000000000009', '01SEEDCR00000000000000005', 'YOUTUBE', 'NehaFoodTrail', 30000, 5.80, FALSE, NULL, NOW(), NOW());
