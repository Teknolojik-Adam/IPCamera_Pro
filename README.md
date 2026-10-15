# 📱 IPCamera Pro V1.0

## Elinde kamera olmayan bir masaüstü mü var? Sorun değil.

Birçok masaüstü bilgisayar (özellikle kasa toplamış olanların bildiği üzere) kamera ile gelmez. Görüntülü görüşmeye mi çıkacaksın, canlı mı yayın yapacaksın? Derhal bir web kamerası almam gerekiyor diye düşünürsün.

**IPCamera Pro V1.0** tam bu sorun için var: elindeki Android telefonun zaten harika bir kamera. Üstelik kablolu bir bağlantıya, ek donanıma ya da pahalı bir webcama da ihtiyacın yok. Telefonunu kablosuz olarak Linux bilgisayarına bağla, sanal bir kamera olarak kullan.

Projeyi kısaca özetlemek gerekirse: **kamerasız kasa PC'lere, telefon üzerinden kamera özelliği kazandırmak.** Harici webcam parası ödemeden, evdeki telefonu gerçek bir kamera kaynağına dönüştürmek.

## 🚀 Neler Yapabilirsin?

- **📷 Ön ve arka kamera:** Hangi kamerayı kullanmak istersen.
- **🐧 Linux ile uyumlu:** Fedora üzerinde geliştirildi ve test edildi. Diğer PipeWire destekli Linux dağıtımlarında çalışması hedefleniyor.
- **🔗 Tamamen kablosuz:** Telefon ve bilgisayarın aynı Wi-Fi ağında olması yeterli.
- **🎥 Akıcı görüntü:** 10 / 15 / 20 FPS seçenekleriyle ağ koşullarına göre ayarla. Gerçek FPS telefon ve bağlantı performansına bağlıdır.
- **🖥️ PipeWire sanal kamera:** Kamoso'da test edildi. Diğer uyumlu kamera uygulamalarıyla çalışması hedefleniyor.
- **⚙️ İki çözünürlük:** 640×480 (düşük sistem yükü) ya da 1280×720 (daha net görüntü).
- **🔐 Bağlantı anahtarı:** Kamera yayınına erişim özel bir bağlantı anahtarıyla korunur. Yerel HTTP yayını uçtan uca şifreli değildir; güvenilir Wi-Fi ağı kullanın.

## 💡 Neden Böyle Bir Proje?

Linux'ta telefonu webcam yapmak ilk bakışta basit gibi görünür ama iş pratiğe gelince işler karışır: sanal kamera kurulumu, sürücü uyumsuzlukları, kopan bağlantılar, düşen FPS… Bir de üstüne masaüstü bilgisayarların kamerasız gelmesi eklenince, "hemen webcam alayım" demek çoğu zaman gereksiz bir masraf demek.

IPCamera Pro bu ihtiyaca çözüm sunmayı hedefliyor: kasa PC'ye telefon üzerinden kamera yeteneği kazandırıyor.

## 📲 Kurulum ve Kullanım

1. Android telefonuna IPCamera Pro uygulamasını yükle.
2. Telefonu ve Linux bilgisayarını aynı Wi-Fi ağına bağla.
3. Uygulamada çözünürlüğü ve FPS'i seç.
4. Telefonda kamera yayınını başlat.
5. Bilgisayardaki IPCamera Pro ile bağlan.
6. Kamoso gibi uyumlu bir kamera uygulamasını aç ve sanal kamerayı seç.

## 🛠️ Teknik Özellikler

| Özellik | Detay |
| --- | --- |
| Sürüm | V1.0.0 |
| Mobil platform | Android |
| Masaüstü platform | Linux |
| Kamera altyapısı | Android Camera2 / Camera1 |
| Görüntü aktarımı | MJPEG / HTTP (yerel ağ) |
| Sanal kamera | PipeWire |
| Hedef FPS | 10 / 15 / 20 |
| Çözünürlük | 640×480 / 1280×720 |
| Test edilen uygulama | Kamoso |

## 📌 V1.0 Durumu

IPCamera Pro, Fedora Linux üzerinde PipeWire ve Kamoso ile test edilmiştir. Performans telefon donanımına ve Wi-Fi bağlantısına göre değişebilir. Diğer Linux dağıtımları ve kamera uygulamaları için uyumluluk çalışmaları devam etmektedir.

---

📱 **Telefonun senin kameran.**

🖥️ **Kasan kamera olmadan da idare etmek zorunda değil.**

🎥 **IPCamera Pro ikisini birleştiriyor.**

**Android + Linux + PipeWire = IPCamera Pro**

*Geliştirici: Teknolojik Adam*
