# Privacy Policy for VaultPass

**Effective Date:** October 30, 2026

## 1. Introduction
Welcome to VaultPass. We respect your privacy and are committed to protecting it. This Privacy Policy explains our practices regarding the collection, use, and disclosure of information when you use the VaultPass Android application ("the App"). 

VaultPass is designed from the ground up to be an offline-first password manager. We believe your data belongs exclusively to you. To ensure this, VaultPass operates locally on your device without requiring an internet connection to function. It has two optional network features: sync with your own computer running VaultPass Desktop, directly over your local network (see Section 5), and a check for new versions of the App on GitHub, which is off unless you turn it on (see Section 6).

## 2. Information Stored by the App
VaultPass allows you to store personal information, including but not limited to passwords, usernames, URLs, notes, custom fields, two-factor (2FA) setup keys, and passkeys. **We do not collect, transmit, or have access to any of this information.** 

VaultPass does not require user registration or account creation. There is no central database of users, and you will never be asked to provide an email address, phone number, or personal identifier to use the App.

## 3. Local Device Storage and System Backups
All data you input into VaultPass is stored on your local device, and on your own computer if you choose to sync with VaultPass Desktop. 
* **No Developer Cloud Servers:** The App does not sync your data to any proprietary or developer cloud servers. VaultPass operates no cloud infrastructure and has zero access to your stored records.
* **No Remote Databases:** There are no backend servers associated with the App that receive your data.
* **No External Telemetry or Analytics:** The App does not monitor your behavior, log your actions, or send usage statistics or crash reports to us or any third parties.
* **Android System Cloud Backups:** VaultPass runs no cloud services of its own and cannot access your information. The App turns Android Auto Backup off (`allowBackup="false"`), so Android does not copy VaultPass data to your Google account. The vault database and the App's settings are also excluded from the device-to-device transfer Android offers when you set up a new phone.

## 4. Encryption and Security
All your vault data is protected using standard AES-GCM encryption before it is written to your device's local storage.
* **Master Password:** Your data is encrypted using a key derived from your Master Password. We do not know your Master Password, and it is never transmitted off your device.
* **No Recovery Backdoors:** Because your data is encrypted locally and we do not have your Master Password, **we cannot recover your data if you forget your Master Password.** 
* **Local Brute-Force Protection:** The App includes built-in cooldown timers to protect against local brute-force guessing attempts. 
* **What Is Not Encrypted with Your Vault:** A few things have to be readable while your vault is locked, so they are kept in the App's private storage without vault encryption: your settings, your security score and password counts, each entry's favorite flag, dates and deleted state, and the autofill records described in Section 9. None of these contain a password or other secret.

While VaultPass employs strong, industry-standard cryptographic practices to protect your data, no software or device can be guaranteed to be entirely immune from compromise, especially if the underlying device operating system is compromised or rooted.

## 5. Sync with VaultPass Desktop (Local Network)
VaultPass can sync your vault with VaultPass Desktop on your own computer. This is optional and off until you pair the two devices.
* **Direct and local only:** The phone and the computer connect to each other over your local Wi-Fi network. Your data never goes over the internet and never passes through any server; VaultPass has no sync servers.
* **When the App uses the network:** Only while the Sync screen is open. You pair once by scanning a QR code shown by VaultPass Desktop, and every sync must be approved on the other device before any entries are sent.
* **What is sent:** Your vault entries, including any 2FA setup keys and passkeys saved in them, and deleted-entry records, only to the computer you paired, and only during a sync you started or approved. Every message is encrypted end to end with AES-256-GCM, using keys from a fresh P-256 key exchange that is authenticated by the key created when you paired. The pairing key itself is never sent.
* **Finding your computer:** While the Sync screen is open, the App broadcasts a small announcement on your local network every few seconds so your paired computer can find it. It contains no device name, device identifier or vault data: only a random number, a timestamp, a port number and a short code that only your paired computer can recognise. As with any network traffic, other devices on the network can see your phone's local IP address as the sender.
* **Camera:** The camera is used only to scan QR codes: the pairing code here, and 2FA setup codes if you use them (see Section 10). The image is processed on your phone and is never stored or sent.
* **What is stored on your phone:** For each paired computer, its name and identifier, its last known local IP address and port, when you paired, when you last synced, and the pairing key, which is encrypted with your vault's key. You can remove a pairing at any time from the Sync screen.
* **Permissions:** The App requests network access, which Android requires for any connection including local ones, network and Wi-Fi state to find its local address, and the camera for QR codes.

