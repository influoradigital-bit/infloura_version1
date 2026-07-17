#!/usr/bin/env bash
# Registers a TEST BRAND account against the local influora-api (dev profile, no OTP required).
#
# Prereqs (all on your Windows machine — NOT the Cowork sandbox):
#   1. MySQL running + Flyway migrations applied
#   2. influora-api running on :8080 with the dev profile:
#        SPRING_PROFILES_ACTIVE=dev  mvn -o -f influora-api spring-boot:run
#      (dev profile sets require-email-otp-before-register=false, so no email OTP needed)
#   3. The BrandSafetyAiClient @Autowired fix in place (so the context boots)
#
# Endpoint: POST /api/v1/auth/brand/register  ->  returns a TokenPair (access + refresh)

BASE_URL="${BASE_URL:-http://localhost:8080/api/v1}"

curl -sS -X POST "${BASE_URL}/auth/brand/register" \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "Swapnil",
    "lastName": "Shinde",
    "email": "shinde111ms@gmail.com",
    "password": "Swapnil111ms$",
    "companyName": "Test Brand",
    "industry": "Technology",
    "companySize": "1-10",
    "acceptedTerms": true
  }'

echo
echo "---"
echo "If you see a TokenPair (accessToken/refreshToken) above, the brand was created."
echo "Log in afterwards with:  POST ${BASE_URL}/auth/brand/login  { email, password }"
