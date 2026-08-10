# Stereo Pair Finder — güvenli deneme sürümü

Android 10+ (API 29) için Kotlin ve Jetpack Compose ile yazılmış, tamamen cihaz üzerinde çalışan bir SBS 3D üreticisidir. Deneme sürümü **yalnızca Android Photo Picker'da kullanıcının seçtiği** görüntüleri alır; galeri, `DCIM/Camera/` veya başka bir klasör için sorgu, otomatik tarama, izleme, geçmiş ve toplu kayıt içermez.

## İş akışı

Metadata sırası EXIF `DateTimeOriginal` (alt saniye ve saat dilimi dâhil), `MediaStore.DATE_TAKEN`, son çare olarak salt okunur `DATE_MODIFIED` şeklindedir. Dosya adı zaman olarak kullanılmaz. Eşit zamanlar URI ile deterministik sıralanır, zamanı olmayanlar açıkça reddedilir. Zamana göre sıralı yalnızca komşu `1–2, 2–3…` çiftleri analiz edilir; varsayılan sınırlar 15 saniye ve %72'dir.

OpenCV 4.10, yönü EXIF'e göre düzeltilmiş ve en çok 2048 piksellik bellek kopyalarında ORB özellikleri çıkarır. Hamming KNN ve Lowe 0,75 oran testi sonrasında temel matris RANSAC ile hesaplanır. Benzerlik, RANSAC iç eşleşmelerinin küçük özellik kümesine oranının kanıt ölçekli, 0–100'e kırpılmış değeridir; 18'den az iç eşleşme puan üretmez. `stereoRectifyUncalibrated` iki görüntüyü ortak koordinat sistemine taşır. Sonlu/ölçek sınırındaki homografiler kabul edilir; dönüşmüş iç eşleşmelerin **medyan mutlak düşey farkı** kalan düşey paralakstır. Güven, bu medyan ile iç eşleşme desteğini birleştirir. 2,5 px üzeri hata, %35 altı ortak alan veya %97 geçerli olmayan ortak kare reddedilir.

Her homografi ayrıca geçerli piksel maskesine uygulanır. Maskelerin kesişimindeki güvenli dikdörtgenin merkezindeki en büyük kare, her iki göze aynı koordinatlarla uygulanır. Kareler eşit boyutludur; önceki görüntü soldadır. SBS tam 2:1'dir, göz başına en çok 2048 px (toplam 4096×2048), düşük çözünürlük büyütülmez. JPEG kalite değeri **95**'tir.

## Depolama güvenliği ve izinler

Manifest **hiçbir Android izni istemez**. Özellikle `MANAGE_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES` ve `READ_EXTERNAL_STORAGE` yoktur. Photo Picker seçili URI'ye geçici okuma yetkisi verir. Kaynaklar sadece `openInputStream`, `openFileDescriptor(..., "r")` ve salt okunur metadata sorgusuyla açılır; kaynak URI üzerinde output stream, update veya delete yolu yoktur. İşleme yalnızca bitmap kopyalarında yapılır.

Kullanıcı başarılı karttaki kaydet düğmesine basınca `MediaStore.insert()` benzersiz tarih-milisaniye-UUID adıyla `Pictures/Stereo SBS Test/` altında yeni bir kayıt oluşturur. Sadece dönen yeni URI yazılır; `update()` sadece aynı URI'nin `IS_PENDING` bayrağını tamamlar. Hata temizliği yalnızca o çağrıda yeni oluşturulan yarım URI'yi siler. Var olan dosyaya yazılmaz ve her basış yeni dosyadır. Kayıtlı dosya silme arayüzü yoktur.

## Derleme

```bash
./gradlew test
./gradlew lintDebug
./gradlew assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
