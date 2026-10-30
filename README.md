# TourCompanion

Android travel companion (Semmozhi). Works **fully offline** out of the box using built-in travel tips.

## Run the app
1. Open the project in Android Studio, **Sync Project with Gradle Files**.
2. Run on a device or emulator.

## Optional: AI answers for questions the built-in tips cannot answer
The app never contains an API key. Instead it calls a small backend you run yourself.

1. Create a **new** API key in the Claude Platform console (revoke any key that was ever pasted in code or chat).
2. Run the proxy in `backend/`:
   ```
   cd backend
   $env:ANTHROPIC_API_KEY="your-new-key"     # PowerShell
   node server.js
   ```
3. Deploy it somewhere with **HTTPS** (a cloud function / small host) and put its address in
   `app/src/main/java/com/example/tourcompanion/AppConfig.kt`:
   `const val AI_PROXY_URL = "https://your-host.example.com/ask"`
4. Leave `AI_PROXY_URL` empty to stay offline.

## Safety notes
- Emergency button opens the dialer with 112; the user presses call.
- "Tell someone I'm safe" opens the share sheet; nothing is sent automatically.

## The 25 screens (Home has 4 bottom tabs: Home, Explore, Safety, Profile)
1 Splash - 2 Home - 3 Ask Semmozhi - 4 Safety Check-in - 5 Emergency (112) - 6 Voice Check - 7 Helplines -
8 My Emergency Contact - 9 Useful Phrases - 10 Tamil phrases - 11 Hindi phrases - 12 Travel Tips (topics) -
13-21 nine tip topics (Transport, Money & Payments, Food & Water, Hotels & Stay, Health & Weather, Safety & Planning,
Scams & Fraud, Culture & Customs, Phone/Internet/Documents) - 22 Packing Checklist - 23 Budget Calculator -
24 Settings - 25 About.
Screens 10-11 share one class (PhrasesListActivity) and 13-21 share another (TipsCategoryActivity).

## Teaching Semmozhi more
Everything she knows is in `app/src/main/res/raw/`:
- `tips.json` - travel tips (topic, comma-separated keywords, one or more answers). Keywords may be in Tamil/Hindi script.
- `phrases.json` - phrase books. `packing.json` - packing checklist.
After editing `tips.json`, raise `DATABASE_VERSION` in `TravelDatabaseHelper.kt` so phones rebuild the table.
Small talk (hello, thanks, who are you...) is in `SmallTalk.kt`; distress words are in `TipMatcher.kt`.

## Tests
`app/src/test/.../TipMatcherTest.kt` - run it from Android Studio (no phone needed).

## Accounts
- The login screen (Create account / Log in / Continue as guest) appears on first launch and after logging out.
- Accounts live in an on-device SQLite database (`users.db`); passwords are stored only as salted PBKDF2 hashes.
- The emergency contact and packing ticks are saved per user. Deleting an account (Profile tab, password required)
  removes the account and all data saved for it. Emergency 112, helplines and tips work without an account.
- This database is local to the phone. Sign-in across phones needs a cloud backend (for example Firebase Authentication
  + Firestore), which needs your own project's `google-services.json`.

## Design
Teal + sunset-amber palette (`res/values/colors.xml`), light theme, gradient headers (`res/drawable/bg_hero.xml`,
`bg_header*.xml`), rounded cards, colourful icon circles, Material button styles (`res/values/styles.xml`).
Change the brand colour in one place: `colors.xml` (`primary`, `primary_dark`, `primary_light`) and the three gradient
files. `EdgeToEdge.kt` keeps headers and the tab bar clear of the status bar and gesture bar on every screen.