## 6. Update Checks
VaultPass can ask GitHub, where its releases are published, whether a newer version of the App exists. This is off by default.
* **When the App uses the internet for this:** Only if you turn on "Check for updates when the app opens" in Settings (then once each time the App starts, in the background), or when you tap "Check now" in Settings. An update is downloaded only after you tap the update offer.
* **What is contacted:** `api.github.com`, to read the latest release. When you download an update, `github.com` and the GitHub download server it redirects to. Only HTTPS connections are used.
* **What is sent:** A normal HTTPS request. Its User-Agent header contains the App's version number (for example `VaultPass-Android/2.7.1`). Nothing from your vault, your settings or your accounts is sent. As with any internet connection, GitHub can see your IP address; GitHub's own privacy statement applies to these requests.
* **Checks before installing:** A download must have the size GitHub lists for it and the SHA-256 checksum GitHub publishes for the file; a release without a checksum is not downloaded. The file must be VaultPass, newer than the installed version, and signed with the same key. A file that fails a check is deleted. Android installs the update with its own installer, which may ask you to confirm and checks the signature again.
* **What is stored on your phone:** The setting, and a downloaded update with its version and checksum, kept in the App's private storage until it is installed. The new version deletes the file when it first starts.
* **Permissions:** The App requests permission to install apps, which you allow in Android's "Install unknown apps" settings, and, on Android 12 and later, permission that lets Android skip its confirmation for later updates once VaultPass has installed the current version itself.

## 7. Import and Export Features
VaultPass provides utilities to import and export your vault data (in TXT, JSON, or encrypted VPEX formats) for your own backup purposes. 
* When you export your data, files are written to the location selected by the user through Android's file picker. 
* You are solely responsible for securing the exported files. If you export your vault in unencrypted formats (TXT or JSON), the information will be readable by anyone who gains access to that file. Encrypted VPEX exports remain encrypted until successfully imported and decrypted using the appropriate password.
* Every export format includes your 2FA setup keys and passkeys. In TXT and JSON files these are readable too, including passkey private keys.
* Autofill history, linked apps and the other autofill records in Section 9 stay on your phone and are not part of any export.

## 8. Biometric Authentication
VaultPass supports biometric authentication (such as fingerprint or other supported biometric authentication methods) to unlock your vault, leveraging the Android Keystore system. 
* Biometric data (e.g., your fingerprint data) is managed entirely by your device’s operating system and hardware. 
* VaultPass requests the operating system to verify your identity; the App never collects, stores, or transmits your actual biometric data.

## 9. Android Autofill Service
VaultPass includes an optional Autofill service that helps you quickly fill in usernames, passwords and 2FA codes inside other apps and web browsers, and save new logins.
* **How It Works:** When you turn on this feature in your Android system settings, the service looks at the login screen to find the username, password and code fields. In a browser VaultPass knows, it suggests entries whose website is on the same domain as the page. In other apps, it suggests entries linked to that app. If your vault is locked, it offers to unlock it first.
* **Suggestions You Confirm:** VaultPass can also suggest a login saved for a related site, using a list built into the App, or for a website an app says it belongs to. It asks you before filling, and remembers your answer on your phone.
* **Temporary Processing:** This matching happens entirely on your phone. The app or website on your screen is never sent anywhere.
* **Saving Logins:** When you sign in or sign up, Android can offer to save what you typed. If you accept, Android passes that username and password to VaultPass, which keeps them in memory until you finish the save screen, for at most 10 minutes, and stores them in your vault only if you confirm. Android 8 can't open that screen, so there a new login is saved directly while your vault is unlocked, and VaultPass shows a message.
* **"Never Save" List:** You can stop VaultPass offering to save on a website or in an app. This list of websites and apps is kept on your phone without vault encryption, because Android needs the answer while your vault is locked. You can remove items in Settings.
* **Generated Passwords:** Passwords that autofill generates, and the old passwords they replace, are kept in Autofill history with the website or app they were for: the last 50, encrypted with your vault's key, on this phone only. They are not synced or exported. You can clear the history in Password Generator.
* **Linked Apps:** When you choose an entry for an app with "Search VaultPass…", VaultPass remembers that app's package name together with an internal identifier of the entry, so it can offer the entry there directly next time. These links are stored only on your phone: they are not synced, exported or backed up, and they are deleted when the entry is permanently deleted. You can also remove a link from the entry's details.
* **Checking Apps:** The first time VaultPass fills an app, it stores the app's package name and a fingerprint of its signing certificate, so it can warn you if a different app later uses that name. To do this, and to show the app's name, VaultPass looks at the app asking for a login, including the website that app says it belongs to. Android lets the App see apps that have a launcher icon, but VaultPass does not list or record your installed apps. These records stay on your phone without vault encryption and are not synced, exported or backed up.
* **Chrome:** VaultPass reads one Chrome setting, "Autofill using another service", to show in Settings whether Chrome is set up for autofill. It reads nothing else from Chrome.
* **User Control:** You can turn off or change the Autofill service at any time in your Android system settings.

## 10. Two-Factor (2FA) Codes
VaultPass can keep a login's authenticator setup key and show its one-time codes. This is optional and is set up per entry.
* **Storage:** The setup key is stored in the entry, encrypted like the rest of your vault. It syncs with VaultPass Desktop and is included in backups and exports.
* **Codes:** Codes are worked out on your phone. No network connection is used.
* **Camera:** The camera is used only to scan the setup QR code. The image is processed on your phone and is never stored or sent.
* **Keeping Both Together:** With a setup key in VaultPass, one unlock of your vault opens both the password and the 2FA code for that login.

