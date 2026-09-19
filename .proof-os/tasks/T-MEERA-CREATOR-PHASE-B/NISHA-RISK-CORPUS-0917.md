# Risk Flag Test Corpus — OFF_PLATFORM_PAYMENT & HIDE_DISCLOSURE

**Author:** Nisha Patel, Content Lead
**Date:** 2026-09-17
**Method:** Every row below was written before opening `OffPlatformPaymentRule.java`, `HideDisclosureRule.java`, `RiskText.java`, `KABIR-CONSENT-0917.md`, `RULINGS-U-0917.md` (round 4), or any file with "RegexProbe" in its name. I did not look at detection logic, patterns, or prior rulings while drafting this. Per Priya's ruling, this corpus exists so the flags can be checked against wording nobody tuned the patterns to.

Lines are written the way a brand manager in India actually types on WhatsApp/email/DM — including realistic typos, spacing slips, and code-switching. Nothing here is copied from a real brand or creator; names like "Casino Creatives," "TECNO," and "Sponsor Bikes" are invented specifically to share vocabulary with a violation without being one.

---

## Flag 1: OFF_PLATFORM_PAYMENT

### SHOULD FLAG (14 rows)

| id | language | text | expected | why | blind |
|---|---|---|---|---|---|
| OPP-F-01 | en | "Hi, can we just pay you directly via UPI instead of routing it through the platform? Saves us both the commission." | FLAG | Direct ask to move payment off-platform via UPI, framed around avoiding fees. | yes |
| OPP-F-02 | en | "We'd rather not use Influora's payment system for this one — send us your bank account number and IFSC and we'll transfer today." | FLAG | Explicit request for bank details to pay outside the platform. | yes |
| OPP-F-03 | en | "Let's settle this outside the app, it's quicker and there's no cut for anyone in between." | FLAG | Indirect off-platform payment request framed as convenience/cost-saving. | yes |
| OPP-F-04 | hinglish | "Aap apna UPI ID bhej do, hum side mein hi payment kar denge, Influora ke through jaane ki zarurat nahi." | FLAG | Direct off-platform UPI request in Hinglish. | yes |
| OPP-F-05 | hinglish | "Bhai Influora se karne ka jhanjhat mat rakho, seedha tumhare account mein daal denge paisa, aaj hi ho jayega." | FLAG | Pushy off-platform payment push, platform framed as "jhanjhat" (hassle). | yes |
| OPP-F-06 | hinglish | "plz sedha bank transfr kr denge no need platfrom , fast ho jayega" | FLAG | Typo-laden but unambiguous off-platform bank transfer request. | yes |
| OPP-F-07 | hi | "आप अपना UPI नंबर भेज दीजिए, हम सीधे पेमेंट कर देंगे, प्लेटफॉर्म की कोई ज़रूरत नहीं है।" | FLAG | Direct Hindi request to bypass the platform via UPI. | yes |
| OPP-F-08 | hi | "अगर आपको एतराज़ न हो तो हम भुगतान सीधे आपके बैंक खाते में कर दें, इससे प्लेटफॉर्म का कमीशन बच जाएगा।" | FLAG | Polite Hindi framing that is still an off-platform payment ask. | yes |
| OPP-F-09 | en | "We really enjoy working with you, and just to save time on both ends — would you be okay receiving payment directly to your account rather than through the Influora escrow flow this time?" | FLAG | Long, polite wording that still requests bypassing escrow. | yes |
| OPP-F-10 | en | "Cash on delivery of the content. No Influora." | FLAG | Short, pushy, explicit rejection of platform payment. | yes |
| OPP-F-11 | hinglish | "upi   pe   direct   bhej   denge   , platform  skip  karte hain  isbaar" | FLAG | Odd spacing but clearly proposes skipping the platform. | yes |
| OPP-F-12 | en | "Let's keep this transaction between us, it'll be quicker that way and no paperwork." | FLAG | Euphemistic "between us" implies an off-platform payment. | yes |
| OPP-F-13 | hi | "पेमेंट का मामला हम आपस में सुलझा लेंगे, प्लेटफॉर्म को बीच में लाने की कोई ज़रूरत नहीं।" | FLAG | Indirect Hindi phrasing — handling payment privately, outside the platform. | yes |
| OPP-F-14 | hinglish | "Invoice platform pe mat banao yaar, hum WhatsApp pe hi paisa bhej denge seedha." | FLAG | Explicit avoidance of platform invoicing in favor of direct WhatsApp/transfer. | yes |

### SHOULD NOT FLAG (14 rows)

