# Yolda İngilizce

Yolda, ekrana bakmadan İngilizce çalışmak için kişisel bir Android uygulaması.
Android Auto'da bir müzik uygulaması gibi görünür. Direksiyondaki tuşlarla cevap verirsin:

| Tuş | Anlamı |
|---|---|
| **İleri (Next)** | Biliyorum / cümle doğru |
| **Geri (Previous)** | Bilmiyorum / cümle yanlış |
| **Oynat / Duraklat** | Dersi durdur, devam et |
| **Hız** (Android Auto ekranında) | Yavaş / normal konuşma |

Navigasyon konuşunca uygulama susar, navigasyon bitince kaldığı cümleden devam eder.

## Neler var (ilk sürüm)

- **Seviye tespiti:** Yaklaşık 10 dakikalık, uyarlamalı doğru/yanlış testi. Seni A1 ile C1 arasında bir yere koyar.
- **Kelime turu:** En yaygın kelimeler, aralıklı tekrarla (Leitner kutuları). Bilmediğin kelime aynı turda bir kez daha ve sonraki günlerde daha sık gelir.
- **Gramer merdiveni:** A1'den C1'e 30 basamak. Üst üste 4 doğru bir basamak çıkarır, üst üste 2 yanlış bir basamak indirir.
- **Yolculuk modu:** İlk sürüşte seviye tespiti, sonra kelime ve gramer sırayla döner.
- **Oyunlaştırma:** XP, günlük seri, öğrenilen kelime sayısı. Telefon ekranında özet olarak görünür.
- **Yapay zeka (Claude):** Kelime listesi ve gramer soruları bittikçe yenilerini üretir. Üretilenler telefonda saklanır, tünelde de çalışır.

Uygulama yapay zeka olmadan da çalışır (130 kelime ve 120 gramer sorusu içinde gelir).

## Kurulum

### 1. APK'yı indir ve yükle

1. GitHub'da **Actions** sekmesine git, en son başarılı **Build APK** çalışmasını aç.
2. Alttaki **yolda-ingilizce-apk** dosyasını indir, zip'i aç, `app-debug.apk`'yı telefona at ve yükle.
   (Telefon "bilinmeyen kaynak" izni isteyebilir.)

### 2. Android Auto'da görünmesi için

Android Auto, Play Store dışından yüklenen medya uygulamalarını varsayılan olarak gizler.

1. Telefonda **Android Auto ayarları**nı aç.
2. En alttaki **Sürüm** satırına art arda 10 kez dokun, geliştirici modunu aç.
3. Sağ üstteki menüden **Geliştirici ayarları**na gir, **Bilinmeyen kaynaklar** seçeneğini aç.
4. Araba bağlıyken Android Auto'daki uygulama listesinde **Yolda İngilizce**'yi göreceksin.
   Gerekirse Android Auto ayarlarında "Başlatıcıyı özelleştir" bölümünden ekle.

Sesli başlatma: "Hey Google, Yolda İngilizce'de gramer çal" ya da "kelime çal", "seviye testi çal".

### 3. Yapay zekayı bağla (Firebase, bir kere yapılır)

**a) Android uygulamasını Firebase'e ekle**
1. Firebase konsolunda `yoldaingilizce` projesinde **Add app → Android**.
   Paket adı: `com.mocuscuk.yoldaingilizce`.
2. İndirdiğin `google-services.json` dosyasının içeriğini kopyala.
3. GitHub'da repo **Settings → Secrets and variables → Actions → New repository secret**:
   ad `GOOGLE_SERVICES_JSON`, değer dosyanın içeriği.
4. Firebase konsolunda **Authentication → Sign-in method → Anonymous**'u aç.
5. Actions'ta yeni bir derleme çalıştır (ya da yeni bir commit gelince kendisi çalışır) ve yeni APK'yı yükle.

**b) Cihaz kimliğini al**
Uygulamayı aç. Ekranın altındaki "Yapay zeka" bölümünde bir **cihaz kimliği** göreceksin. Bunu not et.

**c) Fonksiyonu yükle** (bilgisayarda, Node.js 22 ve `npm i -g firebase-tools` kurulu olmalı)
```bash
firebase login
firebase functions:secrets:set ANTHROPIC_API_KEY   # Claude API anahtarını yapıştır
cd functions && npm ci && cd ..
firebase deploy --only functions                    # ALLOWED_UIDS sorulunca cihaz kimliğini yaz
```
Birden fazla telefon için kimlikleri virgülle ayır. Değer `functions/.env.yoldaingilizce` dosyasında saklanır (bu dosya repoya gitmez).

Fonksiyon `europe-west1` bölgesinde çalışır ve `claude-opus-5-5` modelini düşük "effort" ile kullanır.
Sadece `ALLOWED_UIDS` listesindeki telefonlar çağırabilir, böylece anahtarın başkası tarafından kullanılamaz.

## Proje yapısı

```
app/                     Android uygulaması (Kotlin, Jetpack Compose, Media3)
  playback/              Android Auto medya servisi ve "ders oynatıcı"
  lesson/                Seviye testi, kelime turu, gramer merdiveni, yolculuk modu
  data/                  İçerik ve ilerleme (JSON dosyaları)
  ai/                    Firebase Functions istemcisi
  assets/                Başlangıç kelimeleri ve gramer soruları
functions/               Firebase Cloud Function: Claude proxy
.github/workflows/       APK derlemesi
```

## Sonraki adımlar

- Sesli cevap (konuşarak cevap verme, yapay zeka ile değerlendirme)
- Günlük haber (Engoo Daily News) ve serbest sohbet
- Daha doğal sesler (bulut TTS)
