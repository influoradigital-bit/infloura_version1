# Where each festival date in `events.jsonl` came from

A variable-date festival (Diwali, Holi, Eid, Navratri and the rest) follows the moon, so its date
has to be **looked up and written down per year**, never worked out from memory by a person or a
model. A row with no date for the year in question is skipped: the creator gets an evergreen idea
for that day instead, and `plan_my_week` logs the gap. A wrong Diwali date in front of a creator
is worse than no calendar at all.

## Filled on 2026-09-23

| Festival | Year | Date | Source |
|---|---|---|---|
| Dussehra | 2026 | 20 Oct 2026 | Indian Embassy "List of Holidays for the Year 2026" (gazetted), cross-checked against two festival calendars |
| Diwali | 2026 | 8 Nov 2026 | same list, cross-checked |
| Guru Nanak Jayanti | 2026 | 24 Nov 2026 | same list |
| Dhanteras | 2026 | 6 Nov 2026 | not in the gazette; two independent festival calendars agree, and it sits two days before the gazetted Diwali |
| Bhai Dooj | 2026 | 10 Nov 2026 | as above, two days after the gazetted Diwali |
| Id-ul-Fitr (Eid al-Fitr) | 2027 | 10 Mar 2027 | DoPT Office Memorandum, list of holidays for 2027, dated 16 July 2026 |
| Holi | 2027 | 23 Mar 2027 | same O.M. |
| Ram Navami | 2027 | 15 Apr 2027 | same O.M. |
| Id-ul-Zuha (Bakrid) | 2027 | 17 May 2027 | same O.M. |
| Janmashtami | 2027 | 25 Aug 2027 | same O.M. |
| Dussehra | 2027 | 9 Oct 2027 | same O.M. |
| Diwali | 2027 | 29 Oct 2027 | same O.M. |
| Guru Nanak Jayanti | 2027 | 14 Nov 2027 | same O.M. |

Both Eid dates move with the moon sighting and the government itself prints them as provisional.
If the announced day differs, change the row here — the calendar reads the file, nothing is cached.

## Filled on 2026-09-24

No government gazette covers these (they aren't public holidays), so each was checked against two
independent Panchang/festival-date sources that agreed.

| Festival | Year | Date | Source |
|---|---|---|---|
| Navratri and Durga Puja (Sharad Navratri start) | 2026 | 11 Oct 2026 | drikpanchang.com Navratri date page (Ghatasthapana, New Delhi) and shubhdivas.in "Sharad Navratri 2026", cross-checked; also lands 9 days before the already-verified 20 Oct 2026 Dussehra |
| Karva Chauth | 2026 | 29 Oct 2026 | drikpanchang.com Karwa Chauth date page (Delhi) and en.wikipedia.org "Karva Chauth" (2026 date), cross-checked; 10 days before the gazetted 8 Nov 2026 Diwali |
| Chhath Puja (main day, Sandhya Arghya) | 2026 | 15 Nov 2026 | samvat.in "Chhath Puja 2026" and hindulab.com "Chhath Puja 2026", cross-checked; 7 days after the gazetted 8 Nov 2026 Diwali |
| Maha Shivratri | 2027 | 6 Mar 2027 | drikpanchang.com Maha Shivaratri date page (New Delhi) and nationaltoday.com "Maha Shivaratri", cross-checked |
| Gudi Padwa, Ugadi and Vishu | 2027 | 7 Apr 2027 | drikpanchang.com Gudi Padwa date page (Delhi) and samvat.in "Ugadi/Gudi Padwa 2027", cross-checked |
| Baisakhi | 2027 | 14 Apr 2027 | drikpanchang.com Vaisakhi date page (New Delhi) and nationaltoday.com "Vaisakhi/Baisakhi/Vishu", cross-checked |
| Raksha Bandhan | 2027 | 17 Aug 2027 | drikpanchang.com Raksha Bandhan date page (Delhi) and samvat.in "Raksha Bandhan 2027", cross-checked |
| Ganesh Chaturthi | 2027 | 4 Sep 2027 | drikpanchang.com Ganesh Chaturthi date page (Delhi) and samvat.in "Ganesh Chaturthi 2027", cross-checked |
| Onam (Thiruvonam) | 2027 | 12 Sep 2027 | drikpanchang.com Onam/Thiruvonam date page (Thiruvananthapuram) and prokerala.com "Onam", cross-checked |
| Dhanteras | 2027 | 27 Oct 2027 | drikpanchang.com Dhanteras date page (Delhi) and mpanchang.com "Dhanteras 2027", cross-checked; 2 days before the already-verified 29 Oct 2027 Diwali |
| Bhai Dooj | 2027 | 31 Oct 2027 | drikpanchang.com Bhai Dooj date page (New Delhi) and samvat.in "Bhai Dooj 2027", cross-checked; 2 days after the already-verified 29 Oct 2027 Diwali |

## Still empty, and why

No official list states these, so nobody has written them down yet. They stay unserved until
somebody looks them up for the year and fills them in:

Makar Sankranti and Pongal

Makar Sankranti 2027 specifically was checked and left out on purpose: drikpanchang.com,
divinehindu.in and nationaltoday.com all give 15 Jan 2027 (the Sankranti moment falls at 9:14 PM
on 14 Jan, after sunset, so punya kaal carries to the next sunrise), while calendardate.com and
samvat.in give 14 Jan 2027 (the plain solar-transition day, without the after-sunset carry-over
rule applied). That is a real disagreement between reputable sources, not a typo, so the row
stays empty rather than guessing which convention `plan_my_week` should follow. Whoever fills
this in next should pick a convention explicitly (and say so in this table) rather than trusting
either side's "usual" date.

## How to add one

1. Find the date in a source you can name — the DoPT holiday list for the year, a state government
   calendar, or two independent festival calendars that agree.
2. Add it to that row's `year_dates` in `events.jsonl`, keyed by year: `"2027": "2027-11-05"`.
3. Add a row to the table above with the source.
4. Run `python -m pytest tests/planner` — the loader validates every date at import, so a typo
   fails there rather than in front of a creator.

To see what is missing for the weeks ahead, run the AI service and read the
`week_plan: N festival(s) have no verified date for this window` warning, or run
`python -m pytest tests/planner/test_event_dates_present.py -q`, which lists them.