| id | language | text | expected | why | blind |
|---|---|---|---|---|---|
| OPP-N-01 | en | "Payment will go out after delivery through Influora, as usual — nothing's changed on our end." | NO_FLAG | Explicitly confirms payment stays on-platform. | yes |
| OPP-N-02 | en | "Please add your UPI ID in your Influora payout settings so the finance team can release the funds correctly." | NO_FLAG | UPI mentioned only to configure the platform's own payout settings. | yes |
| OPP-N-03 | en | "Casino will process your payment once the deliverables are approved via the platform, same as last campaign." | NO_FLAG | "Casino" is the brand's name (Casino Creatives), not gambling; payment stays on-platform. | yes |
| OPP-N-04 | en | "TECNO's payment should reflect in your Influora wallet within 48 hours of approval." | NO_FLAG | TECNO is a phone brand name; payment explicitly via the Influora wallet. | yes |
| OPP-N-05 | en | "No issues with the payment structure, we're fine going through escrow as always." | NO_FLAG | Explicit endorsement of the existing on-platform escrow process. | yes |
| OPP-N-06 | en | "Please share your bank account details in the Influora KYC section so payouts can be verified on your end." | NO_FLAG | Bank details requested for the platform's own KYC/payout verification, not to bypass it. | yes |
| OPP-N-07 | hinglish | "Payment Influora ke through hi karenge, jaisa contract mein likha hai, koi change nahi hai." | NO_FLAG | Hinglish confirmation that payment stays on-platform per contract. | yes |
| OPP-N-08 | hi | "भुगतान हमेशा की तरह प्लेटफॉर्म के माध्यम से ही होगा, कोई बदलाव नहीं है।" | NO_FLAG | Hindi statement confirming payment remains through the platform. | yes |
| OPP-N-09 | en | "Can you confirm your UPI-linked bank account works fine for Influora withdrawals?" | NO_FLAG | Asks about withdrawal mechanics within the platform, not routing payment outside it. | yes |
| OPP-N-10 | en | "We've sent the brief fee to Influora's escrow — you should see it credited to your wallet soon." | NO_FLAG | Money moving into Influora's escrow, not out of it. | yes |
| OPP-N-11 | hinglish | "Bank details Influora dashboard mein update kar dijiye, payout ke liye chahiye hoga." | NO_FLAG | Bank detail update requested inside the platform's own dashboard for payout purposes. | yes |
| OPP-N-12 | en | "DM me directly if the Influora payment status doesn't update within 3 days." | NO_FLAG | "Directly" refers to messaging the sender, not routing payment outside the platform. | yes |
| OPP-N-13 | en | "Our finance team processes payments strictly through Influora's system — please don't share personal bank info outside it." | NO_FLAG | Explicitly warns against sharing bank info outside the platform. | yes |
| OPP-N-14 | hi | "कृपया अपने Influora खाते में सही बैंक विवरण डालें ताकि भुगतान समय पर मिल सके।" | NO_FLAG | Hindi request to correct bank details inside the Influora account for timely payment. | yes |

---

## Flag 2: HIDE_DISCLOSURE

### SHOULD FLAG (14 rows)

| id | language | text | expected | why | blind |
|---|---|---|---|---|---|
| HD-F-01 | en | "Please don't mention anywhere that this is a paid collaboration, keep the caption looking fully organic." | FLAG | Direct instruction to hide the paid nature of the post. | yes |
| HD-F-02 | en | "No #ad tag please, we really don't want this looking sponsored to our audience." | FLAG | Explicit instruction to omit the ad label. | yes |
| HD-F-03 | en | "Let's keep this feeling natural — maybe skip the paid partnership label just this once?" | FLAG | Polite but still requests skipping the disclosure label. | yes |
| HD-F-04 | hinglish | "Caption mein paid wala tag mat lagana, bilkul organic jaisa lagna chahiye." | FLAG | Hinglish instruction to avoid the paid tag. | yes |
| HD-F-05 | hinglish | "Bhai #ad bilkul mat daalna, sponsor wali baat chhupa lo is baar." | FLAG | Pushy Hinglish instruction to hide the sponsorship. | yes |
| HD-F-06 | hinglish | "plz na likhna sponsord , orgnic dikhna chahiye pura post" | FLAG | Typo-heavy but clearly asks to not label the post as sponsored. | yes |
| HD-F-07 | hi | "कृपया यह मत बताइए कि यह पेड पोस्ट है, इसे बिल्कुल ऑर्गेनिक दिखाएं।" | FLAG | Direct Hindi instruction to conceal that the post is paid. | yes |
| HD-F-08 | hi | "अगर हो सके तो पेड पार्टनरशिप लेबल हटा दीजिए, हमें यह प्रायोजित नहीं दिखना चाहिए।" | FLAG | Polite Hindi request to remove the paid partnership label. | yes |
| HD-F-09 | en | "We'd prefer the audience doesn't realize this is a brand deal — could you post without any sponsored or paid partnership tag so it reads like a genuine recommendation?" | FLAG | Long, detailed request to disguise the paid post as organic. | yes |
| HD-F-10 | en | "No ad label. Non-negotiable." | FLAG | Short, pushy instruction against disclosure. | yes |
| HD-F-11 | hinglish | "sponsered  wala  tag  hata do , bas normal post jaisa hi rakhna" | FLAG | Odd spacing but a clear instruction to remove the sponsored tag. | yes |
| HD-F-12 | en | "Let's keep this off the record as far as the sponsorship goes." | FLAG | Euphemistic phrasing requesting hidden sponsorship. | yes |
| HD-F-13 | hi | "इसे एक सामान्य पोस्ट की तरह ही रखिए, प्रायोजित शब्द का इस्तेमाल बिल्कुल मत कीजिए।" | FLAG | Hindi instruction to avoid the word "sponsored" entirely. | yes |
| HD-F-14 | hinglish | "Disclosure ki koi zaroorat nahi hai, ye toh bas ek genuine recommendation jaisa lagna chahiye." | FLAG | Hinglish dismissal of the disclosure requirement. | yes |

