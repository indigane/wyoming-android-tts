# Wyoming Android TTS

Brings the power of your Android device's Text-to-Speech (TTS) engines to Home Assistant. This app acts as a Wyoming protocol server, allowing you to use any TTS engine installed on your Android phone or tablet—including Google's high-quality offline voices—as a seamless part of your smart home.

![2025-06-28_22-21-34](https://github.com/user-attachments/assets/5188290a-5f51-4741-9660-87c2110890c0)

## ✨ Features

* **Leverage Any Android TTS:** Use any TTS engine you can install on your Android device (Google, Samsung, etc.).
* **High-Quality Offline Voices:** Access Google's excellent local/offline TTS, which provides natural-sounding voices for many languages, including under-resourced ones.
* **Seamless Home Assistant Integration:** Automatically discovered by Home Assistant as a Wyoming satellite. No complex configuration is needed.
* **Privacy-Focused:** Can be configured to be fully offline and LAN-only, ensuring your voice data never leaves your local network.
* **Lightweight & Efficient:** Runs as a simple background service on your Android device.

## 🤔 Why Wyoming Android TTS?

This project was born out of a desire to use high-quality, local Text-to-Speech voices in Home Assistant, especially for under-resourced languages. Google provides some of the best offline TTS engines available on Android, but there was no easy way to integrate them into Home Assistant's voice ecosystem. This app solves that problem by creating a bridge using the Wyoming protocol.

## 🚀 Getting Started

Getting up and running is simple. Just follow these steps:

1. **Install the App:**
   * Download and install the latest release APK from the [Releases page](https://github.com/indigane/wyoming-android-tts/releases).
2. **Connect to Your Network:**
   * Ensure the Android device running the app is connected to the **same local network (Wi-Fi)** as your Home Assistant instance.
3. **Start the Service:**
   * Open the Wyoming Android TTS app.
   * Press the **"Start Service"** button. A persistent notification will appear, indicating that the service is running.
4. **Configure in Home Assistant:**
   * Home Assistant should automatically discover a new device. You will see a notification in your **Settings \> Devices & Services** page.
   * Click **"Add"** on the discovered Wyoming Android TTS device.
   * Once added, you can select it as your desired TTS engine in your Voice Assistant settings (e.g., in the Assist pipeline).

## 🔒 Optional: Fully Offline / LAN-Only Mode

For a completely private setup, you can prevent the Android device from accessing the internet. This is useful if you want to guarantee all TTS processing happens locally.

1. **Find Device Identifier:** Identify the local **IP address** or **MAC address** of the Android device. This is usually found in the device's Wi-Fi settings. Using the MAC address is often more reliable as the IP address can change. For some devices you may need to go to the Wi-Fi settings of your access point and disable MAC randomization.
2. **Block Internet Access:** In your router's administration page, use Parental Control or Firewall rules to block internet access for that specific IP or MAC address. The app will continue to function on your local network.

## 🤝 Contributing

Contributions are welcome! If you have an idea for a new feature, a bug fix, or an improvement, please feel free to open an issue or submit a pull request.

## 🤖 An Experiment in AI-Assisted Development

As of writing this project is fully LLM created including the code, this Readme and the app icon.

## 📜 License

This project is licensed under the [MIT No Attribution License](LICENSE).
