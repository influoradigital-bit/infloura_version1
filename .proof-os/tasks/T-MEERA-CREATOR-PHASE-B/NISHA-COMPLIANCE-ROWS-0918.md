# Nisha — Compliance Message Rows (blind pass)

Context: these are messages a real Indian brand or agency might send a creator
while **following** ASCI ad-disclosure rules — i.e. instructing the creator to
ADD the paid-partnership label / use #ad / not post without the disclosure.
None of these are "hide the ad" instructions. Every row's expected detector
outcome is NO_FLAG (a disclosure-hiding rule must not fire on any of these).

Written without opening HideDisclosureRule.java, RiskText.java,
RULINGS-U-0917.md, any KABIR-*/PRIYA-* file, or any *Probe* file — pure
domain judgment on how Indian brand/agency people actually write these asks.

| id | lang | text | expected | why |
|----|------|------|----------|-----|
| NC-01 | English | "Please do not post without the paid partnership label." | NO_FLAG | Brand is mandating disclosure, not hiding it — negative phrasing around a required label. |
| NC-02 | English | "Kindly add #ad at the start of the caption before you publish." | NO_FLAG | Direct positive instruction to add the disclosure tag. |
| NC-03 | Hinglish | "Bina #ad ke post mat karna, caption ke sabse upar add karna hai." | NO_FLAG | Hinglish negative phrasing — "don't post without #ad" — is a compliance instruction. |
| NC-04 | Hindi (Devanagari) | "कृपया #ad के बिना पोस्ट मत करना, पेड पार्टनरशिप लेबल ज़रूर लगाएं।" | NO_FLAG | Hindi equivalent of "don't post without #ad" — enforcing disclosure. |
| NC-05 | English | "No posting without #ad — this is mandatory as per ASCI guidelines." | NO_FLAG | Explicitly cites ASCI as the reason for requiring the tag. |
| NC-06 | Hinglish | "Caption ke start mein #ad ya #sponsored zaroor daal dena, ye zaroori hai." | NO_FLAG | Positive Hinglish ask to add disclosure hashtag. |
| NC-07 | English (formal agency email) | "Dear Creator, as per our influencer agreement and in compliance with ASCI's guidelines on celebrity/influencer endorsements, all deliverables under this campaign must carry a clearly visible '#ad' or 'Paid Partnership' disclosure within the first three lines of the caption, and the Instagram 'Paid Partnership' label must be enabled before the post goes live. Kindly do not publish any content without this disclosure. Please share a screenshot of the caption for our review prior to posting." | NO_FLAG | Long formal legal/agency wording still instructs adding disclosure, never hiding it. |
| NC-08 | Hindi (Devanagari) | "कृपया कैप्शन की शुरुआत में #ad ज़रूर लगाएं।" | NO_FLAG | Simple positive Hindi instruction to add #ad. |
| NC-09 | Hinglish | "Don't forget the ad tag yaar, bina tag ke client flag kar dega." | NO_FLAG | Casual Hinglish reminder not to forget the disclosure tag. |
| NC-10 | English | "Use the Instagram 'Paid Partnership' tag before publishing, not just #ad in the caption." | NO_FLAG | Asks for the platform-native disclosure tool in addition to the caption tag — stricter compliance, not evasion. |
| NC-11 | Hindi (Devanagari) | "बिना #ad के पोस्ट मत करना, यह ASCI नियमों के तहत ज़रूरी है।" | NO_FLAG | Hindi negative phrasing citing ASCI requirement explicitly. |
| NC-12 | English (formal agency email) | "This is a reminder from the brand compliance team: all sponsored content must not be posted without the '#ad' disclosure clearly visible in the caption, and the paid partnership toggle must be switched on in the app. Non-compliant posts will need to be taken down and reposted with the correct disclosure." | NO_FLAG | Formal compliance-team memo enforcing disclosure; mentions takedown only for missing disclosure, not for hiding it. |
| NC-13 | Hinglish | "Please make sure #ad ya #collab hashtag caption mein add ho, warna ASCI compliance issue ho jaayega." | NO_FLAG | Positive ask with explicit ASCI compliance rationale. |
| NC-14 | English | "Reminder: don't post without the disclosure tag — this is non-negotiable for brand safety and ASCI compliance." | NO_FLAG | WhatsApp-style brief reminder enforcing, not hiding, disclosure. |

Written blind by Nisha, 2026-09-18

---

## Short-form check (not blind)

Read for this section: `RULINGS-U-0917.md` Round 7 (Ruling 3, "Nisha's natural rows —
accept the mechanical guards") and my own earlier file `NISHA-HIDE-WORD-ROWS-0918.md`.

Priya's question for each terse form the HIDE_DISCLOSURE short-form branches still
guard mechanically: does a brand manager actually send this exact terse form (subject,
negator and verb tight together), perhaps with a few more words around it — not the
longer natural sentence I already wrote in the guard-rows file? A "no" prunes that
alternative and its guard row in the same commit.

| word | terse form | send it? | why |
|---|---|---|---|
| sponsored (Hinglish) | "sponsored mat likhna" | **Yes** | This is exactly how a quick WhatsApp correction reads — e.g. "sponsored mat likhna yaar, brand ko clean chahiye." Brand managers do type the bare word plus "mat likhna" with no filler when they're in a hurry. |
| nahi (Hinglish) | "ad nahi likhna" | **Yes** | Same terse-correction pattern — "Ad nahi likhna caption mein, warna dikh jayega." Natural as a short chat line. |
| na (Hinglish) | "ad na likhna" | **No** | In my own guard row for "na" (NISHA-HIDE-WORD-ROWS-0918.md), the natural hide-ad sentence used "na" only as a trailing soft-request tag on a different verb ("rakhna na… mat likhna"), never as a negator sitting directly in front of the disclosure verb. A brand manager reaching for "na" to negate "likhna" mid-sentence isn't how the word gets used here — "mat" or "nahi" carries that job. Prune it. |
| laga (Hinglish) | "#ad mat laga" | **Yes** | Ordinary terse instruction — "#ad mat laga is baar." Matches how "lagana" gets used casually for hashtags/tags in chat. |
| mention (Hinglish) | "paid partnership mat mention karna" | **Yes** | "Paid partnership" is the literal Instagram feature name, so it gets dropped into Hinglish verbatim; "mat mention karna" is a completely ordinary verb pairing for it in agency chat. |
| sponsored (Devanagari, स्पॉन्सर्ड) | "sponsored मत लिखना" | **Yes** | Devanagari equivalent of the Hinglish form above; brand managers who type in Hindi script use the same terse correction shape. |
| नहीं (Devanagari) | "#ad नहीं डालना" | **Yes** | Realistic — English hashtags mixed into a Devanagari sentence is normal texting/typing behavior in India, and the terse "X नहीं डालना" shape is a completely ordinary instruction. |

**Net:** 6 of 7 terse forms are real; prune "ad na likhna" (the "na" branch's terse,
direct-negator shape) and its guard row.

— Nisha, 2026-09-18
