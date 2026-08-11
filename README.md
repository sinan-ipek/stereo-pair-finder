# Stereo Pair Finder — Adaptive Framing

Android 10+ için, ardışık iki fotoğrafı hizalayıp kare 2:1 Side-by-Side (SBS)
görüntü oluşturan deneme uygulaması.

Bu sürümde kadraj sabit bir yüzdeyle küçültülmez. Rektifiye edilmiş iki görüntüde:

- görsel olarak belirgin konu ve boş alanlar,
- ileri–geri tutarlılık denetimli yoğun optik akış,
- ortak geçerli görüntü maskesi

kesin bir sırayla değerlendirilir. Bölgesel analiz tek ve bariz bir konu bulursa
kadrajı yalnız o konu belirler. Görsel aday stereo olarak sabitse (uçak kanadı
gibi) veya bariz konu yoksa aday elenir ve kadrajı yoğun paralaks belirler. Böylece
uçak fotoğrafında bulut derinliği, belirgin nesneli bir fotoğrafta ise nesne esas
alınır. Algoritma hedefe yaklaşmanın büyük bölümünü sağlayan **en büyük** geçerli
kareyi seçer ve yalnız gerektiği kadar kırpar.

Kaynak fotoğraflar yalnızca okunur. Uygulama bunları silmez, değiştirmez veya
taşımaz. Kaydedilen test çıktıları `Pictures/Stereo SBS Test/` klasörüne yazılır.
