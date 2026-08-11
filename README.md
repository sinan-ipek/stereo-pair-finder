# Stereo Pair Finder — Three-Band Vertical Crop

Android 10+ için, ardışık iki fotoğrafı hizalayıp kare 2:1 Side-by-Side (SBS)
görüntü oluşturan deneme uygulaması.

Bu sürümde basit ve hızlı bir dikey kadraj kararı kullanılır. Rektifiye edilmiş
iki görüntü kabaca üst, orta ve alt bölgelere ayrılır:

- Her bölgenin ortalama yatay paralaksı karşılaştırılır.
- Bir bölge açıkça daha güçlü ise o bölge korunur.
- Paralakslar yakınsa üst ve alt bölgelerin gri ton değişkenliği karşılaştırılır.
- Daha düz, tek renkli bölge kırpılır; fark belirgin değilse merkezden kırpılır.

Perspektif hizalama yalnız analiz kopyalarında paralaks ölçmek için kullanılır;
önizlemeye ve kaydedilen SBS görüntüsüne homografi uygulanmaz. Çıktıdaki düşey
hizalama, güvenilir eşleşmelerin ortanca `sağY - solY` farkı kadar saf translation
ile yapılır. Bu translation görüntüyü warp etmek yerine iki kaynak fotoğrafın
kırpma başlangıç satırlarını farklı seçerek uygulanır.
Özgün portre fotoğrafların tam genişliği korunur. Her iki fotoğraf yalnızca
üstten, alttan veya üstten ve alttan eşit miktarda kırpılır; ardından bozulmamış
iki kare yan yana birleştirilir. Çıktı yolunda döndürme, perspektif düzeltme,
shear, yeniden ölçekleme, esnetme veya yatay kırpma; kadraj kararında ise nesne/konu tanıma ya da
küçük hücre analizi yoktur.

Kaynak fotoğraflar yalnızca okunur. Uygulama bunları silmez, değiştirmez veya
taşımaz. Kaydedilen test çıktıları `Pictures/Stereo SBS Test/` klasörüne yazılır.
