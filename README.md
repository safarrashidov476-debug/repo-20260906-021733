# Screen Recorder (Android)

Ekran va telefon ichki tovushini (musiqa, video, boshqa ilovalar ovozi) yozib, video faylni `Movies/ScreenRecorder` papkasiga (telefon xotirasi) saqlaydigan Android ilovasi. Android 10+ qurilmalarda xuddi telefonning o'z ekran yozuvchisidek telefonda o'ynagan tovushni yozadi. Android 8–9 qurilmalarda esa mikrofon ovozi yoziladi.

## Qanday ishlaydi
- `MainActivity.kt` — ruxsatlarni so'raydi (mikrofon, bildirishnoma) va ekranni yozib olish uchun tizim ruxsatini (`MediaProjection`) oladi.
- `ScreenRecordService.kt` — fon xizmati (foreground service) sifatida ishlaydi. Video `MediaCodec` (H.264) orqali, telefon ichki tovushi esa `AudioPlaybackCapture` orqali yozilib, `MediaMuxer` yordamida bitta `.mp4` faylga birlashtiriladi va `MediaStore` orqali `Movies/ScreenRecorder` papkasiga saqlanadi.

## GitHub'da APK qurish (GitHub Actions)

1. Ushbu papkadagi barcha fayllarni yangi GitHub repository'ga yuklang (push qiling):
   ```bash
   git init
   git add .
   git commit -m "Screen Recorder app"
   git branch -M main
   git remote add origin https://github.com/FOYDALANUVCHI_NOMI/REPO_NOMI.git
   git push -u origin main
   ```
2. GitHub'da repository ochib, **Actions** bo'limiga o'ting.
3. "Build APK" workflow avtomatik ishga tushadi (har bir `push`da). Agar ishga tushmasa, workflow'ni tanlab **Run workflow** tugmasini bosing.
4. Workflow tugagach, repo sahifasidagi o'ng tomonda **Releases** bo'limiga o'ting — u yerda `ScreenRecorder.apk` fayli **to'g'ridan-to'g'ri, ZIP'siz** joylashgan bo'ladi.
5. APK faylni yuklab, telefoningizga o'tkazib o'rnating (noma'lum manbalardan o'rnatishga ruxsat berish kerak bo'lishi mumkin).

## Ilovadan foydalanish
1. Ilovani oching, "Yozishni boshlash" tugmasini bosing.
2. Mikrofon va bildirishnoma ruxsatlarini bering.
3. Ekranni yozib olish uchun tizim so'ragan ruxsatni tasdiqlang.
4. Yozishni tugatish uchun ilovadagi "Yozishni to'xtatish" tugmasini bosing, YOKI ekranning yuqorisidagi bildirishnomalar panelini pastga tortib, u yerdagi "Yozishni to'xtatish" tugmasini bosing.
5. Video `Movies/ScreenRecorder` papkasida saqlanadi (Fayllar ilovasida ko'rish mumkin).

## Eslatma
- `minSdk` — Android 8.0 (API 26) va undan yuqori qurilmalarda ishlaydi.
- Bu — debug (test) APK. Play Store'ga chiqarish uchun signed release build kerak bo'ladi.
