# CEO → CTO — 50 questions on Shopify, WooCommerce and affiliate

**From:** Swapnil Maruti (CEO) · **To:** Priya (CTO) · **Date:** 2026-09-08

I am not asking whether the work was done. I am asking whether a brand who connects
their store today gets attributed sales, and whether a creator who earns commission
gets paid. Answer each question from the code, with a `file:line` you personally
opened. Where the answer is "no" or "cannot tell", say so plainly — I would rather
hear it now than after a customer does.

**Rules for your answers**

1. Every answer cites `file:line`. If you did not open it, do not cite it.
2. If a claim in a comment or doc contradicts the code, the code wins — and tell me
   the doc lied, and where.
3. "It compiles" and "tests pass" are not answers to a question about behaviour.
4. Several of these concern changes made on 2026-09-08. Treat them adversarially. I
   want them broken if they are breakable, not confirmed.
5. Distinguish three states clearly: WORKS (a real store would succeed today),
   WIRED (correct in code, never run against the real third party), ABSENT.

---

## A. Shopify — can a brand actually connect? (1–13)

1. If a brand clicks "Connect Shopify" in production right now, what happens? Trace it end to end and tell me where it stops.
2. Are `influora.shopify.api-key` and `api-secret` bound to any real value in any environment? Which file would set them, and does that file exist?
3. Does `ShopifyProperties.isConfigured()` return true anywhere today? If not, what is the user-visible consequence?
4. `/authorize` now refuses when unconfigured. Verify that the refusal happens BEFORE any OAuth state is minted, and tell me why that ordering matters.
5. Can `/shopify/oauth/callback` be reached and completed while `isConfigured()` is false? Show me the path or show me why not.
6. Where does the authorize URL's `redirect_uri` come from, and does that value point at something that actually exists?
7. Is there a frontend route serving Shopify's post-approval redirect? What did a browser landing there see before 2026-09-08?
8. Does the redirect-uri config value and the registered SPA route agree? What breaks if they drift, and would any test catch it?
9. Does anything in this repo subscribe a connected store to `orders/paid`? Name the mechanism, or confirm none exists.
10. Is there a `shopify.app.toml` manifest anywhere? If not, what is the only remaining way this app can receive a webhook?
11. Where does the webhook delivery address come from? Is it hardcoded, and is the context path included?
12. What happens if webhook registration fails midway through connect — is the brand left connected, disconnected, or something in between?
13. Is the Shopify access token encrypted at rest, and with which key? Is that key distinct from every other secret?

## B. WooCommerce — the one that is live-reachable (14–22)

14. WooCommerce needs no app credentials. So: could a brand connect a WooCommerce store successfully today, right now?
15. What exactly does a brand paste into the connect form, and where do those values come from on their side?
16. Is the webhook Delivery URL shown to brands correct? What was it before, and what did that cost?
17. Does anything verify that the brand actually owns the site URL they claim? What stops me registering a competitor's domain?
18. `site_url` is globally unique. What happens to the real owner of a domain someone else registered first?
19. If a brand disconnects and reconnects the same store, does it work? Trace the unique constraint.
20. If a brand connects a DIFFERENT store URL to a workspace that already has one, what is stored and what is reported back?
21. Is the per-site webhook secret verified before or after the payload is parsed? Show me the ordering.
22. Can a caller distinguish "site not connected" from "bad signature"? What does that leak?

## C. Webhooks, trust and attribution (23–34)

23. For a Shopify delivery, what exactly does the HMAC prove — and what does it NOT prove?
24. Which header selects the workspace a Shopify delivery is attributed to, and is that header covered by the signature?
25. If it is not covered: describe the attack concretely, and tell me whether the 2026-09-08 fix closes it or merely narrows it.
26. That fix calls Shopify's Admin API to confirm the order exists. What happens during a Shopify outage — dropped sale, or retried?
27. Does that Admin API check run on every delivery, or only on ones about to write? What is the cost implication?
28. Has any part of that check ever run against a real Shopify store? If not, what is assumed and where would it break?
29. An order arrives carrying the merchant's OWN discount code (`FREESHIP`), not one of ours. What HTTP status does the platform get, and what does it do next?
30. What did that same case do before 2026-09-08, and how long until the integration would have deleted itself?
31. Is a genuinely transient failure (database down, race) still surfaced as non-2xx so the platform retries? Prove it is not swallowed with everything else.
32. Two brands both create the coupon code `SUMMER20`. Whose sale is attributed, and to whom?
33. Is the coupon lookup workspace-scoped at the QUERY, or filtered after a global fetch? Why does the difference matter in production?
34. Is a delivery replayed twice counted twice? Name the two independent dedup layers and their keys.

## D. Affiliate — does a creator actually get paid? (35–46)

35. Walk me from "a sale is attributed" to "money is in a creator's wallet". Where does that chain stop today?
36. Has any creator ever been credited affiliate commission in production? Answer yes or no, and prove it from the code.
37. What is the settlement schedule, where is it configured, and when does it next run?
38. When a commission settles, which wallet is DEBITED? Name it, and tell me whose money is in it.
39. Is the brand that owes the commission debited anywhere in the flow? Show me the posting or confirm its absence.
40. `wallet_transactions.type` is an ENUM. Does it contain `AFFILIATE_COMMISSION`? Does any migration add it?
41. If it does not: what happens when the settlement job runs — and what has it been doing every month since it shipped?
42. So do these two defects cancel out? Explain precisely what would happen if someone "fixed" only the enum.
43. Is there a gate preventing exactly that? Find it, read it, and tell me whether it would actually fire.
44. Could a brand fabricate orders into its own workspace? What would it cost them, and what would it cost us?
45. Is a payout from a creator wallet automatic or gated? What stands between a fabricated commission and real money leaving?
46. Does any document define who funds affiliate commission — brand wallet, escrow hold, or invoice? Cite it or confirm the gap.

## E. Adversarial — break what was built on 2026-09-08 (47–50)

47. Five gates were added under `.proof-os/gates/`. For each, name one realistic change that would break the thing it guards while the gate still reports PROVED.
48. The cross-tenant tests changed from `assertThrows` to asserting HTTP 200. Was that a genuine fix or a weakened test? Decide, and justify from what the tests still assert.
49. Commit `f3a30c5` carried new classes and gates but not the call sites using them. Is HEAD internally consistent now? Verify, do not assume.
50. Of everything claimed closed this session — F-0725, F-0726, F-0728, F-0730, F-0731, F-0732 — which would you personally refuse to call fixed, and what would you require before shipping it?

---

**Deliver as:** a numbered list, one answer per question, each with its `file:line`
and a WORKS / WIRED / ABSENT verdict where the question asks for one. End with the
three things you would fix first, in order.