### SHOULD NOT FLAG (14 rows)

| id | language | text | expected | why | blind |
|---|---|---|---|---|---|
| HD-N-01 | en | "Please make sure you add the #ad tag as required — we always want to stay compliant with ASCI guidelines." | NO_FLAG | Explicitly asks FOR the disclosure label. | yes |
| HD-N-02 | en | "No issues with disclosure on our end at all, go ahead and mark it as a paid partnership like always." | NO_FLAG | Explicit approval of disclosure. | yes |
| HD-N-03 | en | "Please don't disclose the launch date until the 15th, it's still confidential internally." | NO_FLAG | Confidentiality about a launch date, not about hiding that a post is an ad. | yes |
| HD-N-04 | en | "Sponsor Bikes wants the paid partnership label included as usual, per the contract." | NO_FLAG | "Sponsor" is part of the brand's name (Sponsor Bikes); message asks for the label to be included. | yes |
| HD-N-05 | en | "Don't share the campaign budget numbers with anyone outside our team." | NO_FLAG | Confidentiality about budget figures, unrelated to disclosure labels. | yes |
| HD-N-06 | hinglish | "Disclosure zaroor lagana, ASCI rule follow karna hai humesha, koi chhoot nahi hai." | NO_FLAG | Hinglish instruction requiring disclosure compliance. | yes |
| HD-N-07 | hi | "कृपया पोस्ट में #ad ज़रूर लिखें, यह हमारे लिए ज़रूरी है।" | NO_FLAG | Hindi request explicitly requiring the ad tag. | yes |
| HD-N-08 | en | "Please keep the collaboration details confidential until launch day, but the paid partnership tag should still be there as usual." | NO_FLAG | Confidentiality about timing combined with an explicit disclosure requirement. | yes |
| HD-N-09 | en | "Don't reveal our unreleased product specs in the video, just show the final look — the paid tag should still be visible though." | NO_FLAG | Confidentiality about product specs; disclosure tag explicitly kept. | yes |
| HD-N-10 | hi | "लॉन्च की तारीख अभी किसी को मत बताइए, यह गुप्त रखनी है।" | NO_FLAG | Hindi confidentiality instruction about a launch date, not ad disclosure. | yes |
| HD-N-11 | hinglish | "Sponsorship tag daalna mat bhoolna, compliance ke liye zaroori hai humesha." | NO_FLAG | Hinglish reminder to include the sponsorship tag. | yes |
| HD-N-12 | en | "We're fine with full transparency — mention it's a sponsored post, no problem at all." | NO_FLAG | Explicit comfort with disclosure. | yes |
| HD-N-13 | en | "Please don't disclose our internal pricing strategy to competitors, but yes, tag it as paid partnership as always." | NO_FLAG | Confidentiality about pricing strategy; disclosure tag explicitly requested. | yes |
| HD-N-14 | hi | "पेड पार्टनरशिप का टैग लगाना बिल्कुल मत भूलें, यह अनिवार्य है।" | NO_FLAG | Hindi instruction insisting on the paid partnership tag. | yes |

---

## Row counts

| Flag | English | Hinglish | Hindi | Total |
|---|---|---|---|---|
| OFF_PLATFORM_PAYMENT — SHOULD FLAG | 6 | 5 | 3 | 14 |
| OFF_PLATFORM_PAYMENT — SHOULD NOT FLAG | 10 | 2 | 2 | 14 |
| OFF_PLATFORM_PAYMENT total | 16 | 7 | 5 | 28 |
| HIDE_DISCLOSURE — SHOULD FLAG | 6 | 5 | 3 | 14 |
| HIDE_DISCLOSURE — SHOULD NOT FLAG | 9 | 2 | 3 | 14 |
| HIDE_DISCLOSURE total | 15 | 7 | 6 | 28 |
| **Grand total** | **31** | **14** | **11** | **56** |

Blind rows: 56 / 56 (all rows — I did not open any of the excluded files before or during drafting).

---

**Written blind by Nisha, 2026-09-17.** 56 rows total: 28 for OFF_PLATFORM_PAYMENT (16 English, 7 Hinglish, 5 Hindi), 28 for HIDE_DISCLOSURE (15 English, 7 Hinglish, 6 Hindi). All 56 rows written blind, before opening `OffPlatformPaymentRule.java`, `HideDisclosureRule.java`, `RiskText.java`, `KABIR-CONSENT-0917.md`, `RULINGS-U-0917.md` round 4, or any `RegexProbe` file. Over to Vikram to turn this into a Java test table; Kavya and Kabir to check it.