## 11. Passkeys and Android's Sign-In Sheet
On Android 14 or newer, VaultPass can act as a provider for passkeys and saved logins in Android's sign-in sheet. This is optional: you turn it on in your Android settings under Passwords, passkeys & accounts.
* **How It Works:** When an app or website asks Android for a passkey or login, Android passes the request to VaultPass, including which app or site is asking, and VaultPass lists what matches. This happens entirely on your phone.
* **What the App or Website Receives:** Only what you approve: the login you pick or, for a passkey, its public key when it is created and a signature when you sign in. A passkey's private key is never sent to the app or website. VaultPass itself contacts no server for this.
* **Storage:** A passkey's private key is created on your phone and stored in the entry, encrypted like the rest of your vault. It syncs with VaultPass Desktop and is included in backups and exports.
* **Confirming It's You:** Before a passkey is used, you confirm it by unlocking your vault or with your phone's screen lock. Android handles the screen lock, and your biometric data never reaches the App.
* **Apps You Allow:** Before an app uses a passkey for a website, VaultPass asks you once. It remembers your answer for that app, its signing certificate and that website, on your phone without vault encryption.

## 12. Clipboard Safety
When you copy a username, password or 2FA code, including with the VaultPass Quick Settings tile, the text is temporarily placed onto your Android device clipboard so you can paste it where needed.
* **Automatic Clearing:** VaultPass attempts to automatically clear the copied item after a short time (configurable in the App's Settings), even if the App was closed in the meantime.
* **Copying a 2FA Code After Filling:** When autofill fills a login that has a 2FA setup, VaultPass also copies the current code so you can paste it on the next screen. This is on by default, and you can turn it off in Settings.
* **Clipboard Awareness:** Other applications installed on your phone with clipboard permissions, or custom third-party keyboard apps, may be able to read text on the clipboard while it is active. We encourage you to only use trusted apps and keyboards on your device.

## 13. Third-Party Services
VaultPass is built to function independently of third-party network services. 
* **No Advertising:** The App does not contain any advertisements, nor does it use advertising SDKs.
* **No Tracking:** The App does not use tracking SDKs, remarketing tools, or social media integrations.
* **No Data Selling:** Because we do not collect your data, we do not (and cannot) sell, rent, or share your data with any third parties.
* **GitHub:** The only third-party service the App contacts is GitHub, and only for the optional update check described in Section 6. Nothing from your vault is sent to it.
* **Built-In Lists:** The App ships with public lists that help autofill: the Public Suffix List, Google's list of privileged browsers, and Apple's lists of website password rules and related sites. They are part of the App and are read on your phone; nothing is downloaded or sent to use them.

## 14. Children's Privacy
VaultPass is not intended for children under the age of 13. The App requires no registration and does not collect personal information from any user, including children. All data stored in the App remains on the user's own devices.

## 15. Data Retention
Because VaultPass stores data only on your own devices, you maintain complete control over data retention. Your data is retained for as long as you keep the App installed or keep your exported backup files. You can delete all your data at any time by clearing app data or uninstalling the App and deleting any exported backup files from your device. If you synced with VaultPass Desktop, your computer keeps its own copy until you delete it there.

*(Note: The App features a Recycle Bin that soft-deletes items. Items in the Recycle Bin are permanently and automatically removed from your local device after 7 days, or they can be manually permanently deleted by you at any time. Old passwords in Autofill history are kept until newer ones replace them or you clear the history).*

## 16. Your Privacy Rights (GDPR and CCPA)
Privacy laws like the General Data Protection Regulation (GDPR) and the California Consumer Privacy Act (CCPA) give users rights regarding their personal information, including the rights to view, change, export, and delete their data.

Because VaultPass does not collect or keep your data on any server, you have direct, complete control over these rights on your device:
* **Access and Change:** You can view and edit any entry directly in the App at any time.
* **Data Portability:** You can export your data at any time using the Export feature in Settings.
* **Deletion:** You can permanently delete individual entries using the Recycle Bin, clear Autofill history in Password Generator, or delete all data completely by clearing app data in Android settings or uninstalling the App from your device.

## 17. User Responsibilities
Your privacy and security depend heavily on your own practices. By using VaultPass, you acknowledge that you are responsible for:
* Choosing a strong, unique Master Password that you do not use anywhere else.
* Remembering your Master Password, as it cannot be recovered or reset by us.
* Securing the physical access to your device.
* Managing and securing any unencrypted backup files you choose to export.
* Ensuring your device's operating system is kept up-to-date and free from malware.

## 18. Changes to This Privacy Policy
We may update this Privacy Policy from time to time to reflect changes in our practices or the App's features. Any changes will be posted within this document. Because we do not collect your contact information, we cannot notify you individually of changes. We encourage you to review this Privacy Policy periodically.

## 19. Contact Information
If you have any questions, concerns, or feedback regarding this Privacy Policy or the security practices of VaultPass, please contact us:
* Email: armaanweb100@gmail.com
* GitHub: https://github.com/ArmaanCode2/Vault-pass

