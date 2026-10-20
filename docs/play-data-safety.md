# Play Console → Data safety: answers for ReelMint

These follow what the app really does today. Google's own AdMob page ("Provide information for Google Play's Data safety section") lists exactly what its SDK collects; **tick what that page says in addition to the rows below** and re-check it whenever you update the ads SDK.

| Question | Answer |
|---|---|
| Does the app collect or share user data? | Yes (only through Google AdMob) |
| Is all collected data encrypted in transit? | Yes (the ads SDK uses HTTPS) |
| Can users request that data be deleted? | The app collects no account data. Ad data is held by Google (link Google's controls in the policy). |
| Photos and videos | **Not collected.** They stay on the device and are never sent anywhere. |
| Audio (voice recordings) | **Not collected.** Recorded on device only. |
| Files and docs | Not collected. |
| Personal info (name, email, phone…) | Not collected. |
| Location | Not collected by the app; the ads SDK may derive approximate location from the IP address (declare per Google's AdMob page). |
| Device or other IDs | **Collected and shared** (advertising ID) for advertising. |
| App activity / app info and performance | Declare ad interactions and diagnostics as listed on Google's AdMob page. |
| Purpose | Advertising or marketing; app functionality is **not** a purpose for any collected data. |
| Data processed ephemerally? | No (advertising IDs are handled by Google). |
| Required for the app to work? | No, users can decline in the consent form. |

Other Play Console items that go with this:
- **Privacy policy URL:** the public page from `docs/privacy-policy.html` (see README).
- **Ads declaration:** "Yes, my app contains ads."
- **Target audience:** choose 13+ / general audience, not children, to match the policy. Choosing "includes children" would change the ads rules (child-directed treatment, family policy), so decide this deliberately.
- **Permissions:** camera and microphone are requested at the moment of use; explain this in the listing if asked.
