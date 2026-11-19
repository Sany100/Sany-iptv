# SANY IPTV Admin Dashboard — সেটআপ গাইড

## Dashboard কী দেখাবে

- GitHub Releases থেকে Mobile APK ও Android TV APK-এর মোট asset download count (সব প্রকাশিত release মিলিয়ে)।
- Firebase Analytics/GA4 থেকে Active users, New users এবং `first_open` event।
- 7, 28 অথবা 90 দিনের Analytics রিপোর্ট।

**গুরুত্বপূর্ণ:** GitHub download count অনন্য ব্যক্তি বা ইনস্টল সংখ্যা নয়। Firebase-এর `first_open` হলো অ্যাপ প্রথমবার চালুর event; এটি যাচাইকৃত device-install count নয়।

বর্তমান Android Gradle configuration-এ Firebase Analytics শুধু Mobile flavor-এ যুক্ত এবং Firebase config-এ Mobile package নিবন্ধিত। তাই প্রথম সেটআপে Analytics রিপোর্ট Mobile app-এর হবে। TV app-এর জন্য Firebase-এ আলাদা Android app (`com.sany.iptv.tv`) নিবন্ধন ও আলাদা Google Services config যোগ করতে হবে।

## ধাপ ১ — Render-এ Dashboard deploy

1. Render-এ লগইন করুন: https://dashboard.render.com/
2. **New +** চাপুন, তারপর **Blueprint** নির্বাচন করুন।
3. GitHub account connect করে `Sany100/Sany-iptv` repository নির্বাচন করুন।
4. Render root-এর `render.yaml` পড়ে `sany-iptv-admin` Web Service তৈরি করবে।
5. Environment setup-এ `ADMIN_TOKEN`-এর জন্য একটি লম্বা, অনুমান করা কঠিন secret দিন। এটি কাউকে পাঠাবেন না।
6. আপাতত `GA4_PROPERTY_ID` এবং `GOOGLE_SERVICE_ACCOUNT_JSON` খালি রাখলেও Dashboard deploy হবে; তখন GitHub download count দেখা যাবে।
7. Deploy শেষ হলে Render যে `https://....onrender.com` URL দেবে সেটি খুলুন এবং `ADMIN_TOKEN` দিয়ে প্রবেশ করুন।

## ধাপ ২ — Firebase / GA4 Analytics যুক্ত করা

এই অংশটি ঐচ্ছিক; এটি না করলেও GitHub download count কাজ করবে।

1. Firebase Console খুলুন: https://console.firebase.google.com/
2. SANY IPTV-এর Firebase project খুলে **Analytics** সক্রিয় আছে কি না নিশ্চিত করুন এবং Google Analytics property link করা আছে কি না দেখুন।
3. Google Analytics খুলুন: https://analytics.google.com/
4. **Admin → Property details** থেকে numeric **Property ID** কপি করুন। Measurement ID (G-...) বা Firebase App ID এখানে দেবেন না।
5. Google Cloud Console-এ একই project নির্বাচন করে **Google Analytics Data API** Enable করুন।
6. **IAM & Admin → Service Accounts** থেকে একটি service account তৈরি করুন এবং JSON key তৈরি করুন।
7. Google Analytics → **Admin → Property access management**-এ service account-এর email যোগ করে অন্তত **Viewer** access দিন।
8. Render → `sany-iptv-admin` → **Environment**-এ:
   - `GA4_PROPERTY_ID` = numeric GA4 Property ID
   - `GOOGLE_SERVICE_ACCOUNT_JSON` = JSON key ফাইলের সম্পূর্ণ content
9. Save Changes / Redeploy করুন। Dashboard-এ Analytics status **Connected** দেখালে কাজ হয়েছে।

## নিরাপত্তা

- Service account JSON কখনো GitHub repository, Android APK, বা public website-এ রাখবেন না।
- `ADMIN_TOKEN` কাউকে দেবেন না। Dashboard API প্রতিটি data request-এ এই token যাচাই করে।
- Token হারালে Render Environment-এ নতুন token সেট করে redeploy করুন।
- এই Dashboard কোনো ব্যক্তির নাম, ইমেইল বা ডিভাইস-লেভেল তথ্য দেখায় না; কেবল aggregate Analytics metrics দেখায়।

## সমস্যা হলে

- **GitHub data unavailable:** Render service-এর log দেখুন; GitHub public API সাময়িক rate limit দিতে পারে।
- **GA4 report could not be loaded:** Property ID, service account JSON, API enable এবং GA4 Viewer permission যাচাই করুন।
- **Active users 0:** Firebase Analytics data আসতে কিছু সময় লাগতে পারে; অ্যাপটি Firebase config-সহ build হয়েছে কি না নিশ্চিত করুন।
