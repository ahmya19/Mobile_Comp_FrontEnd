# Owner actions and field protocols (prepared November 23, 2026)

Plain-language steps for the items only the owner or the group can do. Nothing here has been done for you, and nothing asks for a password or key in chat: secrets go into the Supabase dashboard or the gateway app only.

## 1. Free restore project (Batch 1B): blocked on one confirmation

The Supabase command line cannot show the price of a new project, and the project must not be created without a confirmed $0 quote. The account currently shows one organization with one project (production). **Your step:** open the Supabase dashboard, choose *New project* in the same organization, and look at the price line before confirming. If it says $0/month (Free), tell me; I will create it from the command line with an isolated configuration so the production link is never retargeted. If it asks for any payment or a paid compute add-on, stop and tell me; the restore is then recorded as Blocked and the local replay stays the only proof.

## 2. Public email (Batch 2B): free sender setup

1. Create or open the team's Brevo account (the Free plan allowed 300 emails per day when checked; re-read the limit on their pricing page when you sign in).
2. In Brevo, add and verify the sender address or domain the team controls (Brevo emails you a link; this is the verification only you can do).
3. In Brevo, create SMTP credentials. In the Supabase dashboard open *Authentication > Emails > SMTP settings*, turn on custom SMTP and paste the host, port, username, password and the verified sender. Leave email confirmation switched **on**.
4. Tell me when done. I will then register a test user with an address outside the team, and check the 6-digit code, a wrong code, an expired code, resend and rate limits.

## 3. SMS gateway phone (Batch 2C)

1. Put the SIM in the spare Android phone. Check it can send and receive a normal text and has a load or allowance.
2. Install the gateway app (the candidate is "SMS Gateway for Android") from its official source. Do not bypass Play Protect and do not use your everyday phone.
3. Keep it charged, on the charger and connected to Wi-Fi or data. Tell me when it is ready; I will configure the webhook secret on the server and in the app, and then run labelled test messages (valid, malformed, unknown area, duplicate, bad signature).
4. The server stays fail-closed (rejects everything) until that secret exists. Supported area for now: Alangilan only.

## 4. GPS accuracy field protocol (target: within 20 m)

I cannot move the phone outdoors, so this needs you or a teammate. Do **not** share the coordinates publicly.

- Choose **10 outdoor points** with an unobstructed sky and a defensible reference (for example a surveyed marker, a building corner read from an official parcel map, or a point read from a good reference receiver). Write the reference latitude/longitude privately next to each point.
- At each point: stand within about 1 m of the reference, wait until the phone shows a fix, use the app's "Use my current location" and submit a labelled test report. Note the time, the weather and whether the phone was in a pocket or in hand. Do not edit the pin.
- Give me the point list and I will compute the distance from the reference for each submitted report and report the pass count out of 10 and the worst error. Indoor or covered points are a separate, labelled sample.

## 5. Usability (SUS) protocol (target: score of 70 or more)

Recruit **at least 8 to 10 people who are not on the team** (neighbours, classmates). Each person gets the same neutral tasks, with no coaching:

1. Create an account and confirm it. 2. Report a pothole with a photo at a place of your choice. 3. Find your report and say what its status is. 4. Find a report from someone else on the map and support (vote for) it. 5. Open a notification about your report.

Then give each person the standard 10-question System Usability Scale (statements such as "I think that I would like to use this system frequently", answered 1 to 5, from Strongly disagree to Strongly agree). Scoring: for odd-numbered statements subtract 1 from the answer; for even-numbered statements subtract the answer from 5; add all ten values and multiply by 2.5 to get 0 to 100; the study result is the average of the participants. Record participant count and each anonymous score; report the average and the lowest and highest. Nothing is generated or estimated for you: a result exists only after real people answer.

## 6. Free-plan reminders for the owner

Email is limited per day; the database and photo storage are limited in size; a Free project can pause if it is inactive for a while; the gateway phone needs power, signal and SIM load. None of these is unlimited. Keep a periodic export of the data you must keep.
