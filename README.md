# Stereo Pair Finder — Three-Band Vertical Crop

Android 10+ için, ardışık iki fotoğrafı hizalayıp kare 2:1 Side-by-Side (SBS)
görüntü oluşturan deneme uygulaması.

Bu sürümde basit ve hızlı bir dikey kadraj kararı kullanılır. Rektifiye edilmiş
iki görüntü kabaca üst, orta ve alt bölgelere ayrılır:

- Her bölgenin ortalama yatay paralaksı karşılaştırılır.
- Bir bölge açıkça daha güçlü ise o bölge korunur.
- Paralakslar yakınsa üst ve alt bölgelerin gri ton değişkenliği karşılaştırılır.
- Daha düz, tek renkli bölge kırpılır; fark belirgin değilse merkezden kırpılır.
- Üst veya alt tarafın kırpılması seçildiğinde kare kenara yaslanmaz: fazla
  yüksekliğin `%70`i seçilen taraftan, `%30`u karşı taraftan kırpılır.

Perspektif hizalama yalnız analiz kopyalarında paralaks ölçmek için kullanılır;
önizlemeye ve kaydedilen SBS görüntüsüne homografi uygulanmaz. Çıktıdaki düşey
hizalama, güvenilir eşleşmelerin ortanca `sağY - solY` farkı kadar saf translation
ile yapılır. Translation sonrası düşey hata sınırı analiz görüntüsünün yüksekliğine
oranlanır; böylece yüksek çözünürlüklü fotoğraflar küçük ve zararsız birkaç piksellik
kalıntı nedeniyle reddedilmez. Bu translation görüntüyü warp etmek yerine iki kaynak fotoğrafın
kırpma başlangıç satırlarını farklı seçerek uygulanır.
Özgün portre fotoğrafların tam genişliği korunur. Her iki fotoğraf yalnızca
dikey yönde `%70/%30`, `%50/%50` veya `%30/%70` dağılımıyla kırpılır; ardından bozulmamış
iki kare yan yana birleştirilir. Çıktı yolunda döndürme, perspektif düzeltme,
shear, yeniden ölçekleme, esnetme veya yatay kırpma; kadraj kararında ise nesne/konu tanıma ya da
küçük hücre analizi yoktur.

Kaynak fotoğraflar yalnızca okunur. Uygulama bunları silmez, değiştirmez veya
taşımaz. Kaydedilen test çıktıları `Pictures/Stereo SBS Test/` klasörüne yazılır.
