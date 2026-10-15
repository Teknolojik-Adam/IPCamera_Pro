📱 IPCamera Pro V1.0

Got a desktop PC without a camera? No problem.

Many desktop computers-especially custom-built PCs-don't come with a webcam. Need to join a video call or start streaming? Your first thought might be, "I guess I need to buy a webcam."

IPCamera Pro V1.0 was built for exactly that situation. Your Android phone already has a great camera, so why buy another one? No USB cable or extra hardware needed. Connect your phone to your Linux PC over Wi-Fi and use it as a virtual camera.

In short: IPCamera Pro brings webcam functionality to desktop PCs that don't have a camera-using the Android phone you already own.

🚀 What Can You Do?

- 📷 Front and rear cameras: Choose the camera you want to use.
- 🐧 Linux support: Developed and tested on Fedora. Support for other PipeWire-based Linux distributions is a goal, but hasn't been verified everywhere.
- 🔗 Wireless connection: Connect your phone and computer to the same Wi-Fi network.
- 🎥 Adjustable frame rate: Choose 10, 15, or 20 FPS. Actual performance depends on your phone and network.
- 🖥️ PipeWire virtual camera: Successfully tested with Kamoso. Compatibility with other camera applications may vary.
- ⚙️ Two resolutions: 640×480 for lower processing load or 1280×720 for a sharper picture.
- 🔐 Access token: The camera stream is protected by a unique connection token. Local HTTP streaming is not end-to-end encrypted, so use a trusted Wi-Fi network.

💡 Why Build This?

Turning a phone into a webcam on Linux sounds simple-until you run into virtual camera setup, driver compatibility issues, dropped connections, or low frame rates.

And if your desktop PC doesn't have a camera in the first place, buying a separate webcam can feel like an unnecessary expense.

IPCamera Pro aims to make better use of what you already have: your phone's camera.

📲 Setup and Usage

1. Install IPCamera Pro on your Android phone.
2. Connect your phone and Linux PC to the same Wi-Fi network.
3. Select the resolution and FPS in the Android app.
4. Start streaming from your phone.
5. Connect using IPCamera Pro on your Linux PC.
6. Open a compatible camera app, such as Kamoso, and select the virtual camera.

🛠️ Technical Specifications

| Feature | Details |
| --- | --- |
| Version | V1.0.0 |
| Mobile platform | Android |
| Desktop platform | Linux |
| Camera API | Android Camera2 / Camera1 |
| Video transport | MJPEG / HTTP (local network) |
| Virtual camera | PipeWire |
| Target frame rates | 10 / 15 / 20 FPS |
| Resolutions | 640×480 / 1280×720 |
| Tested application | Kamoso |

📌 V1.0 Status

IPCamera Pro has been tested on Linux with PipeWire and Kamoso. Performance depends on phone hardware, JPEG encoding, and Wi-Fi conditions. Compatibility with additional Linux distributions and camera applications is still being worked on.

---

📱 Your phone is your camera.

🖥️ Your desktop doesn't have to stay camera-less.

🎥 IPCamera Pro brings them together.

Android + Linux + PipeWire = IPCamera Pro

Developed by Teknolojik Adam
