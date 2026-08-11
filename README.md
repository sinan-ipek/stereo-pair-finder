# Stereo Pair Finder — Three-Band Vertical Crop

Android 10+ için, ardışık iki fotoğrafı hizalayıp kare 2:1 Side-by-Side (SBS)
görüntü oluşturan deneme uygulaması.

Bu sürümde basit ve hızlı bir dikey kadraj kararı kullanılır. Rektifiye edilmiş
iki görüntü kabaca üst, orta ve alt bölgelere ayrılır:

- Her bölgenin ortalama yatay paralaksı karşılaştırılır.
- Bir bölge açıkça daha güçlü ise o bölge korunur.
- Paralakslar yakınsa üst ve alt bölgelerin gri ton değişkenliği karşılaştırılır.
- Daha düz, tek renkli bölge kırpılır; fark belirgin değilse merkezden kırpılır.

Portre fotoğraflarda tam genişlik korunur. Kırpma yalnızca üstten, alttan veya iki
taraftan eşit yapılır. Nesne/konu tanıma, küçük hücre analizi ve yatay hedefleme
yoktur.

Kaynak fotoğraflar yalnızca okunur. Uygulama bunları silmez, değiştirmez veya
taşımaz. Kaydedilen test çıktıları `Pictures/Stereo SBS Test/` klasörüne yazılır.
