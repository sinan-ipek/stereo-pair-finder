# Stereo Pair Finder — Adaptive Framing

Android 10+ için, ardışık iki fotoğrafı hizalayıp kare 2:1 Side-by-Side (SBS)
görüntü oluşturan deneme uygulaması.

Bu sürümde kadraj sabit bir yüzdeyle küçültülmez. Rektifiye edilmiş iki görüntüde:

- görsel olarak belirgin konu ve boş alanlar,
- ileri–geri tutarlılık denetimli yoğun optik akış,
- ortak geçerli görüntü maskesi

birlikte değerlendirilir. Algoritma, hedef kadraja yaklaşmanın büyük bölümünü
sağlayan **en büyük** geçerli kareyi seçer. Belirgin konu zaten merkezdeyse ek
kırpma yapmaz; konu merkezden uzaktaysa yalnızca gerektiği kadar küçülür.

Kaynak fotoğraflar yalnızca okunur. Uygulama bunları silmez, değiştirmez veya
taşımaz. Kaydedilen test çıktıları `Pictures/Stereo SBS Test/` klasörüne yazılır.
