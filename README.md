# Stereo Pair Finder 2.0

Android 10+ için Kotlin ve Jetpack Compose ile yazılmış, tamamen cihaz üzerinde
çalışan stereo fotoğraf bulucu ve 2:1 Side-by-Side (SBS) üreticisidir.

## Ana ekran

- **Galeriyi Tara:** İlk çalıştırmada erişilebilen galeriyi tarar. Sonraki
  çalıştırmalarda yalnız son başarılı taramadan sonra eklenen fotoğrafları alır.
- **İki Fotoğraf Seç:** Photo Picker ile seçilen iki fotoğrafı ayrıntılı olarak
  denetler; 1.9 sürümündeki elle inceleme ve kaydetme davranışını korur.

## Otomatik tarama

- Fotoğraflar çekim zamanına göre sıralanır.
- Yalnız örtüşmeli komşu çiftler incelenir: `1–2`, `2–3`, `3–4`...
- Ardışık olmayan `1–3` gibi çiftler hiçbir zaman denenmez.
- Uygun çiftler otomatik olarak `Pictures/StereoPairFinder/` klasörüne kaydedilir.
- Uygulamanın kendi çıktı klasörü sonraki taramalara dahil edilmez.
- Android 11 ve üzerinde her depolama alanı için
  `MediaStore version + GENERATION_ADDED` ilerleme noktası kullanılır.
- Android 10'da yedek olarak `DATE_ADDED + MediaStore ID` kullanılır.
- Son sınır fotoğrafı silinse bile ilerleme kaydı korunur.
- Yalnız bir yeni fotoğraf varsa ilerleme noktası ilerletilmez; sonraki fotoğraf
  geldiğinde yeni grubun ilk ardışık çifti kaybolmaz.
- Otomatik çıktı adları kaynak çiftinden kararlı biçimde türetilir. Kesinti
  sonrasında aynı grup yeniden işlenirse aynı SBS ikinci kez oluşturulmaz.

## Görüntü güvenliği

1.9 motoru aynen korunmuştur. Perspektif hizalama yalnız analiz kopyalarında
paralaks ölçümü için kullanılır. Kaydedilen SBS yolunda yalnız:

`kaynak Mat → tam genişlikli dikey kare submat → copy → hconcat`

bulunur. Çıktıya homografi, shear, döndürme, perspektif dönüşümü, farklı X/Y
ölçekleme veya stretch uygulanmaz. Düşey hizalama, iki kaynakta farklı başlangıç
satırları seçen saf translation ile yapılır. Üst/orta/alt kadrajlar `%70/%30`,
`%50/%50` veya `%30/%70` dengeli kırpma kullanır.

Kaynak fotoğraflar yalnızca okunur; silinmez, değiştirilmez veya taşınmaz.

## Doğrulama

```bash
gradle testDebugUnitTest
gradle lintDebug
gradle assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
