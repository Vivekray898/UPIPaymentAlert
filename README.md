<div align="center">

<a href="https://github.com/official-arvind/UPIPaymentAlert">
  <img src="https://capsule-render.vercel.app/api?type=waving&color=0:0f0c29,50:302b63,100:24243e&height=220&section=header&text=UPI%20Payment%20Alert&fontSize=64&fontColor=ffffff&fontAlignY=38&desc=Voice%20Soundbox%20for%20Android&descAlignY=58&descSize=20&animation=fadeIn" width="100%"/>
</a>

<br/>

[![Typing SVG](https://readme-typing-svg.herokuapp.com?font=JetBrains+Mono&weight=700&size=22&duration=3000&pause=800&color=f97316&center=true&vCenter=true&multiline=false&width=700&lines=Dual+SMS+%26+Notification+Listener+%E2%9A%A1;Independent+Volume+Override+%F0%9F%94%8A;Segmented+Speech+Speed+%E2%8F%B1%EF%B8%8F;SMS+Forwarder+%F0%9F%93%A4;100%25+Offline+Privacy+%F0%9F%9B%A1%EF%B8%8F;Built+by+Arvind+Ji+%F0%9F%9A%80)](https://github.com/official-arvind/UPIPaymentAlert)

<br/>

<a href="https://github.com/official-arvind/UPIPaymentAlert/releases/latest">
  <img src="https://img.shields.io/github/v/release/official-arvind/UPIPaymentAlert?style=for-the-badge&logo=github&logoColor=white&label=Release%202.0%20Gold&color=FFD700" alt="Latest Release"/>
</a>
<a href="https://github.com/official-arvind/UPIPaymentAlert/stargazers">
  <img src="https://img.shields.io/github/stars/official-arvind/UPIPaymentAlert?style=for-the-badge&logo=starship&logoColor=white&color=f7c948" alt="Stars"/>
</a>
&nbsp;
<a href="https://github.com/official-arvind/UPIPaymentAlert/graphs/contributors">
  <img src="https://img.shields.io/github/contributors/official-arvind/UPIPaymentAlert?style=for-the-badge&logo=handshake&logoColor=white&color=22c55e" alt="Contributors"/>
</a>
&nbsp;
<a href="LICENSE.txt">
  <img src="https://img.shields.io/badge/License-MIT-orange?style=for-the-badge&logo=opensourceinitiative&logoColor=white" alt="License"/>
</a>

<br/><br/>

> **A free, open-source voice soundbox for your Android device.**
> Hear your payments instantly, privately, and completely offline. Free forever.

<br/>
<a href="https://github.com/official-arvind/UPIPaymentAlert/releases/download/v2.0-gold/app-debug.apk">
  <img src="https://img.shields.io/badge/Download_Gold_APK-0f0c29?style=for-the-badge&logo=android&logoColor=white" alt="Download APK" />
</a>

</div>

---

<div align="center">
  <img src="https://capsule-render.vercel.app/api?type=rect&color=gradient&customColorList=12,20,28&height=4&section=header" width="100%"/>
</div>

## 🛡️ Stability Policy (Gold Edition)

> [!IMPORTANT]
> **UPI Payment Alert v2.0 is considered the final, feature-complete Gold Edition.** 
> No further features or updates will be pushed to this repository, unless one of the following breaking changes occurs:
> - Major Android API changes breaking the Notification Listener or Background Service model.
> - Structural SMS formatting updates in banking messages that require parser tuning.
> - Critical security vulnerabilities.

---

<div align="center">
<img src="https://capsule-render.vercel.app/api?type=rect&color=gradient&customColorList=12,20,28&height=3&section=header" width="100%"/>
</div>

## ⚡ What Is This?

UPI Payment Alert is a **native Android utility** designed to act as your personal voice speaker (soundbox) for UPI transactions. It runs as a persistent background service, listens to incoming transactional SMS and notification pushes (from GPay, PhonePe, Paytm, BHIM, etc.), and announces the payment details aloud. 

Unlike commercial soundboxes, it requires **no subscription fees** and **does not share your financial transactions with any servers**.

---

## 🗂️ The Features

- ⚡ **Dual SMS & Notification Listeners** — Captures alerts from all financial notification pushes and transactional bank SMS.
- 🔁 **60s Deduplication** — Excludes repeated alerts within 60 seconds to avoid multi-channel echoing.
- 🔊 **Independent Volume Override** — Temporarily sets output to a chosen percentage, restoring system volume immediately after speech.
- ⏱️ **Segmented Speech Speed** — Custom slider sets the speed rate of the currency amount independently of other words.
- 🗣️ **Hindi & English Support** — Features customized voice modules for English and formal Hindi (`"प्राप्त हुए"`).
- 📤 **SMS Forwarder** — Route incoming alerts via carrier SMS with Target Application and Transaction Type (Credit/Debit) filtering logic.
- 🔒 **Offline-by-default privacy** — Zero cloud servers, no user accounts, and no internet permission in the base app. The original on-device privacy model is unchanged; a new optional remote-announcement layer can be turned on separately and only sends encrypted payloads when enabled.
- 🔗 **Multi-child remote announcements** — One owner device can pair with multiple child devices. The owner creates a 6-digit pairing code, each child registers with it, and the owner can view and remove any paired child from a single management screen.

---

## 🚀 Getting Started

1. Download the latest **[v2.0 Gold Edition APK](https://github.com/official-arvind/UPIPaymentAlert/releases/download/v2.0-gold/app-debug.apk)**.
2. Install the application on your Android device.
3. Open the app and grant the required permissions:
   - **Notification Access**: To read incoming alerts from payment apps.
   - **SMS Permission**: To parse bank payment statements.
   - **Battery Optimization Bypass**: To prevent the OS from killing the background service.
4. Select your preferred Language, adjust the Volume and Speed sliders, and try the **Test Sound** buttons!

## 🆕 What Changed In This Version

This branch adds a new optional remote-announcement feature on top of the original offline soundbox app.

### New features

- **Optional remote announcements for child devices** — A paired child device can receive encrypted payment announcements pushed from the owner device through a Supabase relay.
- **Multi-child pairing** — The owner device can pair with more than one child. Each child gets its own pairing record, its own identity, and its own delivery address.
- **Owner-side paired-child management** — The owner can open a paired-children list and remove any one child without unpairing the others.
- **Child-side payment history** — A paired child can keep its own received-payment history locally and clear it independently from the owner.
- **New pairing flow** — Pairing uses a short 6-digit code entered on the owner device, with a Supabase lookup/consume endpoint and FCM token registration on the child side. A legacy long-blob flow is still present as a fallback.

### Privacy changes

- **The base app is still offline by default.** The original payment detection, TTS, and local storage behavior are unchanged, and the app does not gain a hard internet requirement.
- **Remote mode is opt-in.** Remote announcements are disabled until the user enables them, and the app can still run with no network calls when that feature is off.
- **The relay does not see payment content.** Payment payloads are encrypted on the owner device before they leave the device. The relay only carries ciphertext and does not store or log payment details.
- **Pairing key material is not stored server-side.** The Supabase pairing endpoints are single-use / lookup-and-consume for codes, and the session keys stay on the paired devices.
- **The app now has an internet permission only for the optional remote feature.** The original offline behavior does not depend on it.

### What stayed the same

- Dual SMS and notification listeners
- 60-second deduplication
- Independent volume override and segmented speech-speed control
- Hindi/English announcement support
- SMS forwarder with app and credit/debit filtering
- Local-only payment history for the main device


---

## 📝 License

Distributed under the MIT License. See `LICENSE.txt` for more information.

---

<div align="center">
  <p>Built by <a href="https://github.com/official-arvind" target="_blank" rel="noopener">Arvind</a>. MIT licensed. No tracking. No servers.</p>
</div>
